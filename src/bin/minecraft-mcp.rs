//! Local stdio MCP adapter. Each submission gets a fresh, serialized game session snapshot.
#[path = "minecraft-mcp/hints.rs"]
mod hints;

use minecraft_input::{DispatchProgram, ScriptSource};
use serde_json::{Value, json};
use std::{
    collections::BTreeSet,
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

fn check_game_support(program: &DispatchProgram, hello: &Value) -> Result<()> {
    let needs_mouse = program
        .runs
        .iter()
        .any(|r| r.wait_mouse || r.clear || !r.moves.is_empty() || !r.buttons.is_empty());
    if needs_mouse && hello["mouse_input"] != true {
        return Err("installed Fabric mod only supports keyboard input; update the mod and restart Minecraft".into());
    }
    Ok(())
}

fn play(args: &Value, call: &Mutex<Call>) -> Result<Value> {
    let args = args.as_object().ok_or("expected script or path")?;
    if args.len() != 1 {
        return Err("supply exactly one of script or path".into());
    }
    let source = match (
        args.get("script").and_then(Value::as_str),
        args.get("path").and_then(Value::as_str),
    ) {
        (Some(text), None) => ScriptSource::Inline(text),
        (None, Some(file)) => ScriptSource::Path(Path::new(file)),
        _ => return Err("expected a string script or path".into()),
    };
    let script = source.load(&env::current_dir()?)?;
    let max_speed = max_mouse_speed()?;
    let path = env::var_os("MINECRAFT_AGENT_SOCKET")
        .ok_or("set MINECRAFT_AGENT_SOCKET to the game socket path")?;
    let mut socket = UnixStream::connect(path)?;
    {
        let mut state = call.lock().unwrap();
        if state.cancelled {
            return Err("request cancelled".into());
        }
        state.socket = Some(socket.try_clone()?);
    }
    let mut reader = BufReader::new(socket.try_clone()?);
    let hello = receive(&mut reader)?;
    let persistent: BTreeSet<String> = serde_json::from_value(hello["persistent"].clone())?;
    let program = script.compile(persistent);
    check_game_support(&program, &hello)?;
    let runs: Vec<_> = program
        .runs
        .into_iter()
        .map(|r| {
            json!({
                "line":r.line, "ticks":r.ticks.to_string(), "held":r.held,
                "moves":r.moves, "buttons":r.buttons, "wait_mouse":r.wait_mouse, "clear":r.clear
            })
        })
        .collect();
    serde_json::to_writer(
        &mut socket,
        &json!({"keys":program.keys,"runs":runs,"persistent":program.persistent,"max_speed":max_speed}),
    )?;
    socket.write_all(b"\n")?;
    let reply = receive(&mut reader)?;
    let data = reply["png"].as_str().ok_or("missing END image")?;
    let (screenshot_id, width, height) = hints::remember(&reply)?;
    let status = json!({
        "ticks": reply["ticks"], "world": reply["world"], "width": width, "height": height,
        "screenshot_id": screenshot_id
    });
    Ok(
        json!({"content":[{"type":"text","text":status.to_string()},{"type":"image","mimeType":"image/png","data":data}]}),
    )
}

fn respond(request: &Value) -> Value {
    let result = match request["method"].as_str() {
        Some("initialize") => {
            json!({"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"minecraft-agent","version":env!("CARGO_PKG_VERSION")}})
        }
        Some("tools/list") => {
            json!({"tools":[{"name":"input_script","description":"Run a keyboard and mouse input script from exactly one of script or path and observe the END frame. See docs/input-script/README.md.","inputSchema":{"type":"object","properties":{"script":{"type":"string"},"path":{"type":"string"}},"additionalProperties":false}},hints::tool()]})
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
        if method != Some("tools/call") || !matches!(tool, Some("input_script" | "cursor_hint")) {
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
    use minecraft_input::Script;

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
}
