//! Local stdio MCP adapter. Each submission gets a fresh, serialized game session snapshot.
#[path = "minecraft-mcp/hints.rs"]
mod hints;
#[path = "minecraft-mcp/queue.rs"]
mod queue;

use minecraft_input::{DispatchProgram, FunctionControl, KeyRun, Script, ScriptSource};
use serde_json::{Value, json};
use std::{
    collections::{BTreeMap, BTreeSet},
    env,
    error::Error,
    io::{self, BufRead, BufReader, Write},
    net::Shutdown,
    os::unix::net::UnixStream,
    path::Path,
    sync::{Arc, Mutex},
    thread,
};

type Result<T> = std::result::Result<T, Box<dyn Error>>;

fn receive(reader: &mut BufReader<UnixStream>) -> Result<Value> {
    let mut line = String::new();
    if reader.read_line(&mut line)? == 0 {
        return Err("game disconnected; check game state before submitting again".into());
    }
    let reply: Value = serde_json::from_str(&line)?;
    if let Some(message) = reply["error"].as_str() {
        return Err(message.to_owned().into());
    }
    Ok(reply)
}

#[derive(Default)]
struct Call {
    cancelled: bool,
    socket: Option<UnixStream>,
}

impl Call {
    fn cancel(&mut self) {
        self.cancelled = true;
        if let Some(socket) = &self.socket {
            let _ = socket.shutdown(Shutdown::Both);
        }
    }
}

fn max_mouse_speed() -> Result<f64> {
    let speed = match env::var("MINECRAFT_AGENT_MOUSE_MAX_SPEED") {
        Ok(value) => value.parse::<f64>()?,
        Err(env::VarError::NotPresent) => 50.0,
        Err(error) => return Err(error.into()),
    };
    if !speed.is_finite() || speed <= 0.0 {
        return Err("MINECRAFT_AGENT_MOUSE_MAX_SPEED must be finite and positive".into());
    }
    Ok(speed)
}

// A listener body cannot declare another listener directly. Called functions
// are visited from the function table without following the call graph.
fn program_runs(program: &DispatchProgram) -> impl Iterator<Item = &KeyRun> + Clone {
    program
        .runs
        .iter()
        .chain(program.functions.values().flatten())
        .flat_map(|run| std::iter::once(run).chain(run.handler.iter().flat_map(|(_, body)| body)))
}

fn check_game_support(program: &DispatchProgram, hello: &Value) -> Result<bool> {
    let runs = program_runs(program);
    let uses_functions = !program.functions.is_empty() || runs.clone().any(|r| r.control.is_some());
    if uses_functions && (hello["input_functions"] != true || hello["pause_queue"] != true) {
        return Err("FUNCTION and RETURN require an updated Fabric mod with input_functions and pause_queue".into());
    }
    for run in runs {
        let line = run.line;
        if (run.handler.is_some() || run.clear_listeners || run.screenshot.is_some())
            && hello["input_events"] != true
        {
            return Err(format!(
                "line {line}: input events and explicit screenshots require an updated Fabric mod"
            )
            .into());
        }
        if let Some((event, _)) = &run.handler {
            let supported = match hello.get("event_names") {
                Some(names) => names
                    .as_array()
                    .is_some_and(|names| names.iter().any(|name| name.as_str() == Some(event))),
                None => matches!(event.as_str(), "Hurt" | "HpLost"),
            };
            if !supported {
                return Err(format!("line {line}: installed Fabric mod does not support ON({event}); update the mod and restart Minecraft").into());
            }
        }
        let needs_mouse =
            run.wait_mouse || run.clear || !run.moves.is_empty() || !run.buttons.is_empty();
        if needs_mouse && hello["mouse_input"] != true {
            return Err(format!("line {line}: installed Fabric mod only supports keyboard input; update the mod and restart Minecraft").into());
        }
    }
    Ok(uses_functions)
}

