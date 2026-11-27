//! Report one client frame's game state and optional image.
use super::{Result, hints, receive};
use serde_json::{Value, json};
use std::io::{BufReader, Read, Write};

pub fn tool() -> Value {
    json!({
        "name":"inspect_game",
        "description":"Read the current screen, local player, and vanilla crosshair target. Optionally include a PNG from the same frame.",
        "inputSchema":{
            "type":"object",
            "properties":{
                "include_screenshot":{"type":"boolean","default":false,"description":"Include the sampled frame as a PNG image."}
            },
            "additionalProperties":false
        },
        "annotations":{"readOnlyHint":true}
    })
}

fn screenshot_requested(args: Option<&Value>) -> Result<bool> {
    let Some(args) = args else {
        return Ok(false);
    };
    let args = args.as_object().ok_or("inspect_game expects an object")?;
    if args.keys().any(|key| key != "include_screenshot") {
        return Err("unknown inspect_game argument".into());
    }
    match args.get("include_screenshot") {
        Some(value) => value
            .as_bool()
            .ok_or_else(|| "include_screenshot must be a boolean".into()),
        None => Ok(false),
    }
}

pub fn inspect<T: Read + Write>(
    args: Option<&Value>,
    connect: impl FnOnce() -> Result<(BufReader<T>, Value)>,
) -> Result<Value> {
    let include_screenshot = screenshot_requested(args)?;
    let (mut reader, hello) = connect()?;
    if hello["inspect_game"] != true {
        return Err(
            "installed Fabric mod lacks inspect_game; update the mod and restart Minecraft".into(),
        );
    }
    let request = json!({"op":"inspect_game","include_screenshot":include_screenshot});
    serde_json::to_writer(reader.get_mut(), &request)?;
    reader.get_mut().write_all(b"\n")?;
    let mut reply = receive(&mut reader)?;
    let mut state = reply
        .get_mut("state")
        .filter(|state| state.is_object())
        .map(Value::take)
        .ok_or("game did not return a state object")?;
    let image = if include_screenshot {
        let data = reply["png"].as_str().ok_or("missing inspect_game image")?;
        Some(hints::image_content(data, &reply["camera"], &mut state)?)
    } else {
        None
    };
    let mut content = vec![json!({"type":"text","text":state.to_string()})];
    content.extend(image);
    Ok(json!({"structuredContent":state,"content":content}))
}

#[cfg(test)]
#[path = "game_state_tests.rs"]
mod tests;
