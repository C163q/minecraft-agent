use serde_json::{Value, json};
use std::{borrow::Cow, collections::BTreeMap, error::Error, fmt, io, iter::Peekable, path::Path};

mod expressions;
mod imports;

use expressions::Expr;

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
    sigiled_name(name, "function")
}

fn sigiled_name<'a>(name: &'a str, kind: &str) -> Result<&'a str, String> {
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
    Err(format!("invalid {kind} name {name:?}"))
}

// Consume one command including nested unit expressions. Whitespace separates
// neighboring commands only after the outer closing parenthesis.
fn next_command(code: &str) -> Result<(&str, &str, &str), String> {
    let (name, arguments) = code
        .split_once('(')
        .ok_or("expected a command followed by '('")?;
    if name.starts_with('$') {
        function_name(name)?;
    } else if !name.bytes().all(|b| b.is_ascii_alphabetic()) || name.is_empty() {
        return Err(format!("invalid command name {name:?}"));
    }
    let mut nested = 0;
    for (index, ch) in unquoted_characters(arguments) {
        match ch {
            '(' => nested += 1,
            ')' if nested == 0 => {
                let rest = &arguments[index + 1..];
                if !rest.is_empty() && !rest.as_bytes()[0].is_ascii_whitespace() {
                    return Err(format!("expected whitespace after {name}"));
                }
                return Ok((name, &arguments[..index], rest.trim_ascii_start()));
            }
            ')' => nested -= 1,
            _ => (),
        }
    }
    Err(format!("missing closing ')' in {name}"))
}

fn unquoted_characters(code: &str) -> impl Iterator<Item = (usize, char)> {
    let mut in_string = false;
    let mut escaped = false;
    code.char_indices().filter(move |&(_, ch)| {
        if in_string {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
            }
            false
        } else if ch == '"' {
            in_string = true;
            false
        } else {
            true
        }
    })
}

fn uncomment(code: &str) -> &str {
    unquoted_characters(code)
        .find(|&(index, ch)| ch == '/' && code[index..].starts_with("//"))
        .map_or(code, |(index, _)| &code[..index])
        .trim_end()
}

fn signature(code: &str) -> Result<(&str, Vec<String>), String> {
    if !code.contains('(') {
        return Ok((function_name(code)?, Vec::new()));
    }
    let (name, arguments, rest) = next_command(code)?;
    if !rest.is_empty() {
        return Err("FUNCTION must be on its own line".into());
    }
    let mut params = Vec::new();
    if !arguments.trim().is_empty() {
        for argument in arguments.split(',') {
            let name = sigiled_name(argument.trim(), "parameter")?;
            if params.iter().any(|existing| existing == name) {
                return Err(format!("duplicate parameter ${name}"));
            }
            params.push(name.to_owned());
        }
    }
    Ok((function_name(name)?, params))
}

fn declaration<'a>(code: &'a str, name: &str) -> Option<&'a str> {
    code.strip_prefix(name)
        .filter(|rest| rest.is_empty() || rest.starts_with(char::is_whitespace))
}

fn quoted_path(arguments: &str) -> Option<(String, &str)> {
    let (path, rest) = quoted_string(arguments)?;
    (!path.is_empty() && !path.contains('\0')).then_some((path, rest))
}