fn connect_game(call: &Mutex<Call>) -> Result<(BufReader<UnixStream>, Value)> {
    let path = env::var_os("MINECRAFT_AGENT_SOCKET")
        .ok_or("set MINECRAFT_AGENT_SOCKET to the game socket path")?;
    let path = Path::new(&path);
    let socket = UnixStream::connect(path).map_err(|error| {
        let hint = if error.kind() == io::ErrorKind::NotFound {
            "; check that Minecraft is running and its log shows 'Input bridge listening on' at this path"
        } else {
            ""
        };
        format!("cannot connect to Minecraft game socket {}: {error}{hint}", path.display())
    })?;
    {
        let mut state = call.lock().unwrap();
        if state.cancelled {
            return Err("request cancelled".into());
        }
        state.socket = Some(socket.try_clone()?);
    }
    let mut reader = BufReader::new(socket);
    let hello = receive(&mut reader)?;
    Ok((reader, hello))
}

fn inspect(args: Option<&Value>, call: &Mutex<Call>) -> Result<Value> {
    let state = queue::inspect(args, || inspect_game(call))?;
    Ok(json!({"structuredContent":state,"content":[{"type":"text","text":state.to_string()}]}))
}

fn inspect_game(call: &Mutex<Call>) -> Result<Value> {
    let (mut reader, hello) = connect_game(call)?;
    if hello["inspect_input"] != true {
        return Err(
            "installed Fabric mod lacks inspect_input; update the mod and restart Minecraft".into(),
        );
    }
    let queue_supported = hello["pause_queue"] == true;
    let mut request = json!({"op":"inspect_input"});
    if queue_supported {
        request["queue_version"] = json!(1);
    }
    serde_json::to_writer(reader.get_mut(), &request)?;
    reader.get_mut().write_all(b"\n")?;
    let state = receive(&mut reader)?;
    if queue_supported && !state["queue_entries"].is_array() {
        return Err("game did not return a queue snapshot".into());
    }
    Ok(state)
}

fn encode_runs(runs: Vec<KeyRun>, queue_supported: bool) -> Result<Vec<Value>> {
    runs.into_iter()
        .map(|r| -> Result<Value> {
            let mut run = json!({
                "line":r.line, "ticks":r.ticks.to_string(), "held":r.held,
                "moves":r.moves, "buttons":r.buttons, "wait_mouse":r.wait_mouse, "clear":r.clear
            });
            if queue_supported {
                run["key_actions"] = json!(r.key_actions);
                run["source"] = json!(r.source);
                run["pause"] = json!(r.pause);
            }
            if let Some((event, body)) = r.handler {
                run["event"] = json!(event);
                run["body"] = json!(encode_runs(body, queue_supported)?);
            }
            if r.clear_listeners {
                run["clear_listeners"] = json!(true);
            }
            if let Some(path) = r.screenshot {
                run["screenshot"] = json!(std::path::absolute(path)?);
            }
            match r.control {
                Some(FunctionControl::Call(name)) => run["call"] = json!(name),
                Some(FunctionControl::Return) => run["return"] = json!(true),
                None => (),
            }
            Ok(run)
        })
        .collect()
}

fn encode_program(program: DispatchProgram, hello: &Value, max_speed: f64) -> Result<Value> {
    let uses_functions = check_game_support(&program, hello)?;
    let queue_supported = hello["pause_queue"] == true;
    if !queue_supported && program.runs.iter().any(|r| r.pause) {
        return Err("PAUSE requires the pause_queue Fabric mod update; update the mod and restart Minecraft".into());
    }
    let runs = encode_runs(program.runs, queue_supported)?;
    let mut request = json!({"keys":program.keys,"runs":runs,"persistent":program.persistent,"max_speed":max_speed});
    if queue_supported {
        request["queue_version"] = json!(1);
    }
    if uses_functions {
        let functions = program
            .functions
            .into_iter()
            .map(|(name, body)| Ok((name, encode_runs(body, queue_supported)?)))
            .collect::<Result<BTreeMap<_, _>>>()?;
        request["functions"] = json!(functions);
    }
    Ok(request)
}

