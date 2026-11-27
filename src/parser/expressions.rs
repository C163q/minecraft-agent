use serde_json::Value;
use std::collections::HashSet;

const MAX_DEPTH: usize = 48;
const MAX_NODES: usize = 4096;
const MAX_SOURCE_BYTES: usize = 1 << 20;
const MAX_NUMBER_BYTES: usize = 4096;

/// Expressions are protocol arrays. Numeric tokens keep their source spelling
/// so the Mod can evaluate them with decimal arithmetic.
#[derive(Clone, Debug, PartialEq)]
pub(super) struct Expr {
    wire: Value,
    depth: usize,
}

impl Expr {
    pub(super) fn json(&self) -> &Value {
        &self.wire
    }

    pub(super) fn string(value: &str) -> Self {
        Self {
            wire: Value::Array(vec!["lit".into(), value.into()]),
            depth: 1,
        }
    }

    pub(super) fn references(&self) -> impl Iterator<Item = &str> {
        let mut stack = vec![&self.wire];
        std::iter::from_fn(move || {
            while let Some(node) = stack.pop() {
                let children = node.as_array().unwrap();
                match children[0].as_str().unwrap() {
                    "var" => return children[1].as_str(),
                    "lit" | "num" => (),
                    "field" => stack.push(&children[1]),
                    "object" => {
                        for field in children[1..].iter().rev() {
                            stack.push(&field[1]);
                        }
                    }
                    _ => stack.extend(children[1..].iter().rev()),
                }
            }
            None
        })
    }

    pub(super) fn target_name(&self) -> Option<&str> {
        let mut node = &self.wire;
        loop {
            match node[0].as_str().unwrap() {
                "var" => return node[1].as_str(),
                "field" | "index" => node = &node[1],
                _ => return None,
            }
        }
    }
}

pub(super) fn parse(source: &str) -> Result<Expr, String> {
    let mut parser = Parser::new(source)?;
    let expression = parser.expression(1)?;
    parser.finish()?;
    Ok(expression)
}

pub(super) fn parse_target(source: &str) -> Result<Expr, String> {
    let target = parse(source)?;
    if target.target_name().is_none() {
        return Err("SET target must be a variable or a path starting at a variable".into());
    }
    Ok(target)
}

pub(super) fn parse_args(source: &str) -> Result<Vec<Expr>, String> {
    let mut parser = Parser::new(source)?;
    let arguments = parser.list("")?;
    parser.finish()?;
    Ok(arguments)
}

pub(super) fn parse_call(source: &str) -> Result<Option<(String, Vec<Expr>)>, String> {
    let mut parser = Parser::new(source)?;
    let Some(rest) = parser.rest.strip_prefix('$') else {
        return Ok(None);
    };
    parser.rest = rest;
    let name = parser.identifier()?;
    if !parser.take("(") {
        return Ok(None);
    }
    let arguments = parser.list(")")?;
    parser.finish()?;
    Ok(Some((name.into(), arguments)))
}

struct Parser<'a> {
    rest: &'a str,
    nesting: usize,
    nodes: usize,
}

impl<'a> Parser<'a> {
    fn new(source: &'a str) -> Result<Self, String> {
        if source.len() > MAX_SOURCE_BYTES {
            return Err("expression source exceeds size limit".into());
        }
        Ok(Self {
            rest: source.trim_start(),
            nesting: 0,
            nodes: 0,
        })
    }

    fn take(&mut self, token: &str) -> bool {
        if let Some(rest) = self.rest.strip_prefix(token) {
            self.rest = rest.trim_start();
            true
        } else {
            false
        }
    }

    fn expect(&mut self, token: &str) -> Result<(), String> {
        if self.take(token) {
            Ok(())
        } else {
            Err(format!("expected {token:?} in expression"))
        }
    }

    fn finish(&self) -> Result<(), String> {
        if self.rest.is_empty() {
            Ok(())
        } else {
            let preview: String = self.rest.chars().take(32).collect();
            Err(format!("unexpected {preview:?} after expression"))
        }
    }

    fn node(
        &mut self,
        operator: &str,
        operands: impl IntoIterator<Item = Expr>,
        suffix: impl IntoIterator<Item = Value>,
    ) -> Result<Expr, String> {
        let mut wire = vec![operator.into()];
        let mut depth = 1;
        for operand in operands {
            depth = depth.max(operand.depth + 1);
            wire.push(operand.wire);
        }
        wire.extend(suffix);
        self.make_expr(wire, depth)
    }

    fn make_expr(&mut self, wire: Vec<Value>, depth: usize) -> Result<Expr, String> {
        self.nodes += 1;
        if self.nodes > MAX_NODES {
            return Err("expression exceeds node limit".into());
        }
        if depth > MAX_DEPTH {
            return Err("expression exceeds nesting limit".into());
        }
        Ok(Expr {
            wire: Value::Array(wire),
            depth,
        })
    }