fn quoted_string(arguments: &str) -> Option<(String, &str)> {
    let arguments = arguments.trim_start();
    let mut strings = serde_json::Deserializer::from_str(arguments).into_iter::<String>();
    let text = strings.next().and_then(Result::ok)?;
    Some((text, &arguments[strings.byte_offset()..]))
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

fn top_level_characters(code: &str) -> impl Iterator<Item = (usize, char)> {
    let mut depth = 0;
    unquoted_characters(code).filter(move |&(_, ch)| {
        match ch {
            '(' | '[' | '{' => depth += 1,
            ')' | ']' | '}' => depth -= 1,
            _ => (),
        }
        depth == 0
    })
}

fn top_level_arguments(code: &str) -> impl Iterator<Item = &str> {
    let mut start = 0;
    top_level_characters(code)
        .chain(std::iter::once((code.len(), ',')))
        .filter(|&(_, ch)| ch == ',')
        .map(move |(index, _)| &code[std::mem::replace(&mut start, index + 1)..index])
}

fn movement(name: &str, arguments: &str) -> Result<(InputAction, Option<Value>), String> {
    let absolute = name == "MouseMoveTo";
    let mut values = Vec::new();
    let mut args = Vec::new();
    let mut inputs = Vec::new();
    for argument in top_level_arguments(arguments) {
        let (name, number, rest) = next_command(argument.trim())?;
        let unit = match name {
            "Px" => "px",
            "Percentage" => "percentage",
            "Rotate" => "rotate",
            _ => return Err(format!("unknown mouse unit {name:?}")),
        };
        if !rest.is_empty() {
            return Err("expected ',' between mouse arguments".into());
        }
        let number = number.trim();
        let expression = if let Ok(value) = number.parse::<f64>() {
            if !value.is_finite() {
                return Err(format!(
                    "expected a finite number in {name}, got {number:?}"
                ));
            }
            values.push(value);
            args.push(json!({"unit":unit, "value":value}));
            // Legacy mouse literals also allow a leading plus.
            number.strip_prefix('+').unwrap_or(number)
        } else {
            number
        };
        inputs.push((Some(unit), expressions::parse(expression)?));
    }
    if !(2..=4).contains(&inputs.len()) {
        return Err("mouse movement expects 2, 3 or 4 arguments".into());
    }
    let action = InputAction {
        name: name.to_owned(),
        args: inputs,
    };
    if values.len() != action.args.len() {
        return Ok((action, None));
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
        _ => unreachable!(),
    }
    Ok((action, Some(json!({"absolute":absolute, "args":args}))))
}

#[derive(Debug)]
struct InputAction {
    name: String,
    args: Vec<(Option<&'static str>, Expr)>,
}

impl InputAction {
    fn json(&self) -> Value {
        let arguments = self.args.iter().map(|(unit, expression)| match unit {
            Some(unit) => json!([unit, expression.json()]),
            None => expression.json().clone(),
        });
        Value::Array(std::iter::once(json!(self.name)).chain(arguments).collect())
    }
}

#[derive(Debug)]
enum Assignment {
    Value(Expr),
    Call(Vec<Expr>),
}

impl Assignment {
    fn expressions(&self) -> &[Expr] {
        match self {
            Self::Value(expression) => std::slice::from_ref(expression),
            Self::Call(args) => args,
        }
    }
}

#[derive(Debug)]
enum ValueStatement {
    Assign {
        declare: bool,
        target: Expr,
        value: Assignment,
    },
    Call(Vec<Expr>),
    Return(Expr),
    Print(String, Vec<Expr>),
}

fn json_args(args: &[Expr]) -> Vec<&Value> {
    args.iter().map(Expr::json).collect()
}

impl ValueStatement {
    fn json(&self) -> Value {
        match self {
            Self::Assign {
                declare,
                target,
                value,
            } => {
                let op = if *declare { "let" } else { "set" };
                match value {
                    Assignment::Value(expression) => {
                        json!({"op":op,"target":target.json(),"expr":expression.json()})
                    }
                    Assignment::Call(args) => {
                        json!({"op":op,"target":target.json(),"args":json_args(args)})
                    }
                }
            }
            Self::Call(args) => json!({"args":json_args(args)}),
            Self::Return(expression) => json!({"expr":expression.json()}),
            Self::Print(format, args) => {
                json!({"op":"print","format":format,"args":json_args(args)})
            }
        }
    }

    fn call_arity(&self) -> Option<usize> {
        match self {
            Self::Call(args)
            | Self::Assign {
                value: Assignment::Call(args),
                ..
            } => Some(args.len()),
            _ => None,
        }
    }

    fn uses_control_flow(&self) -> bool {
        let expressions = match self {
            Self::Assign { target, value, .. } => {
                if target.uses_control_flow() {
                    return true;
                }
                value.expressions()
            }
            Self::Call(args) | Self::Print(_, args) => args,
            Self::Return(expression) => std::slice::from_ref(expression),
        };
        expressions.iter().any(Expr::uses_control_flow)
    }
}

#[derive(Debug)]
pub(crate) struct Branch {
    pub line: usize,
    pub condition: Option<Expr>,
    pub body: Vec<Row>,
}

#[derive(Debug)]
pub(crate) enum Flow {
    If(Vec<Branch>),
    While(Expr, Vec<Row>),
    For(String, Expr, Vec<Row>),
    Break,
    Continue,
}

impl Flow {
    fn bodies(&self) -> impl DoubleEndedIterator<Item = &[Row]> {
        let (branches, body) = match self {
            Self::If(branches) => (branches.as_slice(), None),
            Self::While(_, body) | Self::For(_, _, body) => (&[][..], Some(body.as_slice())),
            Self::Break | Self::Continue => (&[][..], None),
        };
        branches
            .iter()
            .map(|branch| branch.body.as_slice())
            .chain(body)
    }
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
    pub file: Option<String>,
    pub flow: Option<Flow>,
    statement: Option<ValueStatement>,
    wait_expression: Option<(String, Expr)>,
    input_actions: Vec<InputAction>,
    event_binding: Option<String>,
}

impl Row {
    pub(crate) fn has_input(&self) -> bool {
        !(self.keys.is_empty()
            && self.moves.is_empty()
            && self.buttons.is_empty()
            && self.input_actions.is_empty())
    }

    pub(crate) fn uses_values(&self) -> bool {
        self.statement.is_some()
            || self.wait_expression.is_some()
            || !self.input_actions.is_empty()
            || self.event_binding.is_some()
    }

    pub(crate) fn has_flow_expression(&self) -> bool {
        self.statement
            .as_ref()
            .is_some_and(ValueStatement::uses_control_flow)
            || self
                .wait_expression
                .as_ref()
                .is_some_and(|(_, expression)| expression.uses_control_flow())
            || self
                .input_actions
                .iter()
                .flat_map(|action| &action.args)
                .any(|(_, expression)| expression.uses_control_flow())
    }

    fn set_file(&mut self, file: &str) {
        self.file = Some(file.into());
        if let Some((_, body)) = &mut self.handler {
            for row in body {
                row.set_file(file);
            }
        }
        match &mut self.flow {
            Some(Flow::If(branches)) => {
                for row in branches.iter_mut().flat_map(|branch| &mut branch.body) {
                    row.set_file(file);
                }
            }
            Some(Flow::While(_, body) | Flow::For(_, _, body)) => {
                for row in body {
                    row.set_file(file);
                }
            }
            _ => (),
        }
    }

    pub(crate) fn statement_wire(&self) -> Option<Value> {
        self.statement.as_ref().map(ValueStatement::json)
    }

    pub(crate) fn wait_wire(&self) -> Option<(&str, Value)> {
        self.wait_expression.as_ref().map(|(source, expression)| {
            (
                source.as_str(),
                json!({"op":"wait","expr":expression.json()}),
            )
        })
    }

    pub(crate) fn input_wire(&self) -> Option<Value> {
        (!self.input_actions.is_empty()).then(|| json!({
            "op":"input", "actions":self.input_actions.iter().map(InputAction::json).collect::<Vec<_>>()
        }))
    }

    pub(crate) fn event_wire(&self) -> Option<Value> {
        self.event_binding.as_ref().map(|name| json!({"bind":name}))
    }

    fn parse_assignment(&mut self, declare: bool, code: &str) -> Result<(), String> {
        let (index, _) = top_level_characters(code)
            .find(|&(_, ch)| ch == '=')
            .ok_or("expected '=' for assignment")?;
        let (target, body) = (&code[..index], &code[index + 1..]);
        let target = expressions::parse_target(target.trim())?;
        if declare && target.json()[0] != "var" {
            return Err("LET must declare a variable name".into());
        }
        let value = if let Some((name, args)) = expressions::parse_call(body.trim())? {
            self.control = Some(FunctionControl::Call(name));
            Assignment::Call(args)
        } else {
            Assignment::Value(expressions::parse(body.trim())?)
        };
        self.statement = Some(ValueStatement::Assign {
            declare,
            target,
            value,
        });
        Ok(())
    }

    fn parse(line: usize, mut code: &str) -> Result<Self, String> {
        let mut row = Self {
            line,
            wait: u64::from(code.is_empty()),
            ..Self::default()
        };
        if code.starts_with('$') {
            let (name, args) = expressions::parse_call(code)
                .map_err(|message| format!("invalid function call {code:?}: {message}"))?
                .ok_or_else(|| format!("expected a function call, got {code:?}"))?;
            if !args.is_empty() {
                row.statement = Some(ValueStatement::Call(args));
            }
            row.control = Some(FunctionControl::Call(name));
            row.input_source = code.to_owned();
            return Ok(row);
        }
        if let Some(expression) = declaration(code, "RETURN") {
            row.control = Some(FunctionControl::Return);
            row.input_source = code.to_owned();
            if !expression.trim().is_empty() {
                row.statement = Some(ValueStatement::Return(
                    expressions::parse(expression.trim())
                        .map_err(|message| format!("RETURN: {message}"))?,
                ));
            }
            return Ok(row);
        }
        if let Some(assignment) = declaration(code, "LET").or_else(|| declaration(code, "SET")) {
            row.parse_assignment(code.starts_with("LET"), assignment)?;
            row.input_source = code.to_owned();
            return Ok(row);
        }
        if code.starts_with("Print(") {
            let (_, arguments, rest) = next_command(code)?;
            if !rest.is_empty() {
                return Err("Print must be on its own line".into());
            }
            let (format, suffix) = parse_print_format(arguments)?;
            let args = expressions::parse_args(suffix)?;
            let placeholders = print_placeholders(&format)?;
            if placeholders != args.len() {
                return Err(format!(
                    "Print expects {placeholders} values, got {}",
                    args.len()
                ));
            }
            row.statement = Some(ValueStatement::Print(format, args));
            row.input_source = code.to_owned();
            return Ok(row);
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
            row.pause = !rest.trim().is_empty();
            if row.pause && !(rest.starts_with(char::is_whitespace) && rest.trim() == "PAUSE") {
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
        let mut actions = Vec::new();
        let mut has_value_inputs = false;
        while !code.is_empty() {
            let (name, arg, rest) = next_command(code)?;
            match name {
                "Wait" | "WaitMouseMove" if !first => {
                    return Err(format!("{name} must be the first command on its line"));
                }
                "Wait" => {
                    input_source = rest;
                    let arg = arg.trim();
                    if !arg.is_empty()
                        && arg
                            .strip_prefix('-')
                            .unwrap_or(arg)
                            .bytes()
                            .all(|b| b.is_ascii_digit())
                    {
                        row.wait = arg.parse::<u64>().ok().filter(|n| *n > 0).ok_or_else(|| {
                            format!("expected a positive 64-bit tick count, got {arg:?}")
                        })?;
                    } else {
                        row.wait_expression =
                            Some((format!("Wait({arg})"), expressions::parse(arg)?));
                    }
                }
                "WaitMouseMove" => {
                    input_source = rest;
                    if !arg.trim().is_empty() {
                        return Err("WaitMouseMove expects no arguments".into());
                    }
                    row.wait_mouse = true;
                }
                "MouseMove" | "MouseMoveTo" => {
                    let (action, legacy) = movement(name, arg)?;
                    actions.push(action);
                    match legacy {
                        Some(movement) => row.moves.push(movement),
                        None => has_value_inputs = true,
                    }
                }
                "Key" | "KeyPress" | "KeyRelease" | "Mouse" | "MousePress" | "MouseRelease" => {
                    let action = match name {
                        "MousePress" | "KeyPress" => KeyAction::Press,
                        "MouseRelease" | "KeyRelease" => KeyAction::Release,
                        _ => KeyAction::Temporary,
                    };
                    let arg = arg.trim();
                    let is_value_literal = matches!(arg, "true" | "false" | "null");
                    let argument = if !arg.is_empty()
                        && !is_value_literal
                        && arg.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
                    {
                        if name.starts_with("Mouse") {
                            if !matches!(arg, "left" | "right" | "middle") {
                                return Err(format!("unknown mouse button {arg:?}"));
                            }
                            record_action(&mut row.buttons, "mouse button", arg, action)?;
                        } else {
                            record_action(&mut row.keys, "key", arg, action)?;
                        }
                        Expr::string(arg)
                    } else {
                        has_value_inputs = true;
                        expressions::parse(arg)?
                    };
                    actions.push(InputAction {
                        name: name.to_owned(),
                        args: vec![(None, argument)],
                    });
                }
                _ => return Err(format!("unknown command {name:?}")),
            }
            code = rest;
            first = false;
        }
        if has_value_inputs {
            row.input_actions = actions;
        }
        row.input_source = input_source.to_owned();
        Ok(row)
    }
}

fn parse_print_format(arguments: &str) -> Result<(String, &str), String> {
    let (format, suffix) =
        quoted_string(arguments).ok_or("Print expects a string literal format")?;
    let suffix = suffix.trim_start();
    if suffix.is_empty() {
        return Ok((format, suffix));
    }
    let args = suffix
        .strip_prefix(',')
        .filter(|args| !args.trim().is_empty())
        .ok_or("expected ',' and a value after Print format")?;
    Ok((format, args))
}

fn print_placeholders(format: &str) -> Result<usize, String> {
    let mut placeholders = 0;
    let mut chars = format.chars();
    while let Some(ch) = chars.next() {
        match (
            ch,
            if matches!(ch, '{' | '}') {
                chars.next()
            } else {
                None
            },
        ) {
            ('{', Some('}')) => placeholders += 1,
            ('{', Some('{')) | ('}', Some('}')) => (),
            ('{' | '}', _) => return Err("invalid brace in Print format".into()),
            _ => (),
        }
    }
    Ok(placeholders)
}

#[derive(Debug)]
pub struct Script {
    pub(crate) rows: Vec<Row>,
    pub(crate) functions: BTreeMap<String, Vec<Row>>,
    pub(crate) params: BTreeMap<String, Vec<String>>,
    pub(crate) checks: Vec<Vec<Row>>,
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

    pub(crate) fn uses_values(&self) -> bool {
        block_rows(&self.rows).any(Row::uses_values)
    }

    pub(crate) fn uses_control_flow(&self) -> bool {
        block_rows(&self.rows).any(|row| row.flow.is_some())
    }
}

fn block_rows(rows: &[Row]) -> impl Iterator<Item = &Row> {
    let mut stack = vec![rows.iter()];
    std::iter::from_fn(move || {
        loop {
            if let Some(row) = stack.last_mut()?.next() {
                if let Some(flow) = &row.flow {
                    stack.extend(flow.bodies().rev().map(<[Row]>::iter));
                }
                if let Some((_, body)) = &row.handler {
                    stack.push(body.iter());
                }
                return Some(row);
            }
            stack.pop();
        }
    })
}

fn parse_error(line: usize, message: impl Into<String>) -> ParseError {
    ParseError {
        line,
        message: message.into(),
    }
}

type LogicalLine<'a> = (usize, Cow<'a, str>);

fn logical_lines(text: &str) -> impl Iterator<Item = LogicalLine<'_>> {
    let mut source = text.lines().enumerate();
    std::iter::from_fn(move || {
        let (index, code) = source.by_ref().find_map(|(index, raw)| {
            let raw = raw.trim();
            (!raw.starts_with("//")).then(|| (index, uncomment(raw)))
        })?;
        let mut depth = composite_depth(code);
        let mut code = Cow::Borrowed(code);
        while depth > 0 {
            let Some((_, raw)) = source.next() else {
                break;
            };
            let next = uncomment(raw.trim());
            depth += composite_depth(next);
            code.to_mut().push('\n');
            code.to_mut().push_str(next);
        }
        Some((index + 1, code))
    })
}

fn composite_depth(code: &str) -> i32 {
    unquoted_characters(code)
        .map(|(_, ch)| match ch {
            '[' | '{' => 1,
            ']' | '}' => -1,
            _ => 0,
        })
        .sum()
}

fn expect_begin<'a>(
    source: &mut impl Iterator<Item = LogicalLine<'a>>,
    eof: usize,
    declaration: &str,
) -> Result<(), ParseError> {
    match source.next() {
        Some((_, code)) if code == "BEGIN" => Ok(()),
        next => Err(parse_error(
            next.map_or(eof, |(line, _)| line),
            format!("expected BEGIN after {declaration} declaration"),
        )),
    }
}

fn parse_block<'a>(
    source: &mut Peekable<impl Iterator<Item = LogicalLine<'a>>>,
    eof: usize,
    allow_listeners: bool,
    depth: usize,
) -> Result<Vec<Row>, ParseError> {
    let mut rows = Vec::new();
    while let Some((line, code)) = source.next() {
        let code = code.as_ref();
        if code == "END" {
            return Ok(rows);
        }
        let error = |message| parse_error(line, message);
        if declaration(code, "IMPORT").is_some() {
            return Err(error(imports::IMPORT_POSITION.into()));
        }
        if let Some(condition) = control_head(code, "IF") {
            let (branches, trailing_blanks) =
                parse_if(source, eof, allow_listeners, depth, line, condition)?;
            rows.push(flow_row(line, code, Flow::If(branches)));
            rows.extend(trailing_blanks.into_iter().map(|line| Row {
                line,
                wait: 1,
                ..Row::default()
            }));
            continue;
        }
        if let Some(keyword) = branch_keyword(code) {
            return Err(error(format!("{keyword} must follow IF or ELIF")));
        }
        if let Some(condition) = control_head(code, "WHILE") {
            let condition = parse_condition(condition, "WHILE", line)?;
            let body = parse_nested(source, eof, allow_listeners, depth, "WHILE", line)?;
            rows.push(flow_row(line, code, Flow::While(condition, body)));
            continue;
        }
        if let Some(header) = declaration(code, "FOR") {
            let (name, expression) = parse_for(header).map_err(error)?;
            let body = parse_nested(source, eof, allow_listeners, depth, "FOR", line)?;
            rows.push(flow_row(line, code, Flow::For(name, expression, body)));
            continue;
        }
        if let Some((keyword, flow)) = [("BREAK", Flow::Break), ("CONTINUE", Flow::Continue)]
            .into_iter()
            .find(|(keyword, _)| control_head(code, keyword).is_some())
        {
            if code != keyword {
                return Err(error(format!("{keyword} must be on its own line")));
            }
            rows.push(flow_row(line, code, flow));
            continue;
        }
        if code.starts_with("ON(") {
            if !allow_listeners {
                return Err(error("ON cannot be nested inside an event handler".into()));
            }
            let (_, event, rest) = next_command(code).map_err(error)?;
            let (name, binding) = if event.contains('(') {
                let (name, binding, suffix) = next_command(event.trim()).map_err(error)?;
                if !suffix.is_empty() {
                    return Err(error("expected one event binding in ON".into()));
                }
                let binding = sigiled_name(binding.trim(), "event binding").map_err(error)?;
                (name, Some(binding.to_owned()))
            } else {
                (event, None)
            };
            if !rest.is_empty()
                || !matches!(
                    name,
                    "Hurt"
                        | "HpLost"
                        | "Death"
                        | "CollideX"
                        | "CollideZ"
                        | "BlockBroken"
                        | "LeftGround"
                        | "ScreenOpened"
                        | "DamageBlocked"
                        | "BlockInteracted"
                )
            {
                return Err(error(format!(
                    "invalid ON declaration {code:?}: expected one supported event on its own line"
                )));
            }
            let body = parse_nested(source, eof, false, depth, "ON", line)?;
            rows.push(Row {
                line,
                handler: Some((name.to_owned(), body)),
                input_source: code.into(),
                event_binding: binding,
                ..Row::default()
            });
        } else {
            rows.push(Row::parse(line, code).map_err(error)?);
        }
    }
    Err(parse_error(eof, "missing END"))
}

