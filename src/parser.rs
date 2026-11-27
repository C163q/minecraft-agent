use std::{collections::BTreeMap, error::Error, fmt, io, path::Path};

mod imports;

/// Select exactly one source. Relative paths are resolved against the caller's base.
pub enum ScriptSource<'a> {
    Inline(&'a str),
    Path(&'a Path),
}

impl ScriptSource<'_> {
    pub fn load(self, base: &Path) -> io::Result<Script> {
        imports::load(self, base).map_err(Into::into)
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

/// Zero-tick function control dispatched by the game.
#[derive(Clone, Debug)]
pub enum FunctionControl {
    /// The case-sensitive function name without its leading `$`.
    Call(String),
    Return,
}

fn function_name(name: &str) -> Result<&str, String> {
    if let Some(bare) = name.strip_prefix('$') {
        let mut bytes = bare.bytes();
        if bytes
            .next()
            .is_some_and(|b| b.is_ascii_alphabetic() || b == b'_')
            && bytes.all(|b| b.is_ascii_alphanumeric() || b == b'_')
        {
            return Ok(bare);
        }
    }
    Err(format!("invalid function name {name:?}"))
}

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

fn declaration<'a>(code: &'a str, name: &str) -> Option<&'a str> {
    code.strip_prefix(name)
        .filter(|rest| rest.is_empty() || rest.starts_with(char::is_whitespace))
}

fn quoted_path(arguments: &str) -> Option<(String, &str)> {
    let arguments = arguments.trim_start();
    let mut strings = serde_json::Deserializer::from_str(arguments).into_iter::<String>();
    let path = strings
        .next()
        .and_then(Result::ok)
        .filter(|p| !p.is_empty() && !p.contains('\0'))?;
    Some((path, &arguments[strings.byte_offset()..]))
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
    pub handler: Option<(String, Vec<Row>)>,
    pub clear_listeners: bool,
    pub screenshot: Option<String>,
    pub input_source: String,
    pub control: Option<FunctionControl>,
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
        if code.starts_with('$') {
            let (name, arguments) = code
                .split_once('(')
                .ok_or_else(|| format!("expected a function call, got {code:?}"))?;
            let name = function_name(name)?;
            if !arguments
                .strip_suffix(')')
                .is_some_and(|args| args.trim().is_empty())
            {
                return Err(format!(
                    "${name}() takes no arguments and must be on its own line"
                ));
            }
            row.control = Some(FunctionControl::Call(name.to_owned()));
            row.input_source = code.to_owned();
            return Ok(row);
        }
        if code == "RETURN" {
            row.control = Some(FunctionControl::Return);
            row.input_source = code.to_owned();
            return Ok(row);
        }
        if code.split_whitespace().next() == Some("RETURN") {
            return Err("RETURN must be on its own line".into());
        }
        if code == "CLEAR_LISTENERS" {
            row.clear_listeners = true;
            return Ok(row);
        }
        if let Some(args) = code.strip_prefix("ScreenShot(") {
            let (path, rest) = quoted_path(args).ok_or("ScreenShot expects a quoted file path")?;
            let rest = rest
                .trim_start()
                .strip_prefix(')')
                .ok_or("expected ')' after screenshot path")?;
            let suffix = rest.split_once("//").map_or(rest, |(code, _)| code);
            row.pause = !suffix.trim().is_empty();
            if row.pause && !(suffix.starts_with(char::is_whitespace) && suffix.trim() == "PAUSE") {
                return Err("ScreenShot may only be followed by PAUSE".into());
            }
            row.input_source = format!("ScreenShot({})", serde_json::to_string(&path).unwrap());
            row.screenshot = Some(path);
            return Ok(row);
        }
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
    pub(crate) functions: BTreeMap<String, Vec<Row>>,
    pub(crate) import_keys: BTreeMap<String, (String, usize)>,
}

impl Script {
    /// Load an inline script, resolving relative imports against the working directory.
    pub fn parse(text: &str) -> Result<Self, ParseError> {
        imports::load(ScriptSource::Inline(text), Path::new(".")).map_err(Into::into)
    }

    pub(crate) fn uses_functions(&self) -> bool {
        !self.functions.is_empty() || block_rows(&self.rows).any(|row| row.control.is_some())
    }
}

// Only entry and function bodies can declare listeners, so one pass through
// those bodies and their immediate handlers visits the entire lexical block.
fn block_rows(rows: &[Row]) -> impl Iterator<Item = &Row> {
    rows.iter()
        .flat_map(|row| std::iter::once(row).chain(row.handler.iter().flat_map(|(_, body)| body)))
}

fn parse_error(line: usize, message: impl Into<String>) -> ParseError {
    ParseError {
        line,
        message: message.into(),
    }
}

fn expect_begin<'a>(
    source: &mut impl Iterator<Item = (usize, &'a str)>,
    eof: usize,
    declaration: &str,
) -> Result<(), ParseError> {
    match source.next() {
        Some((_, "BEGIN")) => Ok(()),
        next => Err(parse_error(
            next.map_or(eof, |(line, _)| line),
            format!("expected BEGIN after {declaration} declaration"),
        )),
    }
}

fn parse_block<'a>(
    source: &mut impl Iterator<Item = (usize, &'a str)>,
    eof: usize,
    allow_listeners: bool,
) -> Result<Vec<Row>, ParseError> {
    let mut rows = Vec::new();
    while let Some((line, code)) = source.next() {
        if code == "END" {
            return Ok(rows);
        }
        let error = |message| parse_error(line, message);
        if declaration(code, "IMPORT").is_some() {
            return Err(error(imports::IMPORT_POSITION.into()));
        }
        if code.starts_with("ON(") {
            if !allow_listeners {
                return Err(error("ON cannot be nested inside an event handler".into()));
            }
            let (_, name, rest) = next_command(code).map_err(error)?;
            if !rest.is_empty()
                || !matches!(name, "Hurt" | "HpLost" | "Death" | "CollideX" | "CollideZ")
            {
                return Err(error(format!(
                    "invalid ON declaration {code:?}: expected one supported event on its own line"
                )));
            }
            expect_begin(source, eof, "ON")?;
            rows.push(Row {
                line,
                handler: Some((name.to_owned(), parse_block(source, eof, false)?)),
                ..Row::default()
            });
        } else {
            rows.push(Row::parse(line, code).map_err(error)?);
        }
    }
    Err(parse_error(eof, "missing END"))
}