    fn nested<T>(
        &mut self,
        parse: impl FnOnce(&mut Self) -> Result<T, String>,
    ) -> Result<T, String> {
        if self.nesting == MAX_DEPTH {
            return Err("expression exceeds nesting limit".into());
        }
        self.nesting += 1;
        let result = parse(self);
        self.nesting -= 1;
        result
    }

    fn expression(&mut self, min_precedence: u8) -> Result<Expr, String> {
        let mut left = self.unary()?;
        loop {
            let (op, precedence) = match self.rest.as_bytes().first() {
                Some(b'+') => ("+", 1),
                Some(b'-') => ("-", 1),
                Some(b'*') => ("*", 2),
                Some(b'/') => ("/", 2),
                _ => break,
            };
            if precedence < min_precedence {
                break;
            }
            self.take(op);
            let right = self.expression(precedence + 1)?;
            left = self.node(op, [left, right], [])?;
        }
        Ok(left)
    }

    fn unary(&mut self) -> Result<Expr, String> {
        if self.take("-") {
            let operand = self.nested(Self::unary)?;
            return self.node("neg", [operand], []);
        }
        let mut expression = self.primary()?;
        loop {
            if self.take(".") {
                let name = self.identifier()?;
                expression = self.node("field", [expression], [name.into()])?;
            } else if self.take("[") {
                let index = self.nested(|p| p.expression(1))?;
                self.expect("]")?;
                expression = self.node("index", [expression, index], [])?;
            } else {
                return Ok(expression);
            }
        }
    }

    fn primary(&mut self) -> Result<Expr, String> {
        match self.rest.as_bytes().first() {
            Some(b'$') => {
                // The sigil and identifier form one token.
                self.rest = &self.rest[1..];
                let name = self.identifier()?;
                if self.rest.starts_with('(') {
                    return Err(
                        "user function calls must occupy an entire statement or assignment".into(),
                    );
                }
                self.node("var", [], [name.into()])
            }
            Some(b'"') => {
                let text = self.quoted()?;
                self.node("lit", [], [text.into()])
            }
            Some(b'0'..=b'9' | b'.') => {
                let text = self.number()?;
                self.node("num", [], [text.into()])
            }
            Some(b'(') => {
                self.take("(");
                let expression = self.nested(|p| p.expression(1))?;
                self.expect(")")?;
                Ok(expression)
            }
            Some(b'[') => {
                self.take("[");
                let items = self.nested(|p| p.list("]"))?;
                self.node("array", items, [])
            }
            Some(b'{') => {
                self.take("{");
                self.nested(Self::object)
            }
            Some(byte) if ident_start(*byte) => self.keyword(),
            _ => Err("expected an expression".into()),
        }
    }

    fn keyword(&mut self) -> Result<Expr, String> {
        let name = self.identifier()?;
        let arity = match name {
            "null" => return self.node("lit", [], [Value::Null]),
            "true" => return self.node("lit", [], [true.into()]),
            "false" => return self.node("lit", [], [false.into()]),
            "Floor" | "Ceil" | "Abs" => 1,
            "Min" | "Max" => 2,
            _ => return Err(format!("unknown value or math function {name:?}")),
        };
        self.expect("(")?;
        let arguments = self.nested(|p| p.list(")"))?;
        if arguments.len() != arity {
            return Err(format!("{name} expects {arity} arguments"));
        }
        self.node(name, arguments, [])
    }

    fn list(&mut self, close: &str) -> Result<Vec<Expr>, String> {
        let mut elements = Vec::new();
        if self.list_end(close) {
            return Ok(elements);
        }
        loop {
            elements.push(self.expression(1)?);
            if self.list_end(close) {
                return Ok(elements);
            }
            self.expect(",")?;
            if self.rest.is_empty() || (!close.is_empty() && self.rest.starts_with(close)) {
                return Err("expected an expression after ','".into());
            }
        }
    }

    fn list_end(&mut self, close: &str) -> bool {
        if close.is_empty() {
            self.rest.is_empty()
        } else {
            self.take(close)
        }
    }

    fn object(&mut self) -> Result<Expr, String> {
        let mut fields = vec!["object".into()];
        let mut names = HashSet::new();
        let mut depth = 1;
        if !self.take("}") {
            loop {
                let name = if self.rest.starts_with('"') {
                    self.quoted()?
                } else {
                    self.identifier()?.to_owned()
                };
                if !names.insert(name.clone()) {
                    return Err(format!("duplicate object field {name:?}"));
                }
                self.expect(":")?;
                let expression = self.expression(1)?;
                depth = depth.max(expression.depth + 1);
                fields.push(Value::Array(vec![name.into(), expression.wire]));
                if self.take("}") {
                    break;
                }
                self.expect(",")?;
            }
        }
        self.make_expr(fields, depth)
    }

