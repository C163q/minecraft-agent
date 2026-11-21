use std::{collections::BTreeMap, error::Error, fmt, fs, io, path::Path};

/// Select exactly one source. Relative paths are resolved against the caller's base.
pub enum ScriptSource<'a> {
    Inline(&'a str),
    Path(&'a Path),
}

impl ScriptSource<'_> {
    pub fn load(self, base: &Path) -> io::Result<Script> {
        let parse = |text: &str| {
            Script::parse(text).map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
        };
        match self {
            Self::Inline(text) => parse(text),
            Self::Path(path) => parse(&fs::read_to_string(base.join(path))?),
        }
    }
}

#[derive(Debug)]
pub struct ParseError {
    pub line: usize,
    pub message: String,
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "line {}: {}", self.line, self.message)
    }
}
impl Error for ParseError {}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub(crate) enum KeyAction {
    Temporary,
    Press,
    Release,
}

pub(crate) type Keys = BTreeMap<String, KeyAction>;

#[derive(Debug)]
pub(crate) struct Row {
    pub line: usize,
    pub wait: u64,
    pub keys: Keys,
}

impl Row {
    fn parse(line: usize, code: &str) -> Result<Self, ParseError> {
        let mut row = Self {
            line,
            wait: u64::from(code.is_empty()),
            keys: Keys::new(),
        };
        let error = |message: String| ParseError { line, message };
        for (index, token) in code.split_ascii_whitespace().enumerate() {
            let (name, arg) = token
                .strip_suffix(')')
                .and_then(|s| s.split_once('('))
                .ok_or_else(|| {
                    error(format!(
                        "expected a command with one argument, got {token:?}"
                    ))
                })?;
            if name == "Wait" {
                if index != 0 {
                    return Err(error("Wait must be the first command on its line".into()));
                }
                row.wait = arg
                    .parse::<u64>()
                    .ok()
                    .filter(|n| *n > 0 && arg.bytes().all(|b| b.is_ascii_digit()))
                    .ok_or_else(|| {
                        error(format!(
                            "expected a positive 64-bit tick count, got {arg:?}"
                        ))
                    })?;
                continue;
            }
            let action = match name {
                "Key" => KeyAction::Temporary,
                "KeyPress" => KeyAction::Press,
                "KeyRelease" => KeyAction::Release,
                _ => return Err(error(format!("unknown command {name:?}"))),
            };
            if arg.is_empty() || !arg.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_') {
                return Err(error(format!("invalid key name {arg:?}")));
            }
            let previous = row.keys.get(arg).copied();
            if let Some(old) = previous {
                if (old == KeyAction::Release) != (action == KeyAction::Release) {
                    return Err(error(format!("conflicting actions for key {arg:?}")));
                }
                // An explicit press also satisfies a temporary hold on this tick.
                if old == KeyAction::Press {
                    continue;
                }
            }
            row.keys.insert(arg.to_owned(), action);
        }
        Ok(row)
    }
}

#[derive(Debug)]
pub struct Script {
    pub(crate) rows: Vec<Row>,
}

impl Script {
    /// Validate the entire block before making any input available to the caller.
    pub fn parse(text: &str) -> Result<Self, ParseError> {
        enum Scope {
            Before,
            Inside,
            After,
        }
        let mut scope = Scope::Before;
        let mut rows = Vec::new();
        let mut eof = 1;
        for (index, raw) in text.lines().enumerate() {
            let line = index + 1;
            eof = line + 1;
            if raw.starts_with("//") {
                continue;
            }
            let code = raw
                .split_once("//")
                .map_or(raw, |(prefix, _)| prefix)
                .trim();
            match (&scope, code) {
                (Scope::Before, "BEGIN") => scope = Scope::Inside,
                (Scope::Inside, "END") => scope = Scope::After,
                (Scope::Inside, body) => rows.push(Row::parse(line, body)?),
                (_, "") => (),
                (Scope::Before, _) => {
                    return Err(ParseError {
                        line,
                        message: "expected BEGIN".into(),
                    });
                }
                (Scope::After, _) => {
                    return Err(ParseError {
                        line,
                        message: "unexpected input after END".into(),
                    });
                }
            }
        }
        match scope {
            Scope::After => Ok(Self { rows }),
            Scope::Before => Err(ParseError {
                line: eof,
                message: "missing BEGIN".into(),
            }),
            Scope::Inside => Err(ParseError {
                line: eof,
                message: "missing END".into(),
            }),
        }
    }
}
