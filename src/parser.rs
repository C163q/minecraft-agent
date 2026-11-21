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

// Consume one command including nested unit expressions. Whitespace separates
// neighboring commands only after the outer closing parenthesis.
fn next_command(code: &str) -> Result<(&str, &str, &str), String> {
    let (name, arguments) = code
        .split_once('(')
        .ok_or("expected a command followed by '('")?;
    if !name.bytes().all(|b| b.is_ascii_alphabetic()) || name.is_empty() {
        return Err(format!("invalid command name {name:?}"));
    }
    let mut nested = 0;
    for (index, byte) in arguments.bytes().enumerate() {
        match byte {
            b'(' => nested += 1,
            b')' if nested == 0 => {
                let rest = &arguments[index + 1..];
                if !rest.is_empty() && !rest.as_bytes()[0].is_ascii_whitespace() {
                    return Err(format!("expected whitespace after {name}"));
                }
                return Ok((name, &arguments[..index], rest.trim_ascii_start()));
            }
            b')' => nested -= 1,
            _ => (),
        }
    }
    Err(format!("missing closing ')' in {name}"))
}

fn record_action(
    actions: &mut Keys,
    kind: &str,
    name: &str,
    action: KeyAction,
) -> Result<(), String> {
    if let Some(&previous) = actions.get(name) {
        if (previous == KeyAction::Release) != (action == KeyAction::Release) {
            return Err(format!("conflicting actions for {kind} {name:?}"));
        }
        if previous == KeyAction::Press {
            return Ok(());
        }
    }
    actions.insert(name.to_owned(), action);
    Ok(())
}

fn movement(absolute: bool, arguments: &str) -> Result<serde_json::Value, String> {
    let mut values = Vec::new();
    let mut args = Vec::new();
    for argument in arguments.split(',') {
        let (name, number, rest) = next_command(argument.trim())?;
        let unit = match name {
            "Px" => "px",
            "Percentage" => "percentage",
            "Rotate" => "rotate",
            _ => return Err(format!("unknown mouse unit {name:?}")),
        };
        let value = number
            .trim()
            .parse::<f64>()
            .ok()
            .filter(|n| n.is_finite())
            .ok_or_else(|| format!("expected a finite number in {name}, got {number:?}"))?;
        if !rest.is_empty() {
            return Err("expected ',' between mouse arguments".into());
        }
        values.push(value);
        args.push(serde_json::json!({"unit":unit, "value":value}));
    }
    match values.as_slice() {
        [_, _] => (),
        [_, _, speed] if *speed > 0.0 => (),
        [_, _, _] => return Err("mouse speed must be positive".into()),
        [x, y, vx, vy] => {
            if *vx < 0.0 || *vy < 0.0 {
                return Err("mouse axis speeds must be non-negative".into());
            }
            // The distance of an absolute move depends on the cursor at dispatch.
            if !absolute && ((*vx == 0.0 && *x != 0.0) || (*vy == 0.0 && *y != 0.0)) {
                return Err(
                    "non-zero mouse displacement requires a positive speed on that axis".into(),
                );
            }
        }
        _ => return Err("mouse movement expects 2, 3 or 4 arguments".into()),
    }
    Ok(serde_json::json!({"absolute":absolute, "args":args}))
}

#[derive(Default, Debug)]
pub(crate) struct Row {
    pub line: usize,
    pub wait: u64,
    pub keys: Keys,
    pub moves: Vec<serde_json::Value>,
    pub buttons: Keys,
    pub wait_mouse: bool,
    pub clear: bool,
    pub pause: bool,
    pub input_source: String,
}

impl Row {
    pub(crate) fn has_input(&self) -> bool {
        !(self.keys.is_empty() && self.moves.is_empty() && self.buttons.is_empty())
    }

    fn parse(line: usize, mut code: &str) -> Result<Self, String> {
        let mut row = Self {
            line,
            wait: u64::from(code.is_empty()),
            ..Self::default()
        };
        if code == "CLEAR" {
            row.clear = true;
            return Ok(row);
        }
        if let Some(before) = code.strip_suffix("PAUSE")
            && (before.is_empty() || before.ends_with(|c: char| c.is_ascii_whitespace()))
        {
            row.pause = true;
            code = before.trim_ascii_end();
        }
        let mut input_source = code;
        let mut first = true;
        while !code.is_empty() {
            let (name, arg, rest) = next_command(code)?;
            match name {
                "Wait" | "WaitMouseMove" if !first => {
                    return Err(format!("{name} must be the first command on its line"));
                }
                "Wait" => {
                    input_source = rest;
                    row.wait = arg
                        .parse::<u64>()
                        .ok()
                        .filter(|n| *n > 0 && arg.bytes().all(|b| b.is_ascii_digit()))
                        .ok_or_else(|| {
                            format!("expected a positive 64-bit tick count, got {arg:?}")
                        })?;
                }
                "WaitMouseMove" => {
                    input_source = rest;
                    if !arg.trim().is_empty() {
                        return Err("WaitMouseMove expects no arguments".into());
                    }
                    row.wait_mouse = true;
                }
                "MouseMove" | "MouseMoveTo" => {
                    row.moves.push(movement(name == "MouseMoveTo", arg)?);
                }
                "Key" | "KeyPress" | "KeyRelease" | "Mouse" | "MousePress" | "MouseRelease" => {
                    let action = match name {
                        "MousePress" | "KeyPress" => KeyAction::Press,
                        "MouseRelease" | "KeyRelease" => KeyAction::Release,
                        _ => KeyAction::Temporary,
                    };
                    if name.starts_with("Mouse") {
                        if !matches!(arg, "left" | "right" | "middle") {
                            return Err(format!("unknown mouse button {arg:?}"));
                        }
                        record_action(&mut row.buttons, "mouse button", arg, action)?;
                    } else {
                        if arg.is_empty()
                            || !arg.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
                        {
                            return Err(format!("invalid key name {arg:?}"));
                        }
                        record_action(&mut row.keys, "key", arg, action)?;
                    }
                }
                _ => return Err(format!("unknown command {name:?}")),
            }
            code = rest;
            first = false;
        }
        row.input_source = input_source.to_owned();
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
                (Scope::Inside, body) => rows
                    .push(Row::parse(line, body).map_err(|message| ParseError { line, message })?),
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