    fn identifier(&mut self) -> Result<&'a str, String> {
        let bytes = self.rest.as_bytes();
        if !bytes.first().is_some_and(|byte| ident_start(*byte)) {
            return Err("expected an identifier".into());
        }
        let len = bytes
            .iter()
            .take_while(|&&byte| ident_start(byte) || byte.is_ascii_digit())
            .count();
        let (name, rest) = self.rest.split_at(len);
        self.rest = rest.trim_start();
        Ok(name)
    }

    fn quoted(&mut self) -> Result<String, String> {
        let mut strings = serde_json::Deserializer::from_str(self.rest).into_iter::<String>();
        let value = strings
            .next()
            .expect("quoted() is called with a leading double quote")
            .map_err(|error| format!("invalid string literal: {error}"))?;
        self.rest = self.rest[strings.byte_offset()..].trim_start();
        Ok(value)
    }

    fn number(&mut self) -> Result<&'a str, String> {
        let bytes = self.rest.as_bytes();
        let mut len = digits(bytes);
        if bytes.get(len) == Some(&b'.') {
            len += 1 + digits(&bytes[len + 1..]);
        }
        if len == 1 && bytes[0] == b'.' {
            return Err("expected digits in decimal number".into());
        }
        if matches!(bytes.get(len), Some(b'e' | b'E')) {
            len += 1;
            if matches!(bytes.get(len), Some(b'+' | b'-')) {
                len += 1;
            }
            let exponent_len = digits(&bytes[len..]);
            if exponent_len == 0 {
                return Err("expected digits in number exponent".into());
            }
            len += exponent_len;
        }
        if len > MAX_NUMBER_BYTES {
            return Err("number exceeds size limit".into());
        }
        let (number, rest) = self.rest.split_at(len);
        self.rest = rest.trim_start();
        Ok(number)
    }
}

fn ident_start(byte: u8) -> bool {
    byte.is_ascii_alphabetic() || byte == b'_'
}

fn digits(bytes: &[u8]) -> usize {
    bytes
        .iter()
        .take_while(|byte| byte.is_ascii_digit())
        .count()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn precedence_and_exact_number_text() {
        let expr = parse("Floor(-Abs(.8) * 10 + 18446744073709551615 / 1e0) - 3 - 2").unwrap();
        assert_eq!(
            expr.json(),
            &json!([
                "-",
                [
                    "-",
                    [
                        "Floor",
                        [
                            "+",
                            ["*", ["neg", ["Abs", ["num", ".8"]]], ["num", "10"]],
                            ["/", ["num", "18446744073709551615"], ["num", "1e0"]]
                        ]
                    ],
                    ["num", "3"]
                ],
                ["num", "2"]
            ])
        );
        assert_eq!(expr.references().next(), None);
    }

    #[test]
    fn composite_values_and_paths_keep_only_variable_references() {
        let expr =
            parse(r#"{id: [$target["block-id"], {"$other": true}], name: "// $name"}"#).unwrap();
        assert_eq!(expr.references().collect::<Vec<_>>(), ["target"]);
        let target = parse_target("$copy.pos[$i + 1].name").unwrap();
        assert_eq!(target.target_name(), Some("copy"));
        assert_eq!(target.references().collect::<Vec<_>>(), ["copy", "i"]);
        assert!(parse_target("$copy.pos[0] + 1").is_err());
        assert!(parse_target("{name: 2}.name").is_err());
    }

    #[test]
    fn user_functions_must_occupy_the_entire_expression() {
        let Some((name, args)) =
            parse_call(r#"$call([1, 2], Max($x, 2), "hello, world")"#).unwrap()
        else {
            panic!("expected a user call");
        };
        assert_eq!(name, "call");
        assert_eq!(args.len(), 3);
        assert_eq!(args[1].references().collect::<Vec<_>>(), ["x"]);
        assert!(parse_call("$call($nested())").is_err());
        assert!(parse_call("$call() + 1").is_err());
        assert!(parse_call("$ call()").is_err());
        assert!(parse_call("$x.Field").unwrap().is_none());
        assert!(parse_args("true, null, 12e-").is_err());
        assert!(parse("{a: 0, \"a\": 1}").is_err());
    }

    #[test]
    fn bounds_stack_depth_for_source_and_wire_ast() {
        assert!(parse(&format!("{}1{}", "(".repeat(100), ")".repeat(100))).is_err());
        assert!(parse(&format!("{}1", "1+".repeat(100))).is_err());
        assert!(parse(&format!("{}1{}", "[".repeat(30), "]".repeat(30))).is_ok());
        assert!(parse(&format!("[{}0]", "0,".repeat(10_000))).is_err());
    }
}
