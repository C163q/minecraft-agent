use super::{inspect as inspect_game, *};
use crate::hints;
use image::{ImageFormat, Rgba, RgbaImage};
use std::io::{self, Cursor};

struct GameReply<'a> {
    reply: Cursor<Vec<u8>>,
    request: &'a mut Vec<u8>,
}

impl Read for GameReply<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.reply.read(buf)
    }
}

impl Write for GameReply<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.request.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn session<'a>(request: &'a mut Vec<u8>, reply: &Value) -> BufReader<GameReply<'a>> {
    BufReader::new(GameReply {
        reply: Cursor::new(format!("{reply}\n").into_bytes()),
        request,
    })
}

fn state() -> Value {
    json!({
        "world":"paused",
        "screen":null,
        "player":{
            "dimension":"minecraft:overworld",
            "position":{"x":7.25,"y":64.0,"z":-3.5},
            "rotation":{"yaw":0.0,"pitch":0.0},
            "main_hand":{"id":"minecraft:diamond_sword","count":1},
            "off_hand":null,
            "health":{"current":18.0,"max":20.0,"absorption":4.0},
            "food":19,
            "air":{"current":297,"max":300}
        },
        "target":{"kind":"block","id":"minecraft:short_grass","position":{"x":8,"y":64,"z":-2},"face":"west"}
    })
}

fn text_status(result: &Value) -> Value {
    let block = &result["content"][0];
    assert_eq!(block["type"], "text");
    serde_json::from_str(block["text"].as_str().unwrap()).unwrap()
}

#[test]
fn inspect_game_defaults_to_state_without_screenshot() {
    let state = state();
    let reply = json!({"state":state,"png":"ignored when screenshot is disabled"});
    for args in [
        None,
        Some(json!({})),
        Some(json!({"include_screenshot":false})),
    ] {
        let mut request = Vec::new();
        let result = inspect_game(args.as_ref(), || {
            Ok((session(&mut request, &reply), json!({"inspect_game":true})))
        })
        .unwrap();
        assert_eq!(
            serde_json::from_slice::<Value>(&request).unwrap(),
            json!({"op":"inspect_game","include_screenshot":false})
        );
        assert_eq!(request.last(), Some(&b'\n'));
        assert_eq!(result["structuredContent"], state);
        assert_eq!(text_status(&result), state);
        assert_eq!(result["content"].as_array().unwrap().len(), 1);
    }
}

#[test]
fn inspect_game_caches_screenshot_and_camera_for_cursor_hint() {
    let mut png = Cursor::new(Vec::new());
    RgbaImage::from_pixel(4, 2, Rgba([1, 2, 3, 255]))
        .write_to(&mut png, ImageFormat::Png)
        .unwrap();
    let png = base64_simd::STANDARD.encode_to_string(png.into_inner());
    let state = state();
    let reply = json!({
        "state":state,"png":png,
        "camera":{"yaw":0,"pitch":0,"rayBasis":[-1,0,0,0,1,0,0,0,1]}
    });
    let mut request = Vec::new();
    let result = inspect_game(Some(&json!({"include_screenshot":true})), || {
        Ok((session(&mut request, &reply), json!({"inspect_game":true})))
    })
    .unwrap();
    assert_eq!(
        serde_json::from_slice::<Value>(&request).unwrap(),
        json!({"op":"inspect_game","include_screenshot":true})
    );

    let status = &result["structuredContent"];
    assert_eq!(&text_status(&result), status);
    let id = status["screenshot_id"].as_str().unwrap();
    let mut expected = state;
    expected["screenshot_id"] = json!(id);
    expected["width"] = json!(4);
    expected["height"] = json!(2);
    assert_eq!(status, &expected);
    assert_eq!(result["content"].as_array().unwrap().len(), 2);
    assert_eq!(
        result["content"][1],
        json!({"type":"image","mimeType":"image/png","data":png})
    );

    let marked = hints::annotate(&json!({"screenshot_id":id,"x":3,"y":1})).unwrap();
    let marked_status = text_status(&marked);
    assert_eq!(marked_status["width"], 4);
    assert_eq!(marked_status["height"], 2);
    let yaw = marked_status["rotation"]["yaw_delta_deg"].as_f64().unwrap();
    assert!((yaw - 26.56505).abs() < 1e-4);
}

#[test]
fn inspect_game_rejects_invalid_arguments_before_connecting() {
    for (args, message) in [
        (json!(null), "expects an object"),
        (json!([]), "expects an object"),
        (json!({"screenshot":true}), "unknown"),
        (json!({"include_screenshot":null}), "must be a boolean"),
        (json!({"include_screenshot":"true"}), "must be a boolean"),
        (json!({"include_screenshot":1}), "must be a boolean"),
    ] {
        let error = inspect_game::<GameReply<'_>>(Some(&args), || {
            panic!("invalid arguments must not connect to Minecraft")
        })
        .unwrap_err();
        assert!(error.to_string().contains(message), "{error}");
    }
}

#[test]
fn inspect_game_requires_advertised_capability_before_sending() {
    for hello in [
        json!({"inspect_input":true,"pause_queue":true}),
        json!({"inspect_game":false}),
        json!({"inspect_game":"true"}),
    ] {
        let mut request = Vec::new();
        let error = inspect_game(None, || Ok((session(&mut request, &json!({})), hello)))
            .unwrap_err()
            .to_string();
        assert!(error.contains("inspect_game"), "{error}");
        assert!(error.contains("update the mod"), "{error}");
        assert!(error.contains("restart Minecraft"), "{error}");
        assert!(request.is_empty());
    }
}

#[test]
fn inspect_game_requires_a_state_object_and_the_requested_png() {
    for (reply, message) in [
        (json!([]), "state object"),
        (json!("unexpected reply"), "state object"),
        (json!({"state":[]}), "state object"),
        (json!({"state":state()}), "missing inspect_game image"),
    ] {
        let mut request = Vec::new();
        let error = inspect_game(Some(&json!({"include_screenshot":true})), || {
            Ok((session(&mut request, &reply), json!({"inspect_game":true})))
        })
        .unwrap_err();
        assert!(error.to_string().contains(message), "{error}");
    }
}