// An IF adds five levels to the wire tree. Leave space under Gson's 255-level
// JSON limit for a maximally nested expression with object fields.
const MAX_BLOCK_DEPTH: usize = 30;

fn parse_nested<'a>(
    source: &mut Peekable<impl Iterator<Item = LogicalLine<'a>>>,
    eof: usize,
    allow_listeners: bool,
    depth: usize,
    keyword: &str,
    line: usize,
) -> Result<Vec<Row>, ParseError> {
    if depth >= MAX_BLOCK_DEPTH {
        return Err(parse_error(
            line,
            format!("block nesting exceeds the limit of {MAX_BLOCK_DEPTH}"),
        ));
    }
    expect_begin(source, eof, keyword)?;
    parse_block(source, eof, allow_listeners, depth + 1)
}

fn parse_if<'a>(
    source: &mut Peekable<impl Iterator<Item = LogicalLine<'a>>>,
    eof: usize,
    allow_listeners: bool,
    depth: usize,
    line: usize,
    condition: &str,
) -> Result<(Vec<Branch>, Vec<usize>), ParseError> {
    let condition = parse_condition(condition, "IF", line)?;
    let mut branches = vec![Branch {
        line,
        condition: Some(condition),
        body: parse_nested(source, eof, allow_listeners, depth, "IF", line)?,
    }];
    loop {
        let mut blanks = Vec::new();
        while source.peek().is_some_and(|(_, code)| code.is_empty()) {
            let (line, _) = source.next().unwrap();
            blanks.push(line);
        }
        let Some(keyword) = source.peek().and_then(|(_, code)| branch_keyword(code)) else {
            return Ok((branches, blanks));
        };
        let (line, header) = source.next().unwrap();
        if branches
            .last()
            .is_some_and(|branch| branch.condition.is_none())
        {
            return Err(parse_error(line, format!("{keyword} cannot follow ELSE")));
        }
        let arguments = control_head(&header, keyword).unwrap();
        let condition = if keyword == "ELSE" {
            if !arguments.trim().is_empty() {
                return Err(parse_error(line, "ELSE must be on its own line"));
            }
            None
        } else {
            Some(parse_condition(arguments, keyword, line)?)
        };
        let body = parse_nested(source, eof, allow_listeners, depth, keyword, line)?;
        branches.push(Branch {
            line,
            condition,
            body,
        });
    }
}

