//! Local stdio MCP adapter for game input, state and screenshots.
#[path = "minecraft-mcp/game_state.rs"]
mod game_state;
#[path = "minecraft-mcp/hints.rs"]
mod hints;
#[path = "minecraft-mcp/queue.rs"]
mod queue;

use minecraft_input::{DispatchProgram, FlowRun, FunctionControl, KeyRun, Script, ScriptSource};
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

const DEFAULT_MAX_TICKS: i64 = 600;

type Result<T> = std::result::Result<T, Box<dyn Error>>;

fn receive(reader: &mut impl BufRead) -> Result<Value> {
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

fn program_runs(program: &DispatchProgram) -> impl Iterator<Item = &KeyRun> + Clone {
    let mut stack: Vec<_> = program
        .checks
        .iter()
        .rev()
        .chain(program.functions.values().rev())
        .chain(std::iter::once(&program.runs))
        .map(|body| body.iter())
        .collect();
    std::iter::from_fn(move || {
        loop {
            if let Some(run) = stack.last_mut()?.next() {
                if let Some(flow) = &run.flow {
                    stack.extend(flow.bodies().rev().map(<[KeyRun]>::iter));
                }
                if let Some((_, body)) = &run.handler {
                    stack.push(body.iter());
                }
                return Some(run);
            }
            stack.pop();
        }
    })
}

fn run_location(run: &KeyRun) -> String {
    run.file.as_ref().map_or_else(
        || format!("line {}", run.line),
        |file| format!("{file}: line {}", run.line),
    )
}

fn check_game_support(program: &DispatchProgram, hello: &Value) -> Result<bool> {
    let runs = program_runs(program);
    if (hello["input_control_flow"] != true || hello["pause_queue"] != true)
        && let Some(run) = runs
            .clone()
            .find(|run| run.flow.is_some() || run.has_flow_expression)
    {
        let location = run_location(run);
        return Err(format!("{location}: this input script requires an updated Fabric mod with input_control_flow and pause_queue").into());
    }
    let uses_functions = !program.functions.is_empty() || runs.clone().any(|r| r.control.is_some());
    let uses_values = !program.params.is_empty() || runs.clone().any(|r| r.values.is_some());
    if uses_values && (hello["input_values"] != true || hello["pause_queue"] != true) {
        return Err(
            "input script values require an updated Fabric mod with input_values and pause_queue"
                .into(),
        );
    }
    if uses_functions && (hello["input_functions"] != true || hello["pause_queue"] != true) {
        return Err("FUNCTION and RETURN require an updated Fabric mod with input_functions and pause_queue".into());
    }
    for run in runs {
        let location = run_location(run);
        if (run.handler.is_some() || run.clear_listeners || run.screenshot.is_some())
            && hello["input_events"] != true
        {
            return Err(format!(
                "{location}: input events and explicit screenshots require an updated Fabric mod"
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
                return Err(format!("{location}: installed Fabric mod does not support ON({event}); update the mod and restart Minecraft").into());
            }
        }
        let dynamic_mouse = run
            .values
            .as_ref()
            .and_then(|value| value["actions"].as_array())
            .is_some_and(|actions| {
                actions.iter().any(|action| {
                    action[0]
                        .as_str()
                        .is_some_and(|name| name.starts_with("Mouse"))
                })
            });
        let needs_mouse = dynamic_mouse
            || run.wait_mouse
            || run.clear
            || !run.moves.is_empty()
            || !run.buttons.is_empty();
        if needs_mouse && hello["mouse_input"] != true {
            return Err(format!("{location}: installed Fabric mod only supports keyboard input; update the mod and restart Minecraft").into());
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
    let state = queue::inspect(args, || input_snapshot(call))?;
    Ok(json!({"structuredContent":state,"content":[{"type":"text","text":state.to_string()}]}))
}

fn input_snapshot(call: &Mutex<Call>) -> Result<Value> {
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
                if let Some(file) = r.file {
                    run["file"] = json!(file);
                }
            }
            if let Some(values) = r.values {
                run["v"] = values;
            }
            if let Some((event, body)) = r.handler {
                run["event"] = json!(event);
                run["body"] = Value::Array(encode_runs(body, queue_supported)?);
            }
            if let Some(flow) = r.flow {
                run["flow"] = encode_flow(flow, queue_supported)?;
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

fn encode_flow(flow: FlowRun, queue_supported: bool) -> Result<Value> {
    let flow = match flow {
        FlowRun::If(branches) => {
            let branches = branches
                .into_iter()
                .map(|branch| {
                    let mut wire = json!({"line": branch.line});
                    if let Some(condition) = branch.condition {
                        wire["expr"] = condition;
                    }
                    wire["body"] = Value::Array(encode_runs(branch.body, queue_supported)?);
                    Ok(wire)
                })
                .collect::<Result<Vec<_>>>()?;
            let mut wire = json!({"op":"if"});
            wire["branches"] = Value::Array(branches);
            wire
        }
        FlowRun::While { condition, body } => {
            let mut wire = json!({"op":"while"});
            wire["expr"] = condition;
            wire["body"] = Value::Array(encode_runs(body, queue_supported)?);
            wire
        }
        FlowRun::For {
            name,
            expression,
            body,
        } => {
            let mut wire = json!({"op":"for","name":name});
            wire["expr"] = expression;
            wire["body"] = Value::Array(encode_runs(body, queue_supported)?);
            wire
        }
        FlowRun::Break => json!({"op":"break"}),
        FlowRun::Continue => json!({"op":"continue"}),
    };
    Ok(flow)
}

fn encode_program(program: DispatchProgram, hello: &Value, max_speed: f64) -> Result<Value> {
    let uses_functions = check_game_support(&program, hello)?;
    let queue_supported = hello["pause_queue"] == true;
    if !queue_supported && program.runs.iter().any(|r| r.pause) {
        return Err("PAUSE requires the pause_queue Fabric mod update; update the mod and restart Minecraft".into());
    }
    let runs = encode_runs(program.runs, queue_supported)?;
    let mut request =
        json!({"keys":program.keys,"persistent":program.persistent,"max_speed":max_speed});
    request["runs"] = Value::Array(runs);
    if queue_supported {
        request["queue_version"] = json!(1);
    }
    if uses_functions {
        let functions = program
            .functions
            .into_iter()
            .map(|(name, body)| Ok((name, Value::Array(encode_runs(body, queue_supported)?))))
            .collect::<Result<serde_json::Map<_, _>>>()?;
        request["functions"] = Value::Object(functions);
    }
    if !program.params.is_empty() {
        request["params"] = json!(program.params);
    }
    if !program.checks.is_empty() {
        let checks = program
            .checks
            .into_iter()
            .map(|body| encode_runs(body, queue_supported).map(Value::Array))
            .collect::<Result<Vec<_>>>()?;
        request["checks"] = Value::Array(checks);
    }
    Ok(request)
}

fn play(args: &Value, call: &Mutex<Call>) -> Result<Value> {
    let args = args.as_object().ok_or("expected script or path")?;
    if args.keys().any(|key| {
        !matches!(
            key.as_str(),
            "script" | "path" | "include_screenshot" | "max_ticks"
        )
    }) {
        return Err("unknown input_script argument".into());
    }
    let max_ticks = match args.get("max_ticks") {
        Some(value) => value
            .as_i64()
            .filter(|&ticks| ticks > 0)
            .ok_or("max_ticks must be a positive 64-bit integer")?,
        None => DEFAULT_MAX_TICKS,
    };
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
    if hello["max_ticks"] != true {
        return Err("input_script requires a Fabric mod with max_ticks support; update the mod and restart Minecraft".into());
    }
    let persistent: BTreeSet<String> = serde_json::from_value(hello["persistent"].clone())?;
    let program = script.compile(persistent);
    let mut request = encode_program(program, &hello, max_speed)?;
    request["max_ticks"] = json!(max_ticks);
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
    for field in [
        "pause_reason",
        "pause_listeners",
        "events",
        "events_omitted",
        "transition",
        "screen",
        "stdout",
        "script_error",
        "capture_error",
    ] {
        if let Some(value) = reply.get(field) {
            status[field] = value.clone();
        }
    }
    let image = if include_screenshot && reply.get("capture_error").is_none() {
        let data = reply["png"].as_str().ok_or("missing stop image")?;
        Some(hints::image_content(data, &reply["camera"], &mut status)?)
    } else {
        None
    };
    let text = if let Some(value) = reply.get("return_value_json") {
        use serde_json::value::{RawValue, to_raw_value};
        let value: &RawValue =
            serde_json::from_str(value.as_str().ok_or("invalid serialized return value")?)?;
        let mut fields = BTreeMap::new();
        for (key, field) in status.as_object().expect("stop status is an object") {
            fields.insert(key.as_str(), to_raw_value(field)?);
        }
        fields.insert("return_value", value.to_owned());
        serde_json::to_string(&fields)?
    } else {
        status.to_string()
    };
    let mut content = vec![json!({"type":"text","text":text})];
    content.extend(image);
    let mut result = json!({"content":content});
    if reply.get("script_error").is_some() || reply.get("capture_error").is_some() {
        result["isError"] = json!(true);
    }
    Ok(result)
}

fn respond(request: &Value) -> Value {
    let result = match request["method"].as_str() {
        Some("initialize") => {
            json!({"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"minecraft-agent","version":env!("CARGO_PKG_VERSION")}})
        }
        Some("tools/list") => {
            let input_script = json!({
                "name":"input_script",
                "description":"Run a keyboard and mouse input script from exactly one of script or path and return at PAUSE, a pause shortcut, END, the input tick limit, or a single-player save transition. See docs/input-script/README.md.",
                "inputSchema":{
                    "type":"object",
                    "properties":{
                        "script":{"type":"string"},
                        "path":{"type":"string"},
                        "include_screenshot":{"type":"boolean","default":true,"description":"Include the stopped frame as a PNG image."},
                        "max_ticks":{
                            "type":"integer","minimum":1,"maximum":i64::MAX,"default":DEFAULT_MAX_TICKS,
                            "description":"Pause after this many input ticks in this call if playback would continue. Pending input is retained."
                        }
                    },
                    "additionalProperties":false
                }
            });
            json!({"tools":[input_script,queue::tool(),hints::tool(),game_state::tool()]})
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
            || !matches!(
                tool,
                Some("input_script" | "cursor_hint" | "inspect_input" | "inspect_game")
            )
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
            let args = request["params"].get("arguments");
            let result = match request["params"]["name"].as_str() {
                Some("inspect_input") => inspect(args, &call),
                Some("inspect_game") => game_state::inspect(args, || connect_game(&call)),
                Some("cursor_hint") => hints::annotate(args.unwrap_or(&Value::Null)),
                _ => play(args.unwrap_or(&Value::Null), &call),
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
    fn lists_inspect_game_as_a_read_only_tool_with_optional_screenshot() {
        let response = respond(&json!({"id":1,"method":"tools/list"}));
        let tool = response["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .find(|tool| tool["name"] == "inspect_game")
            .expect("tools/list must include inspect_game");
        assert_eq!(tool["annotations"]["readOnlyHint"], true);
        assert_eq!(tool["inputSchema"]["additionalProperties"], false);
        let properties = tool["inputSchema"]["properties"].as_object().unwrap();
        assert_eq!(properties.len(), 1);
        assert_eq!(properties["include_screenshot"]["type"], "boolean");
        assert_eq!(properties["include_screenshot"]["default"], false);
        assert!(tool["inputSchema"].get("required").is_none());
    }

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
    fn stopped_script_values_preserve_output_null_and_error_context() {
        let reply = json!({"world":"paused","ticks":2,"pause_reason":"end","return_value_json":"null","stdout":"A\nB\n"});
        let result = stop_result(&reply, false).unwrap();
        let status: Value =
            serde_json::from_str(result["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(status.get("return_value"), Some(&Value::Null));
        assert_eq!(status["stdout"], "A\nB\n");

        let exact = r#"{"$serde_json::private::Number":"ordinary key","n":36893488147419103230}"#;
        let result = stop_result(
            &json!({"world":"paused","ticks":0,"return_value_json":exact}),
            false,
        )
        .unwrap();
        assert!(
            result["content"][0]["text"]
                .as_str()
                .unwrap()
                .contains(exact)
        );

        let reply = json!({"world":"paused","ticks":1,"pause_reason":"error","stdout":"A\n",
                            "script_error":{"block":3,"line":4,"message":"expected a number","call_stack":[]}});
        let result = stop_result(&reply, false).unwrap();
        assert_eq!(result["isError"], true);
        let status: Value =
            serde_json::from_str(result["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(status["script_error"], reply["script_error"]);
        assert_eq!(status["stdout"], "A\n");
        assert!(status.get("return_value").is_none());
    }

    #[test]
    fn max_ticks_is_advertised_and_validated_before_loading_the_script() {
        let reply = respond(&json!({"method":"tools/list"}));
        let schema = &reply["result"]["tools"][0]["inputSchema"]["properties"]["max_ticks"];
        assert_eq!(schema["type"], "integer");
        assert_eq!(schema["default"], 600);
        assert_eq!(schema["minimum"], 1);
        for invalid in [
            json!(0),
            json!(-1),
            json!(0.5),
            json!("1"),
            json!(true),
            Value::Null,
            json!(u64::MAX),
        ] {
            let args = json!({"path":"missing.input","max_ticks":invalid});
            let error = play(&args, &Mutex::default()).unwrap_err().to_string();
            assert!(error.contains("max_ticks"), "{error}");
        }
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
    fn transition_stop_returns_the_destination_screen_and_cleanup() {
        for (direction, screen, world) in [
            ("enter", Value::Null, "paused"),
            ("leave", json!("TitleScreen"), "not_needed"),
        ] {
            let state = json!({"world":world,"ticks":1,"pause_reason":"world_changed",
                "events":[],"screen":screen,"transition":{"direction":direction,
                "status":"completed","input_cleared":true}});
            let result = stop_result(&state, false).unwrap();
            assert_eq!(
                result,
                json!({"content":[{"type":"text","text":state.to_string()}]})
            );
        }
    }

    #[test]
    fn stop_reports_observed_events_with_and_without_a_screenshot() {
        let events = json!([{"event":"BlockInteracted","item":null,"before":{"open":false},
            "after":{"open":true},"listeners":[{"event":"BlockInteracted","block":1,"line":2}]}]);
        let mut reply = json!({"world":"paused","ticks":4,"events":events,"events_omitted":2});
        let mut png = std::io::Cursor::new(Vec::new());
        image::RgbaImage::new(2, 2)
            .write_to(&mut png, image::ImageFormat::Png)
            .unwrap();
        reply["png"] = json!(base64_simd::STANDARD.encode_to_string(png.get_ref()));
        for image in [false, true] {
            let result = stop_result(&reply, image).unwrap();
            let status: Value =
                serde_json::from_str(result["content"][0]["text"].as_str().unwrap()).unwrap();
            assert_eq!(status["events"], events);
            assert_eq!(status["events_omitted"], 2);
        }
    }

    #[test]
    fn extended_events_require_an_advertised_name() {
        for event in ["Death", "BlockInteracted"] {
            let script =
                Script::parse(&format!("BEGIN\nON({event})\nBEGIN\nPAUSE\nEND\nEND")).unwrap();
            let program = script.compile(Default::default());
            let mut hello = json!({"input_events":true});
            assert!(check_game_support(&program, &hello).is_err());
            hello["event_names"] = json!(["CollideX", "CollideZ"]);
            assert!(check_game_support(&program, &hello).is_err());
            hello["event_names"] = json!([event]);
            assert!(check_game_support(&program, &hello).is_ok());
        }
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

    #[test]
    fn values_are_encoded_with_parameters_separate_waits_and_input_actions() {
        let script = Script::parse(concat!(
            "FUNCTION $prepare($name)\nBEGIN\nRETURN $name\nEND\n",
            "BEGIN\nLET $key = $prepare(\"w\")\n",
            "ON(Hurt($event))\nBEGIN\nPrint(\"{{}} {}\", $event.event)\nEND\n",
            "LET $delay = 3\nWait($delay) Key($key) MouseMove(Px(Min(1, 2)), Px(0))\nEND"
        ))
        .unwrap();
        let old_hello = json!({"pause_queue":true,"input_functions":true,"input_events":true,"mouse_input":true});
        let error =
            encode_program(script.compile(Default::default()), &old_hello, 50.).unwrap_err();
        assert!(error.to_string().contains("input_values"), "{error}");
        let mut hello = old_hello;
        hello["input_values"] = json!(true);
        let request = encode_program(script.compile(Default::default()), &hello, 50.).unwrap();
        assert_eq!(request["params"], json!({"prepare":["name"]}));
        assert_eq!(request["runs"][0]["call"], "prepare");
        assert_eq!(
            request["runs"][0]["v"],
            json!({"op":"let","target":["var","key"],"args":[["lit","w"]]})
        );
        assert_eq!(request["runs"][1]["v"], json!({"bind":"event"}));
        assert_eq!(
            request["runs"][1]["body"][0]["v"],
            json!({"op":"print","format":"{{}} {}","args":[["field",["var","event"],"event"]]})
        );
        assert_eq!(request["runs"][3]["ticks"], "1");
        assert_eq!(
            request["runs"][3]["v"],
            json!({"op":"wait","expr":["var","delay"]})
        );
        assert_eq!(
            request["runs"][4]["v"],
            json!({"op":"input","actions":[["Key",["var","key"]],["MouseMove",["px",["Min",["num","1"],["num","2"]]],["px",["num","0"]]]]})
        );
        assert_eq!(
            request["functions"]["prepare"][0]["v"],
            json!({"expr":["var","name"]})
        );
        assert_eq!(request["runs"][4]["line"], 12);
    }

    #[test]
    fn control_flow_wire_preserves_nested_runs_and_only_consumes_intra_chain_blanks() {
        let script = Script::parse(concat!(
            "BEGIN\nFOR $item IN [true]\nBEGIN\nIF ($item)\nBEGIN\n\nCONTINUE\nEND\n",
            "  \n// skip\n\nELIF (false)\nBEGIN\nBREAK\nEND\n\nELSE\nBEGIN\nKey(a)\nEND\n",
            "\n  \nKey(w)\nEND\nWHILE (false)\nBEGIN\nEND\nEND"
        ))
        .unwrap();
        let hello = json!({"pause_queue":true,"input_control_flow":true});
        let request = encode_program(script.compile(Default::default()), &hello, 50.).unwrap();
        let runs = request["runs"].as_array().unwrap();
        assert_eq!(runs.len(), 2);
        assert_eq!(runs[0]["ticks"], "0");
        assert_eq!(runs[0]["source"], "FOR $item IN [true]");
        let flow = &runs[0]["flow"];
        assert_eq!(flow["op"], "for");
        assert_eq!(flow["name"], "item");
        assert_eq!(flow["expr"], json!(["array", ["lit", true]]));
        let body = flow["body"].as_array().unwrap();
        assert_eq!(body.len(), 4);
        assert_eq!(body[0]["source"], "IF ($item)");
        let branches = body[0]["flow"]["branches"].as_array().unwrap();
        assert_eq!(branches.len(), 3);
        assert_eq!(branches[0]["line"], 4);
        assert_eq!(branches[0]["expr"], json!(["var", "item"]));
        assert_eq!(branches[0]["body"][0]["line"], 6);
        assert_eq!(branches[0]["body"][0]["ticks"], "1");
        assert_eq!(branches[0]["body"][1]["flow"], json!({"op":"continue"}));
        assert_eq!(branches[1]["line"], 12);
        assert_eq!(branches[1]["body"][0]["flow"], json!({"op":"break"}));
        assert_eq!(branches[2]["line"], 17);
        assert!(branches[2].get("expr").is_none());
        assert_eq!(
            branches[2]["body"][0]["key_actions"],
            json!({"a":"temporary"})
        );
        assert_eq!(body[1]["line"], 21);
        assert_eq!(body[2]["line"], 22);
        assert_eq!(body[1]["ticks"], "1");
        assert_eq!(body[2]["ticks"], "1");
        assert_eq!(body[3]["source"], "Key(w)");
        assert_eq!(request["keys"], json!({"a":19,"w":23}));
        assert_eq!(
            runs[1]["flow"],
            json!({"op":"while","expr":["lit",false],"body":[]})
        );
    }

    #[test]
    fn control_flow_and_its_expressions_require_the_advertised_capability() {
        for (source, line) in [
            ("BEGIN\nIF (false)\nBEGIN\nEND\nEND", 2),
            ("BEGIN\nLET $n = Len([])\nEND", 2),
            ("BEGIN\nPrint(\"{}\", [0 < 1])\nEND", 2),
            ("BEGIN\nLET $x = [0, 1]\nSET $x[Len([])] = 2\nEND", 3),
            (
                "BEGIN\nLET $x = [0, 1]\nSET $x[Len([$x[0] <= $x[1]])] = 2\nEND",
                3,
            ),
            ("BEGIN\nWait(Len([1])) Key(w)\nEND", 2),
            ("BEGIN\nMouseMove(Px(Len([1])), Px(0))\nEND", 2),
            (
                "FUNCTION $unused\nBEGIN\nON(Hurt)\nBEGIN\nIF (true)\nBEGIN\nEND\nEND\nEND\nBEGIN\nEND",
                5,
            ),
        ] {
            let script = Script::parse(source).unwrap();
            let mut hello = json!({"pause_queue":true,"input_values":true,"input_functions":true,"input_events":true,"mouse_input":true});
            let old = encode_program(script.compile(Default::default()), &hello, 50.).unwrap_err();
            assert!(old.to_string().contains("input_control_flow"), "{old}");
            assert!(old.to_string().contains(&format!("line {line}")), "{old}");
            hello["input_control_flow"] = json!(true);
            assert!(encode_program(script.compile(Default::default()), &hello, 50.).is_ok());
        }
    }

    #[test]
    fn excessively_nested_control_flow_fails_before_compilation() {
        for levels in [31, 3000] {
            let source = format!(
                "BEGIN\n{}Key(w)\n{}END",
                "IF (true)\nBEGIN\n".repeat(levels),
                "END\n".repeat(levels)
            );
            let error = Script::parse(&source).unwrap_err();
            assert_eq!(error.line, 62);
            assert!(error.message.contains("nesting"), "{error}");
        }
    }

    #[test]
    fn deeply_nested_control_flow_encodes_within_the_game_json_depth_limit() {
        let source = format!(
            "FUNCTION $nested\nBEGIN\n{}Print(\"{{}}\", {}0{})\n{}END\nBEGIN\n$nested()\nEND",
            "IF (true)\nBEGIN\n".repeat(30),
            "{k:".repeat(47),
            "}".repeat(47),
            "END\n".repeat(30)
        );
        let script = Script::parse(&source).unwrap();
        let hello = json!({"pause_queue":true,"input_control_flow":true,"input_functions":true,"input_values":true});
        let request = encode_program(script.compile(Default::default()), &hello, 50.).unwrap();
        let mut stack = vec![(&request, 0)];
        while let Some((value, parent_depth)) = stack.pop() {
            let depth = parent_depth + 1;
            match value {
                Value::Array(values) => stack.extend(values.iter().map(|value| (value, depth))),
                Value::Object(fields) => stack.extend(fields.values().map(|value| (value, depth))),
                _ => continue,
            }
            assert!(depth <= 255, "JSON nesting at depth {depth}");
        }
        assert!(
            serde_json::to_vec(&request)
                .unwrap()
                .windows(8)
                .any(|bytes| bytes == b"\"print\"}")
        );
    }

    #[test]
    fn imported_demo_control_flow_is_checked_at_its_original_location() {
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let temp =
            std::env::temp_dir().join(format!("mc-flow-wire-{}-{timestamp}", std::process::id()));
        std::fs::create_dir(&temp).unwrap();
        let library = temp.join("demo.mcinput");
        std::fs::write(
            &library,
            concat!(
                "BEGIN\nIF (false)\nBEGIN\nKey(demo_key)\nON(Hurt)\nBEGIN\n",
                "WHILE (false)\nBEGIN\nMouse(left)\nEND\nEND\nEND\nEND"
            ),
        )
        .unwrap();
        let source = ScriptSource::Inline("IMPORT \"demo.mcinput\"\nBEGIN\nKey(w)\nEND");
        let script = source.load(&temp).unwrap();
        let path = library.canonicalize().unwrap().display().to_string();
        std::fs::remove_dir_all(&temp).unwrap();

        let mut hello = json!({"pause_queue":true,"input_events":true});
        let error = encode_program(script.compile(Default::default()), &hello, 50.).unwrap_err();
        assert!(
            error.to_string().contains(&format!("{path}: line 2")),
            "{error}"
        );
        assert!(error.to_string().contains("input_control_flow"), "{error}");
        hello["input_control_flow"] = json!(true);
        let error = encode_program(script.compile(Default::default()), &hello, 50.).unwrap_err();
        assert!(
            error.to_string().contains(&format!("{path}: line 9")),
            "{error}"
        );
        assert!(error.to_string().contains("keyboard input"), "{error}");
        hello["mouse_input"] = json!(true);
        let program = encode_program(script.compile(Default::default()), &hello, 50.).unwrap();
        assert_eq!(program["runs"].as_array().unwrap().len(), 1);
        assert_eq!(program["runs"][0]["source"], "Key(w)");
        let branch = &program["checks"][0][0]["flow"]["branches"][0]["body"];
        assert_eq!(branch[0]["file"], path);
        assert_eq!(branch[0]["line"], 4);
        assert_eq!(script.key_location("demo_key"), Some((path.as_str(), 4)));
        assert_eq!(program["keys"]["demo_key"], 4);
    }
}