fn play(args: &Value, call: &Mutex<Call>) -> Result<Value> {
    let args = args.as_object().ok_or("expected script or path")?;
    if args
        .keys()
        .any(|key| !matches!(key.as_str(), "script" | "path" | "include_screenshot"))
    {
        return Err("unknown input_script argument".into());
    }
    let include_screenshot = match args.get("include_screenshot") {
        Some(value) => value
            .as_bool()
            .ok_or("include_screenshot must be a boolean")?,
        None => true,
    };
    let source = match (args.get("script"), args.get("path")) {
        (Some(Value::String(text)), None) => ScriptSource::Inline(text),
        (None, Some(Value::String(file))) => ScriptSource::Path(Path::new(file)),
        _ => return Err("supply exactly one of script or path as a string".into()),
    };
    let script = source.load(&env::current_dir()?)?;
    let max_speed = max_mouse_speed()?;
    let (mut reader, hello) = connect_game(call)?;
    let persistent: BTreeSet<String> = serde_json::from_value(hello["persistent"].clone())?;
    let program = script.compile(persistent);
    let mut request = encode_program(program, &hello, max_speed)?;
    if !include_screenshot {
        request["include_screenshot"] = json!(false);
    }
    serde_json::to_writer(reader.get_mut(), &request)?;
    reader.get_mut().write_all(b"\n")?;
    let reply = receive(&mut reader).map_err(|error| locate_key_error(&script, error))?;
    stop_result(&reply, include_screenshot)
}

// The mod validates every key before queue.submit. The existing wire error
// identifies the key by name, which also identifies its source in this submission.
fn locate_key_error(script: &Script, error: Box<dyn Error>) -> Box<dyn Error> {
    let message = error.to_string();
    if let Some((prefix, key)) = message.rsplit_once(": unknown keyboard key: ")
        && let Some((source, line)) = script.key_location(key)
        && prefix == format!("java.lang.IllegalArgumentException: line {line}")
    {
        return format!("{source}: line {line}: unknown keyboard key: {key}").into();
    }
    error
}

fn stop_result(reply: &Value, include_screenshot: bool) -> Result<Value> {
    let ticks = reply["ticks"]
        .as_u64()
        .ok_or("missing or invalid stop tick count")?;
    let world = reply["world"]
        .as_str()
        .ok_or("missing or invalid stop world state")?;
    let mut status = json!({"ticks":ticks, "world":world});
    if let Some(reason) = reply.get("pause_reason") {
        status["pause_reason"] = reason.clone();
    }
    if let Some(origins) = reply.get("pause_listeners") {
        status["pause_listeners"] = origins.clone();
    }
    if !include_screenshot {
        return Ok(json!({"content":[{"type":"text","text":status.to_string()}]}));
    }
    let data = reply["png"].as_str().ok_or("missing stop image")?;
    let (screenshot_id, width, height) = hints::remember(reply)?;
    status["screenshot_id"] = json!(screenshot_id);
    status["width"] = json!(width);
    status["height"] = json!(height);
    Ok(json!({"content":[{"type":"text","text":status.to_string()},
                         {"type":"image","mimeType":"image/png","data":data}]}))
}

