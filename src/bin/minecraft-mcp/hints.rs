//! Mark immutable PNG frames from the current MCP session, or an external PNG.
use image::{ImageFormat, ImageReader, Rgba};
use serde_json::{Value, json};
use std::{
    collections::VecDeque,
    fs::File,
    io::{BufReader, Cursor},
    sync::{Arc, LazyLock, Mutex},
    time::{SystemTime, UNIX_EPOCH},
};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;

struct Frame {
    png: Vec<u8>,
    camera: Value,
}

const KEEP_FRAMES: usize = 8;
const CACHE_BYTES: usize = 64 * 1024 * 1024;

struct Cache {
    session: String,
    serial: u64,
    frames: VecDeque<(String, Arc<Frame>)>,
    bytes: usize,
}

static CACHE: LazyLock<Mutex<Cache>> = LazyLock::new(|| {
    let start = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    Mutex::new(Cache {
        session: format!("{:x}-{:x}", std::process::id(), start.as_nanos()),
        serial: 0,
        frames: VecDeque::new(),
        bytes: 0,
    })
});

pub fn remember(reply: &Value) -> Result<(String, u32, u32)> {
    let data = reply["png"].as_str().ok_or("missing END image")?;
    let frame = Frame {
        png: base64_simd::STANDARD.decode_to_vec(data)?,
        camera: reply["camera"].clone(),
    };
    let (width, height) =
        ImageReader::with_format(Cursor::new(&frame.png), ImageFormat::Png).into_dimensions()?;
    let mut cache = CACHE.lock().unwrap();
    while cache.frames.len() >= KEEP_FRAMES || cache.bytes + frame.png.len() > CACHE_BYTES {
        let Some((_, oldest)) = cache.frames.pop_front() else {
            break;
        };
        cache.bytes -= oldest.png.len();
    }
    cache.serial += 1;
    let id = format!("{}-{}", cache.session, cache.serial);
    cache.bytes += frame.png.len();
    cache.frames.push_back((id.clone(), Arc::new(frame)));
    Ok((id, width, height))
}

pub fn tool() -> Value {
    let coordinate = json!({"type":["integer","string"],
        "description":"Integer PNG pixel or percentage string from the left or top, e.g. 25%"});
    json!({
        "name":"cursor_hint",
        "description":"Mark a point using exactly one of screenshot_id or path to an external PNG. Returns optional first-person rotation. See docs/input-script/README.md.",
        "inputSchema":{
            "type":"object",
            "properties":{
                "screenshot_id":{"type":"string","description":"ID from input_script"},
                "path":{"type":"string","description":"Path to a PNG on the MCP host"},
                "x":coordinate, "y":coordinate,
                "include_rotation":{"type":"boolean","default":true}
            },
            "required":["x","y"],
            "additionalProperties":false
        }
    })
}

fn pixel(value: &Value, extent: u32) -> Result<u32> {
    if let Some(px) = value.as_u64() {
        if px < u64::from(extent) {
            return Ok(px as u32);
        }
    } else if let Some(pct) = value.as_str().and_then(|s| s.strip_suffix('%')) {
        let pct: f64 = pct.parse()?;
        if (0.0..=100.0).contains(&pct) {
            return Ok(((f64::from(extent) * pct / 100.0).floor() as u32).min(extent - 1));
        }
    }
    Err(format!("expected a pixel in [0, {extent}) or percentage in [0%, 100%]").into())
}

fn rotation(camera: &Value, x: u32, y: u32, width: u32, height: u32) -> Option<Value> {
    let basis = camera["rayBasis"].as_array()?;
    let nx = 2.0 * f64::from(x) / f64::from(width) - 1.0;
    let ny = 1.0 - 2.0 * f64::from(y) / f64::from(height);
    let axis = |i: usize| -> Option<f64> {
        Some(
            basis.get(i)?.as_f64()? * nx
                + basis.get(3 + i)?.as_f64()? * ny
                + basis.get(6 + i)?.as_f64()?,
        )
    };
    let (dx, dy, dz) = (axis(0)?, axis(1)?, axis(2)?);
    let yaw = camera["yaw"].as_f64()?;
    let pitch = camera["pitch"].as_f64()?;
    let horizontal = dx.hypot(dz);
    let dyaw = if horizontal <= 1e-7 * dy.abs() {
        0.0
    } else {
        ((-dx).atan2(dz).to_degrees() - yaw + 180.0).rem_euclid(360.0) - 180.0
    };
    let dpitch = (-dy).atan2(horizontal).to_degrees() - pitch;
    Some(json!({"yaw_delta_deg":dyaw,"pitch_delta_deg":dpitch}))
}