fn flow_row(line: usize, code: &str, flow: Flow) -> Row {
    Row {
        line,
        input_source: code.into(),
        flow: Some(flow),
        ..Row::default()
    }
}

fn control_head<'a>(code: &'a str, keyword: &str) -> Option<&'a str> {
    declaration(code, keyword).or_else(|| {
        code.strip_prefix(keyword)
            .filter(|rest| rest.starts_with('('))
    })
}

fn branch_keyword(code: &str) -> Option<&'static str> {
    ["ELIF", "ELSE"]
        .into_iter()
        .find(|keyword| control_head(code, keyword).is_some())
}

fn parse_condition(source: &str, keyword: &str, line: usize) -> Result<Expr, ParseError> {
    let expression = source
        .trim()
        .strip_prefix('(')
        .and_then(|source| source.strip_suffix(')'))
        .ok_or_else(|| parse_error(line, format!("{keyword} expects a parenthesized condition")))?;
    expressions::parse(expression)
        .map_err(|error| parse_error(line, format!("{keyword} condition: {error}")))
}

fn parse_for(header: &str) -> Result<(String, Expr), String> {
    let (name, rest) = header
        .trim()
        .split_once(char::is_whitespace)
        .ok_or("FOR expects a loop variable followed by IN and an expression")?;
    let expression = declaration(rest.trim_start(), "IN")
        .filter(|expression| !expression.trim().is_empty())
        .ok_or("FOR expects a loop variable followed by IN and an expression")?;
    let name = sigiled_name(name, "FOR variable")?.into();
    let expression =
        expressions::parse(expression).map_err(|error| format!("FOR expression: {error}"))?;
    Ok((name, expression))
}