fn respond(request: &Value) -> Value {
    let result = match request["method"].as_str() {
        Some("initialize") => {
            json!({"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"minecraft-agent","version":env!("CARGO_PKG_VERSION")}})
        }
        Some("tools/list") => {
            json!({"tools":[{"name":"input_script","description":"Run a keyboard and mouse input script from exactly one of script or path and return its stop status at PAUSE, a pause shortcut, or END. See docs/input-script/README.md.","inputSchema":{"type":"object","properties":{"script":{"type":"string"},"path":{"type":"string"},"include_screenshot":{"type":"boolean","default":true,"description":"Include the stopped frame as a PNG image."}},"additionalProperties":false}},queue::tool(),hints::tool()]})
        }
        Some("ping") => json!({}),
        method => {
            let (code, message) = if method == Some("tools/call") {
                (-32602, "unknown tool")
            } else {
                (-32601, "unknown method")
            };
            return json!({"jsonrpc":"2.0","id":request["id"],"error":{"code":code,"message":message}});
        }
    };
    json!({"jsonrpc":"2.0","id":request["id"],"result":result})
}

fn send(value: &Value) -> io::Result<()> {
    let mut out = io::stdout().lock();
    serde_json::to_writer(&mut out, value)?;
    writeln!(out)
}

fn main() -> io::Result<()> {
    // Keep stdin responsive to cancellation even during a long in-game wait.
    let pending = Arc::new(Mutex::new(None::<(Value, Arc<Mutex<Call>>)>));
    for line in io::stdin().lock().lines() {
        let Ok(request) = serde_json::from_str::<Value>(&line?) else {
            send(
                &json!({"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"invalid JSON"}}),
            )?;
            continue;
        };
        let method = request["method"].as_str();
        let Some(id) = request.get("id").cloned() else {
            if method == Some("notifications/cancelled") {
                let guard = pending.lock().unwrap();
                if let Some((id, call)) = guard.as_ref()
                    && id == &request["params"]["requestId"]
                {
                    call.lock().unwrap().cancel();
                }
            }
            continue;
        };
        let tool = request["params"]["name"].as_str();
        if method != Some("tools/call")
            || !matches!(tool, Some("input_script" | "cursor_hint" | "inspect_input"))
        {
            send(&respond(&request))?;
            continue;
        }
        let call = Arc::new(Mutex::new(Call::default()));
        {
            let mut slot = pending.lock().unwrap();
            if slot.is_some() {
                send(
                    &json!({"jsonrpc":"2.0","id":id,"result":{"isError":true,"content":[{"type":"text","text":"tool call in progress"}]}}),
                )?;
                continue;
            }
            *slot = Some((id.clone(), call.clone()));
        }
        let slot = Arc::clone(&pending);
        thread::spawn(move || {
            let args = &request["params"]["arguments"];
            let result = match request["params"]["name"].as_str() {
                Some("inspect_input") => inspect(request["params"].get("arguments"), &call),
                Some("cursor_hint") => hints::annotate(args),
                _ => play(args, &call),
            }
            .unwrap_or_else(
                |e| json!({"isError":true,"content":[{"type":"text","text":e.to_string()}]}),
            );
            let mut guard = slot.lock().unwrap();
            guard.take();
            let _ = send(&json!({"jsonrpc":"2.0","id":id,"result":result}));
        });
    }
    if let Some((_, call)) = pending.lock().unwrap().take() {
        call.lock().unwrap().cancel();
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn imported_demo_keys_are_validated_without_dispatching_its_entry() {
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let temp =
            std::env::temp_dir().join(format!("mc-import-wire-{}-{timestamp}", std::process::id()));
        std::fs::create_dir(&temp).unwrap();
        let library = temp.join("movement.mcinput");
        std::fs::write(
            &library,
            "BEGIN\nON(Hurt)\nBEGIN\nKey(no_such_key)\nEND\nEND",
        )
        .unwrap();
        let source = ScriptSource::Inline("IMPORT \"movement.mcinput\"\nBEGIN\nWait(1)\nEND");
        let script = source.load(&temp).unwrap();
        let wire = encode_program(
            script.compile(Default::default()),
            &json!({"pause_queue":true}),
            50.,
        )
        .unwrap();
        assert_eq!(wire["keys"]["no_such_key"], 4);
        assert_eq!(wire["runs"].as_array().unwrap().len(), 1);
        assert_eq!(wire["runs"][0]["source"], "Wait(1)");
        let error = locate_key_error(
            &script,
            "java.lang.IllegalArgumentException: line 4: unknown keyboard key: no_such_key".into(),
        );
        assert!(
            error
                .to_string()
                .ends_with("movement.mcinput: line 4: unknown keyboard key: no_such_key")
        );
        let unrelated = "java.io.IOException: cannot write path: unknown keyboard key: no_such_key";
        assert_eq!(
            locate_key_error(&script, unrelated.into()).to_string(),
            unrelated
        );
        std::fs::remove_file(&library).unwrap();
        std::fs::remove_dir(&temp).unwrap();
    }

    #[test]
    fn text_only_stop_requires_valid_status_and_skips_legacy_images() {
        for reply in [
            json!({}),
            json!({"world":"paused","ticks":null}),
            json!({"world":false,"ticks":0}),
        ] {
            assert!(stop_result(&reply, false).is_err());
        }
        let status = json!({"world":"paused","ticks":0,"pause_reason":"end"});
        let mut legacy = status.clone();
        legacy["png"] = json!("unused payload from an older mod");
        for reply in [&status, &legacy] {
            assert_eq!(
                stop_result(reply, false).unwrap(),
                json!({"content":[{"type":"text","text":status.to_string()}]})
            );
        }
        assert!(stop_result(&status, true).is_err());
    }

    #[test]
    fn extended_events_require_an_advertised_name() {
        let script = Script::parse("BEGIN\nON(Death)\nBEGIN\nPAUSE\nEND\nEND").unwrap();
        let program = script.compile(Default::default());
        let mut hello = json!({"input_events":true});
        assert!(check_game_support(&program, &hello).is_err());
        hello["event_names"] = json!(["CollideX", "CollideZ"]);
        assert!(check_game_support(&program, &hello).is_err());
        hello["event_names"] = json!(["Death"]);
        assert!(check_game_support(&program, &hello).is_ok());
    }

    #[test]
    fn events_require_game_support_and_keep_the_handlers_source_lines() {
        let script =
            Script::parse("BEGIN\nON(Hurt)\nBEGIN\nKeyRelease(w) PAUSE\nEND\nEND").unwrap();
        let old = json!({"pause_queue":true,"mouse_input":true});
        assert!(encode_program(script.compile(BTreeSet::new()), &old, 50.).is_err());
        let updated = json!({"input_events":true,"pause_queue":true,"mouse_input":true});
        let wire = encode_program(script.compile(BTreeSet::new()), &updated, 50.).unwrap();
        assert_eq!(wire["keys"]["w"], 4);
        let listener = &wire["runs"][0];
        assert_eq!(listener["event"], "Hurt");
        assert_eq!(listener["line"], 2);
        assert_eq!(listener["body"][0]["key_actions"]["w"], "release");
        assert_eq!(listener["body"][1]["pause"], true);
    }

    #[test]
    fn pause_wire_keeps_declarations_and_split_source_fragments() {
        let script = Script::parse("BEGIN\nWait(17293822569102704640) KeyPress(w) KeyRelease(a) Key(d) Mouse(left) PAUSE // stop\nEND").unwrap();
        let old = json!({"mouse_input":true});
        assert!(encode_program(script.compile(BTreeSet::new()), &old, 50.).is_err());
        let hello = json!({"pause_queue":true,"mouse_input":true});
        let request = encode_program(script.compile(BTreeSet::new()), &hello, 50.).unwrap();
        assert_eq!(request["queue_version"], 1);
        let runs = request["runs"].as_array().unwrap();
        assert_eq!(runs[0]["key_actions"], json!({}));
        assert_eq!(runs[0]["source"], "Wait(17293822569102704640)");
        assert_eq!(
            runs[1]["source"],
            "KeyPress(w) KeyRelease(a) Key(d) Mouse(left)"
        );
        assert_eq!(
            runs[1]["key_actions"],
            json!({"w":"press","a":"release","d":"temporary"})
        );
        assert_eq!(runs[1]["pause"], false);
        assert_eq!(runs[2]["pause"], true);
        assert_eq!(runs[2]["source"], "PAUSE");
        assert_eq!(runs[2]["ticks"], "0");
        let legacy = Script::parse("BEGIN\nKey(w)\nEND").unwrap();
        let request = encode_program(legacy.compile(BTreeSet::new()), &old, 50.).unwrap();
        assert!(request.get("queue_version").is_none());
        assert_eq!(request["runs"][0]["held"], json!(["w"]));
    }

    #[test]
    fn keyboard_only_mod_rejects_mouse_before_dispatch() {
        let old = json!({"persistent":[]});
        let updated = json!({"persistent":[],"mouse_input":true});
        for (line, needs_mouse) in [
            ("Key(w)", false),
            ("Mouse(left)", true),
            ("WaitMouseMove()", true),
            ("CLEAR", true),
            ("MouseMove(Px(1), Px(0))", true),
        ] {
            let script = Script::parse(&format!("BEGIN\n{line}\nEND")).unwrap();
            let program = script.compile(BTreeSet::new());
            assert_eq!(check_game_support(&program, &old).is_err(), needs_mouse);
            assert!(check_game_support(&program, &updated).is_ok());
        }
    }

    #[test]
    fn functions_are_encoded_as_zero_tick_controls_without_expanding_recursion() {
        let program = Script::parse(concat!(
            "FUNCTION $outer_1 // declaration\nBEGIN\n",
            "ON(Hurt)\nBEGIN\n$INNER()\nRETURN // handler return\nEND\nEND\n",
            "FUNCTION $INNER\nBEGIN\n$outer_1()\nRETURN\nEND\n",
            "FUNCTION $Unused\nBEGIN\nKeyRelease(a)\n$Unused()\nEND\n",
            "BEGIN\n$outer_1() // first call\nKey(a)\nEND"
        ))
        .unwrap()
        .compile(Default::default());
        let hello = json!({"pause_queue":true,"input_functions":true,"input_events":true});
        let request = encode_program(program, &hello, 50.).unwrap();
        assert_eq!(request["queue_version"], 1);
        assert_eq!(request["keys"]["a"], 16);
        let runs = request["runs"].as_array().unwrap();
        assert_eq!(runs.len(), 2);
        assert_eq!(runs[0]["source"], "$outer_1()");
        assert_eq!(runs[0]["call"], "outer_1");
        assert_eq!(runs[0]["ticks"], "0");
        assert_eq!(runs[0]["line"], 20);
        let handler = &request["functions"]["outer_1"][0];
        assert_eq!(handler["line"], 3);
        assert_eq!(handler["body"][0]["call"], "INNER");
        assert_eq!(handler["body"][1]["return"], true);
        assert_eq!(handler["body"][1]["line"], 6);
        assert_eq!(handler["body"][1]["ticks"], "0");
        assert_eq!(request["functions"]["INNER"][0]["call"], "outer_1");
        assert_eq!(request["functions"]["INNER"][1]["return"], true);
        assert_eq!(request["functions"]["Unused"][0]["line"], 16);
        assert_eq!(request["functions"]["Unused"][1]["call"], "Unused");
    }

    #[test]
    fn using_functions_or_return_requires_a_compatible_queue() {
        for source in [
            "FUNCTION $unused\nBEGIN\nEND\nBEGIN\nEND",
            "BEGIN\nRETURN\nEND",
            "BEGIN\nON(Hurt)\nBEGIN\nRETURN\nEND\nEND",
        ] {
            let script = Script::parse(source).unwrap();
            for hello in [
                json!({"input_events":true,"pause_queue":true}),
                json!({"input_events":true,"input_functions":true}),
            ] {
                let error =
                    encode_program(script.compile(Default::default()), &hello, 50.).unwrap_err();
                assert!(error.to_string().contains("updated Fabric mod"), "{error}");
            }
        }
        let hello = json!({"pause_queue":true,"input_functions":true});
        let script = Script::parse("BEGIN\nRETURN\nEND").unwrap();
        let request = encode_program(script.compile(Default::default()), &hello, 50.).unwrap();
        assert_eq!(request["functions"], json!({}));
        assert_eq!(request["runs"][0]["return"], true);
        let script = Script::parse("BEGIN\nKey(w)\nEND").unwrap();
        let hello = json!({"pause_queue":true});
        let request = encode_program(script.compile(Default::default()), &hello, 50.).unwrap();
        assert!(request.get("functions").is_none());
        assert!(request["runs"][0].get("call").is_none());
        assert!(request["runs"][0].get("return").is_none());
    }

    #[test]
    fn unused_function_bodies_are_checked_against_game_capabilities() {
        for (body, line, missing) in [
            ("ON(Death)\nBEGIN\nEND", 3, "ON(Death)"),
            ("ON(Hurt)\nBEGIN\nMouse(left)\nEND", 5, "keyboard input"),
        ] {
            let script =
                Script::parse(&format!("FUNCTION $unused\nBEGIN\n{body}\nEND\nBEGIN\nEND"))
                    .unwrap();
            let hello = json!({"pause_queue":true,"input_events":true,"input_functions":true});
            let error =
                encode_program(script.compile(Default::default()), &hello, 50.).unwrap_err();
            assert!(
                error.to_string().contains(&format!("line {line}")),
                "{error}"
            );
            assert!(error.to_string().contains(missing), "{error}");
        }
    }
}