pub fn annotate(args: &Value) -> Result<Value> {
    let args = args.as_object().ok_or("expected cursor_hint arguments")?;
    if args.keys().any(|k| {
        !matches!(
            k.as_str(),
            "screenshot_id" | "path" | "x" | "y" | "include_rotation"
        )
    }) {
        return Err("unknown cursor_hint argument".into());
    }
    let include = match args.get("include_rotation") {
        None => true,
        Some(v) => v.as_bool().ok_or("include_rotation must be boolean")?,
    };
    let (mut image, camera) = match (args.get("screenshot_id"), args.get("path")) {
        (Some(id), None) => {
            let id = id.as_str().ok_or("screenshot_id must be a string")?;
            let frame = CACHE
                .lock()
                .unwrap()
                .frames
                .iter()
                .find(|(key, _)| key == id)
                .map(|(_, frame)| Arc::clone(frame))
                .ok_or("screenshot_id expired or unknown to this MCP session")?;
            (
                ImageReader::with_format(Cursor::new(&frame.png), ImageFormat::Png)
                    .decode()?
                    .into_rgba8(),
                frame.camera.clone(),
            )
        }
        (None, Some(path)) => {
            let file = File::open(path.as_str().ok_or("path must be a PNG file path")?)?;
            (
                ImageReader::with_format(BufReader::new(file), ImageFormat::Png)
                    .decode()?
                    .into_rgba8(),
                Value::Null,
            )
        }
        _ => return Err("supply exactly one of screenshot_id or path".into()),
    };
    let (w, h) = image.dimensions();
    let x = pixel(args.get("x").ok_or("expected x")?, w)?;
    let y = pixel(args.get("y").ok_or("expected y")?, h)?;
    for py in y.saturating_sub(10)..=y.saturating_add(10).min(h - 1) {
        for px in x.saturating_sub(10)..=x.saturating_add(10).min(w - 1) {
            let radius2 = x.abs_diff(px).pow(2) + y.abs_diff(py).pow(2);
            if radius2 <= 4 || (64..=100).contains(&radius2) {
                image.put_pixel(px, py, Rgba([255, 0, 0, 255]));
            }
        }
    }
    let mut status = json!({"x":x,"y":y,"width":w,"height":h});
    if include {
        status["rotation"] = rotation(&camera, x, y, w, h).unwrap_or(Value::Null);
        if status["rotation"].is_null() {
            status["rotation_unavailable"] = json!("No first-person camera data for this image");
        }
    }
    let mut png = Cursor::new(Vec::new());
    image.write_to(&mut png, ImageFormat::Png)?;
    let data = base64_simd::STANDARD.encode_to_string(png.into_inner());
    Ok(json!({"content":[{"type":"text","text":status.to_string()},
        {"type":"image","mimeType":"image/png","data":data}]}))
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::RgbaImage;

    fn small_frame() -> Value {
        let image = RgbaImage::from_pixel(64, 48, Rgba([1, 2, 3, 255]));
        let mut png = Cursor::new(Vec::new());
        image.write_to(&mut png, ImageFormat::Png).unwrap();
        // Camera looks south, yaw=0 and pitch=0. At right edge dx/dz=-1.
        json!({"png":base64_simd::STANDARD.encode_to_string(png.into_inner()),
               "camera":{"yaw":0,"pitch":0,"rayBasis":[-1,0,0, 0,1,0, 0,0,1]}})
    }

    #[test]
    fn historical_frame_percent_hint_keeps_picture_and_camera_together() {
        let (id, width, height) = remember(&small_frame()).unwrap();
        assert_eq!((width, height), (64, 48));
        let mut second = small_frame();
        second["camera"] = Value::Null;
        let (gui_id, _, _) = remember(&second).unwrap();
        let reply = annotate(&json!({"screenshot_id":id,"x":"75%","y":"50%"})).unwrap();
        let status: Value =
            serde_json::from_str(reply["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(status["x"], 48);
        assert_eq!(status["y"], 24);
        assert!((status["rotation"]["yaw_delta_deg"].as_f64().unwrap() - 26.56505).abs() < 1e-4);
        assert_eq!(status["rotation"]["pitch_delta_deg"], 0.0);
        let repeat =
            annotate(&json!({"screenshot_id":id,"x":"0%","y":"100%","include_rotation":false}))
                .unwrap();
        let status: Value =
            serde_json::from_str(repeat["content"][0]["text"].as_str().unwrap()).unwrap();
        assert!(status.get("rotation").is_none());
        assert_eq!(status["y"], 47);
        let bytes = base64_simd::STANDARD
            .decode_to_vec(repeat["content"][1]["data"].as_str().unwrap())
            .unwrap();
        let rgb = image::load_from_memory(&bytes).unwrap().into_rgba8();
        assert_eq!(rgb.get_pixel(0, 47).0, [255, 0, 0, 255]);
        assert_eq!(rgb.get_pixel(48, 24).0, [1, 2, 3, 255]); // Original frame has no previous dot.
        let gui = annotate(&json!({"screenshot_id":gui_id,"x":1,"y":1})).unwrap();
        let status: Value =
            serde_json::from_str(gui["content"][0]["text"].as_str().unwrap()).unwrap();
        assert!(status["rotation"].is_null() && status["rotation_unavailable"].is_string());
        for bad in [json!(64), json!(-1), json!("110%"), json!("NaN%")] {
            assert!(annotate(&json!({"screenshot_id":id,"x":bad,"y":1})).is_err());
        }
        assert!(annotate(&json!({"screenshot_id":"unknown","x":0,"y":0})).is_err());
    }

    #[test]
    fn upward_ray_decreases_pitch_and_horizon_wraps_yaw() {
        // yaw=180 looks north. A point half-way up and right points east/up/north.
        let camera = json!({"yaw":180,"pitch":0,"rayBasis":[1,0,0,0,1,0,0,0,-1]});
        let value = rotation(&camera, 75, 25, 100, 100).unwrap();
        assert!((value["yaw_delta_deg"].as_f64().unwrap() - 26.56505).abs() < 1e-4);
        assert!((value["pitch_delta_deg"].as_f64().unwrap() + 24.09484).abs() < 1e-4);
    }
    #[test]
    fn horizontal_screen_offset_while_looking_down_changes_both_angles() {
        let c = std::f64::consts::FRAC_1_SQRT_2;
        // East/right is -X, the camera points equally down and south.
        let camera = json!({"yaw":0,"pitch":45,"rayBasis":[-1,0,0, 0,c,c, 0,-c,c]});
        let value = rotation(&camera, 75, 50, 100, 100).unwrap();
        assert!((value["yaw_delta_deg"].as_f64().unwrap() - 35.26439).abs() < 1e-4);
        assert!((value["pitch_delta_deg"].as_f64().unwrap() + 5.76848).abs() < 1e-4);
    }
}
