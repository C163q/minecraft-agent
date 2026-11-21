//! Present compact pages of one immutable game snapshot, or publish the whole snapshot.
use super::Result;
use serde_json::{Value, json};
use std::{
    collections::VecDeque,
    fs::{self, OpenOptions},
    io::{BufWriter, ErrorKind, Write},
    os::unix::fs::OpenOptionsExt,
    path::Path,
    sync::{
        Arc, LazyLock, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

const PREVIEW: usize = 5;
const INLINE_DEFAULT: usize = 20;
const INLINE_MAX: usize = 100;
const KEEP_SNAPSHOTS: usize = 8;

pub fn tool() -> Value {
    json!({
        "name":"inspect_input",
        "description":"Read script-held keys, mouse buttons, pending moves, pause reason and a short queue preview. Use inline to page through a snapshot or file to create a full JSON snapshot on the MCP host. See docs/input-script/inspect.md.",
        "inputSchema":{"type":"object","additionalProperties":false,"properties":{
            "queue":{"type":"string","enum":["inline","file"]},
            "limit":{"type":"integer","minimum":1,"maximum":INLINE_MAX,"description":"Inline page size (default 20)."},
            "cursor":{"type":"string","description":"Inline continuation token from queue.next_cursor."},
            "path":{"type":"string","description":"New file path on the host, required for file mode."}
        }},
        "annotations":{"readOnlyHint":false,"destructiveHint":false}
    })
}

enum Query<'a> {
    Preview,
    Inline {
        limit: usize,
        cursor: Option<&'a str>,
    },
    File(&'a Path),
}

impl<'a> Query<'a> {
    fn parse(args: Option<&'a Value>) -> Result<Self> {
        let Some(args) = args else {
            return Ok(Self::Preview);
        };
        let args = args.as_object().ok_or("inspect_input expects an object")?;
        if args.is_empty() {
            return Ok(Self::Preview);
        }
        match args.get("queue").and_then(Value::as_str) {
            Some("inline") if args.keys().all(|key| matches!(key.as_str(), "queue" | "limit" | "cursor")) => {
                let limit = match args.get("limit") {
                    None => INLINE_DEFAULT,
                    Some(value) => value
                        .as_u64()
                        .filter(|n| (1..=INLINE_MAX as u64).contains(n))
                        .ok_or("limit must be an integer in [1, 100]")? as usize,
                };
                let cursor = args.get("cursor")
                    .map(|value| value.as_str().ok_or("cursor must be a string"))
                    .transpose()?;
                Ok(Self::Inline { limit, cursor })
            }
            Some("file") if args.len() == 2 => {
                let path = args.get("path")
                    .and_then(Value::as_str)
                    .map(Path::new)
                    .filter(|path| path.file_name().is_some())
                    .ok_or("file mode requires a file path")?;
                // Also reject dangling symlinks. The final hard link enforces this again at publication.
                match fs::symlink_metadata(path) {
                    Ok(_) => return Err(format!("export path already exists: {}", path.display()).into()),
                    Err(e) if e.kind() == ErrorKind::NotFound => (),
                    Err(e) => return Err(e.into()),
                }
                Ok(Self::File(path))
            }
            _ => Err("use {} for preview, queue=inline with optional limit and cursor, or queue=file with path".into()),
        }
    }
}

struct Snapshot {
    state: Value,
    entries: Vec<Value>,
}

static SAVED: Mutex<VecDeque<(String, Arc<Snapshot>)>> = Mutex::new(VecDeque::new());
static SESSION: LazyLock<String> = LazyLock::new(|| {
    let start = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    format!("{:x}-{:x}", std::process::id(), start.as_nanos())
});
static SERIAL: AtomicU64 = AtomicU64::new(1);

fn unique_id() -> String {
    format!("{}-{}", *SESSION, SERIAL.fetch_add(1, Ordering::Relaxed))
}

impl Snapshot {
    fn page(&self, id: &str, offset: usize, limit: usize) -> Value {
        let end = offset.saturating_add(limit).min(self.entries.len());
        let mut page = self.state.clone();
        page["queue"] = json!({
            "total":self.entries.len(), "entries":&self.entries[offset..end],
            "next_cursor":(end < self.entries.len()).then(|| format!("{id}:{end}"))
        });
        page
    }

    fn first_page(self, limit: usize) -> Value {
        let id = unique_id();
        let page = self.page(&id, 0, limit);
        if limit < self.entries.len() {
            let mut saved = SAVED.lock().unwrap();
            if saved.len() == KEEP_SNAPSHOTS {
                saved.pop_front();
            }
            saved.push_back((id, Arc::new(self)));
        }
        page
    }

    fn export(self, path: &Path) -> Result<Value> {
        let path = std::path::absolute(path)?;
        let total = self.entries.len();
        let summary = json!({
            "world": self.state["world"], "pause_reason": self.state["pause_reason"],
            "held": self.state["held"], "max_speed_px_per_tick": self.state["max_speed_px_per_tick"],
            "move_count": self.state["moves"].as_array().ok_or("missing movement snapshot")?.len(),
            "queue": {"total":total, "path":path}
        });
        let mut state = self.state;
        state["queue"] = json!({"total":total,"next_cursor":null});
        state["queue"]["entries"] = Value::Array(self.entries);

        // A same-directory link publishes the completed file atomically and never replaces another file.
        let temp = path.with_file_name(format!(".minecraft-agent-{}.tmp", unique_id()));
        let mut file = BufWriter::new(
            OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&temp)?,
        );
        let result = (|| -> Result<()> {
            serde_json::to_writer_pretty(&mut file, &state)?;
            file.write_all(b"\n")?;
            file.flush()?;
            fs::hard_link(&temp, &path)?;
            Ok(())
        })();
        drop(file);
        let cleanup = fs::remove_file(&temp);
        result?;
        cleanup?;
        Ok(summary)
    }
}

fn saved_page(cursor: &str, limit: usize) -> Result<Value> {
    let (id, offset) = cursor.split_once(':').ok_or("invalid queue cursor")?;
    let offset = offset
        .parse::<usize>()
        .map_err(|_| "invalid queue cursor offset")?;
    let saved = SAVED.lock().unwrap();
    let snapshot = saved
        .iter()
        .find(|(key, _)| key == id)
        .map(|(_, s)| Arc::clone(s))
        .ok_or("queue cursor expired or unknown to this MCP session")?;
    drop(saved);
    if offset == 0 || offset >= snapshot.entries.len() {
        return Err("invalid queue cursor offset".into());
    }
    Ok(snapshot.page(id, offset, limit))
}

pub fn inspect(args: Option<&Value>, fetch: impl FnOnce() -> Result<Value>) -> Result<Value> {
    let query = Query::parse(args)?;
    if let Query::Inline {
        limit,
        cursor: Some(cursor),
    } = query
    {
        return saved_page(cursor, limit);
    }
    let mut state = fetch()?;
    let fields = state
        .as_object_mut()
        .ok_or("expected a game state object")?;
    let Some(queue) = fields.remove("queue_entries") else {
        return match query {
            Query::Preview => Ok(state),
            _ => Err(
                "installed Fabric mod lacks queue inspection; update the mod and restart Minecraft"
                    .into(),
            ),
        };
    };
    let Value::Array(entries) = queue else {
        return Err("expected an array of queue entries".into());
    };
    fields.entry("pause_reason").or_insert(Value::Null);
    let snapshot = Snapshot { state, entries };
    match query {
        Query::Preview => Ok(snapshot.first_page(PREVIEW)),
        Query::Inline { limit, .. } => Ok(snapshot.first_page(limit)),
        Query::File(path) => snapshot.export(path),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn live() -> Result<Value> {
        Ok(
            json!({"world":"paused","pause_reason":"pause","held":{"keys":{},"buttons":{}},
            "moves":[],"max_speed_px_per_tick":50,"queue_entries":
            (1..=7).map(|i| json!({"block":2,"line":i,"source":"Wait(12345678901234567890)",
                "remaining_ticks":"12345678901234567890"})).collect::<Vec<_>>() }),
        )
    }

    #[test]
    fn preview_cursor_reads_the_same_snapshot_after_newer_inspections() {
        let preview = inspect(None, live).unwrap();
        assert_eq!(preview["queue"]["total"], 7);
        assert_eq!(preview["queue"]["entries"].as_array().unwrap().len(), 5);
        let cursor = &preview["queue"]["next_cursor"];
        assert!(cursor.is_string());
        let fresh = inspect(Some(&json!({"queue":"inline","limit":1})), || {
            Ok(json!({"world":"unpaused","queue_entries":[]}))
        })
        .unwrap();
        assert_eq!(fresh["queue"]["total"], 0);
        assert!(fresh.as_object().unwrap().contains_key("pause_reason"));
        let args = json!({"queue":"inline","limit":1,"cursor":cursor});
        let page = inspect(Some(&args), || panic!("must use saved snapshot")).unwrap();
        assert_eq!(page["queue"]["total"], 7);
        assert_eq!(page["queue"]["entries"][0]["line"], 6);
        assert_eq!(page["world"], "paused");
        let end = inspect(
            Some(&json!({"queue":"inline","cursor":page["queue"]["next_cursor"]})),
            || panic!("must use saved snapshot"),
        )
        .unwrap();
        assert_eq!(end["queue"]["entries"][0]["line"], 7);
        assert!(end["queue"]["next_cursor"].is_null());
        assert_eq!(inspect(Some(&args), || panic!()).unwrap(), page);
    }

    #[test]
    fn file_exports_all_entries_and_refuses_overwrite() {
        let path =
            std::env::temp_dir().join(format!("mcagent-queue-test-{}.json", std::process::id()));
        let args = json!({"queue":"file","path":path});
        let reply = inspect(Some(&args), || {
            let mut state = live()?;
            state["moves"] =
                json!([{"remaining_px":{"x":110,"y":0},"requested_speed_px_per_tick":80}]);
            Ok(state)
        })
        .unwrap();
        let exported: Value = serde_json::from_reader(std::fs::File::open(&path).unwrap()).unwrap();
        assert_eq!(exported["queue"]["entries"][6]["line"], 7);
        assert_eq!(
            exported["queue"]["entries"][0]["remaining_ticks"],
            "12345678901234567890"
        );
        assert_eq!(reply["queue"], json!({"total":7,"path":path}));
        assert!(inspect(Some(&args), || Ok(json!({"queue_entries":[]}))).is_err());
        let again: Value = serde_json::from_reader(std::fs::File::open(&path).unwrap()).unwrap();
        std::fs::remove_file(path).unwrap();
        assert_eq!(again, exported);
        assert_eq!(reply["move_count"], 1);
        assert!(reply.get("moves").is_none());
        assert_eq!(exported["moves"][0]["remaining_px"]["x"], 110);
    }

    #[test]
    fn bad_queue_arguments_fail_before_fetching_and_old_mod_supports_default() {
        for args in [
            json!(null),
            json!({"limit":2}),
            json!({"queue":"file"}),
            json!({"queue":"file","path":""}),
            json!({"queue":"inline","limit":0}),
            json!({"queue":"inline","limit":101}),
            json!({"queue":"inline","limit":"2"}),
            json!({"queue":"inline","cursor":"made-up"}),
            json!({"queue":"inline","path":"extra"}),
            json!({"queue":"file","path":"x","limit":1}),
            json!({"queue":"other"}),
            json!({"other":true}),
        ] {
            assert!(
                inspect(Some(&args), || panic!("fetched for {args}")).is_err(),
                "{args}"
            );
        }
        let legacy = json!({"world":"paused","held":{"keys":{},"buttons":{}},"moves":[],"max_speed_px_per_tick":50});
        assert_eq!(
            inspect(Some(&json!({})), || Ok(legacy.clone())).unwrap(),
            legacy
        );
        assert!(inspect(Some(&json!({"queue":"inline"})), || Ok(legacy)).is_err());
    }
}