fn validate_block<'a>(
    rows: &'a [Row],
    mut locals: BTreeMap<&'a str, bool>,
    captured: &BTreeMap<&'a str, bool>,
    arities: &BTreeMap<&str, usize>,
    in_loop: bool,
) -> Result<(), ParseError> {
    for row in rows {
        let error = |message: String| parse_error(row.line, message);
        if let Some(FunctionControl::Call(name)) = &row.control {
            let actual = row
                .statement
                .as_ref()
                .and_then(ValueStatement::call_arity)
                .unwrap_or(0);
            let expected = arities
                .get(name.as_str())
                .ok_or_else(|| error(format!("undefined function ${name}")))?;
            if actual != *expected {
                return Err(error(format!(
                    "function ${name} expects {expected} arguments, got {actual}"
                )));
            }
        }
        let binding = |name: &str| locals.get(name).or_else(|| captured.get(name)).copied();
        let validate = |expr: &Expr, line: usize| {
            for name in expr.references() {
                if binding(name).is_none() {
                    return Err(parse_error(line, format!("undeclared variable ${name}")));
                }
            }
            Ok(())
        };
        let visible = || {
            captured
                .iter()
                .chain(&locals)
                .map(|(&name, &read_only)| (name, read_only))
                .collect()
        };
        if let Some((_, body)) = &row.handler {
            let event_binding = row
                .event_binding
                .as_deref()
                .map(|name| (name, true))
                .into_iter()
                .collect();
            validate_block(body, event_binding, &visible(), arities, false)?;
        }
        match &row.flow {
            Some(Flow::If(branches)) => {
                let visible = visible();
                for branch in branches {
                    if let Some(condition) = &branch.condition {
                        validate(condition, branch.line)?;
                    }
                    validate_block(&branch.body, BTreeMap::new(), &visible, arities, in_loop)?;
                }
            }
            Some(Flow::While(condition, body)) => {
                validate(condition, row.line)?;
                validate_block(body, BTreeMap::new(), &visible(), arities, true)?;
            }
            Some(Flow::For(name, expression, body)) => {
                validate(expression, row.line)?;
                let binding = BTreeMap::from([(name.as_str(), false)]);
                validate_block(body, binding, &visible(), arities, true)?;
            }
            Some(Flow::Break) if !in_loop => {
                return Err(error("BREAK requires an enclosing loop".into()));
            }
            Some(Flow::Continue) if !in_loop => {
                return Err(error("CONTINUE requires an enclosing loop".into()));
            }
            _ => (),
        }
        if let Some((_, expr)) = &row.wait_expression {
            validate(expr, row.line)?;
        }
        for (_, expr) in row.input_actions.iter().flat_map(|action| &action.args) {
            validate(expr, row.line)?;
        }
        match &row.statement {
            Some(ValueStatement::Assign {
                declare,
                target,
                value,
            }) => {
                let name = target.target_name().expect("parsed assignment target");
                if *declare {
                    if locals.contains_key(name) {
                        return Err(error(format!("duplicate variable declaration ${name}")));
                    }
                } else {
                    validate(target, row.line)?;
                    if binding(name) == Some(true) {
                        return Err(error(format!("event variable ${name} is read-only")));
                    }
                }
                for expr in value.expressions() {
                    validate(expr, row.line)?;
                }
                if *declare {
                    locals.insert(name, false);
                }
            }
            Some(ValueStatement::Call(args) | ValueStatement::Print(_, args)) => {
                for expr in args {
                    validate(expr, row.line)?;
                }
            }
            Some(ValueStatement::Return(expr)) => validate(expr, row.line)?,
            None => (),
        }
    }
    Ok(())
}
