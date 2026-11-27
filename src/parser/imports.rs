use super::{
    FunctionControl, ParseError, Row, Script, ScriptSource, block_rows, declaration, expect_begin,
    function_name, parse_block, parse_error, quoted_path,
};
use std::{
    collections::BTreeMap,
    error::Error,
    fmt, fs, io,
    io::Read,
    path::{Path, PathBuf},
};

const INLINE_SOURCE: &str = "<inline>";
pub(super) const IMPORT_POSITION: &str = "IMPORT must appear before any FUNCTION or BEGIN";

#[derive(Debug)]
pub(super) struct LoadError {
    source: String,
    line: Option<usize>,
    message: String,
    kind: io::ErrorKind,
}

impl LoadError {
    fn syntax(source: &str, error: ParseError) -> Self {
        Self {
            source: source.into(),
            line: Some(error.line),
            message: error.message,
            kind: io::ErrorKind::InvalidData,
        }
    }

    fn file(path: &Path, imported_from: Option<(&str, usize)>, error: io::Error) -> Self {
        let (source, line, message) = match imported_from {
            Some((source, line)) => (
                source.to_owned(),
                Some(line),
                format!("could not load import {}: {error}", path.display()),
            ),
            None => (
                path.display().to_string(),
                None,
                format!("could not load script: {error}"),
            ),
        };
        Self {
            source,
            line,
            message,
            kind: error.kind(),
        }
    }
}

impl fmt::Display for LoadError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.source)?;
        if let Some(line) = self.line {
            write!(f, ": line {line}")?;
        }
        write!(f, ": {}", self.message)
    }
}

impl Error for LoadError {}

impl From<LoadError> for io::Error {
    fn from(error: LoadError) -> Self {
        io::Error::new(error.kind, error)
    }
}

impl From<LoadError> for ParseError {
    fn from(error: LoadError) -> Self {
        let line = error.line.unwrap_or(1);
        let message = if error.source == INLINE_SOURCE {
            error.message
        } else {
            format!("{}: {}", error.source, error.message)
        };
        parse_error(line, message)
    }
}

struct Import {
    line: usize,
    path: PathBuf,
}

struct Definition {
    line: usize,
    body: Vec<Row>,
}

struct SourceFile {
    name: String,
    path: Option<PathBuf>,
    imports: Vec<Import>,
    functions: BTreeMap<String, Definition>,
    entry: Vec<Row>,
}

impl SourceFile {
    fn parse(text: &str, path: Option<PathBuf>, root: bool) -> Result<Self, LoadError> {
        let name = path
            .as_ref()
            .map_or_else(|| INLINE_SOURCE.to_owned(), |p| p.display().to_string());
        let mut file = Self {
            name,
            path,
            imports: Vec::new(),
            functions: BTreeMap::new(),
            entry: Vec::new(),
        };
        file.parse_body(text, root)
            .map_err(|error| LoadError::syntax(&file.name, error))?;
        Ok(file)
    }

    fn parse_body(&mut self, text: &str, root: bool) -> Result<(), ParseError> {
        let eof = text.lines().count() + 1;
        let mut source = text.lines().enumerate().filter_map(|(index, raw)| {
            let raw = raw.trim();
            if raw.starts_with("//") {
                return None;
            }
            let code = if raw.starts_with("ScreenShot(") || declaration(raw, "IMPORT").is_some() {
                raw
            } else {
                raw.split_once("//")
                    .map_or(raw, |(code, _)| code)
                    .trim_end()
            };
            Some((index + 1, code))
        });
        while let Some((line, code)) = source.next() {
            if code.is_empty() {
                continue;
            }
            if code == "BEGIN" {
                self.entry = parse_block(&mut source, eof, true)?;
                if let Some((line, _)) = source.find(|(_, code)| !code.is_empty()) {
                    return Err(parse_error(line, "unexpected input after END"));
                }
                return Ok(());
            }
            if let Some(arguments) = declaration(code, "IMPORT") {
                if !self.functions.is_empty() {
                    return Err(parse_error(line, IMPORT_POSITION));
                }
                let (path, rest) = quoted_path(arguments)
                    .ok_or_else(|| parse_error(line, "IMPORT expects a quoted file path"))?;
                if !rest.is_empty() && !rest.trim_start().starts_with("//") {
                    return Err(parse_error(line, "IMPORT must be on its own line"));
                }
                self.imports.push(Import {
                    line,
                    path: PathBuf::from(path),
                });
                continue;
            }
            let signature =
                declaration(code, "FUNCTION").ok_or_else(|| parse_error(line, "expected BEGIN"))?;
            let name =
                function_name(signature.trim()).map_err(|message| parse_error(line, message))?;
            if let Some(first) = self.functions.get(name) {
                return Err(duplicate_error(line, name, &self.name, first.line));
            }
            expect_begin(&mut source, eof, "FUNCTION")?;
            let body = parse_block(&mut source, eof, true)?;
            self.functions
                .insert(name.to_owned(), Definition { line, body });
        }
        if root {
            return Err(parse_error(eof, "missing BEGIN"));
        }
        Ok(())
    }

    fn rows(&self) -> impl Iterator<Item = &Row> {
        self.functions
            .values()
            .map(|definition| definition.body.as_slice())
            .chain(std::iter::once(self.entry.as_slice()))
            .flat_map(block_rows)
    }
}

fn duplicate_error(line: usize, name: &str, first_source: &str, first_line: usize) -> ParseError {
    parse_error(
        line,
        format!("duplicate function ${name} (first defined at {first_source}: line {first_line})"),
    )
}

enum Visit {
    Active,
    Complete,
}

fn read_script(path: &Path) -> io::Result<String> {
    let mut options = fs::OpenOptions::new();
    options.read(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        // Opening a named pipe must finish before we can check its file type.
        options.custom_flags(libc::O_NONBLOCK | libc::O_NOCTTY);
    }
    let mut file = options.open(path)?;
    if !file.metadata()?.is_file() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "expected a regular file",
        ));
    }
    let mut text = String::new();
    file.read_to_string(&mut text)?;
    Ok(text)
}

pub(super) fn load(source: ScriptSource<'_>, base: &Path) -> Result<Script, LoadError> {
    let root = match source {
        ScriptSource::Inline(text) => SourceFile::parse(text, None, true)?,
        ScriptSource::Path(path) => {
            let requested = base.join(path);
            let path = requested
                .canonicalize()
                .map_err(|error| LoadError::file(&requested, None, error))?;
            let text = read_script(&path).map_err(|error| LoadError::file(&path, None, error))?;
            SourceFile::parse(&text, Some(path), true)?
        }
    };

    // Walk the dependency graph without using the call stack. Active files form
    // the current import chain, while completed files can be shared by siblings.
    let mut visited = BTreeMap::new();
    if let Some(path) = &root.path {
        visited.insert(path.clone(), Visit::Active);
    }
    let mut files = vec![root];
    let mut stack = vec![(0, 0)];
    while let Some((index, next_import)) = stack.last_mut() {
        let file = &files[*index];
        let Some(import) = file.imports.get(*next_import) else {
            if let Some(path) = &file.path {
                visited.insert(path.clone(), Visit::Complete);
            }
            stack.pop();
            continue;
        };
        *next_import += 1;

        let directory = file.path.as_deref().and_then(Path::parent).unwrap_or(base);
        let requested = directory.join(&import.path);
        let from = Some((file.name.as_str(), import.line));
        let path = requested
            .canonicalize()
            .map_err(|error| LoadError::file(&requested, from, error))?;
        match visited.get(&path) {
            Some(Visit::Complete) => continue,
            Some(Visit::Active) => {
                let repeat = path.display().to_string();
                let chain = stack
                    .iter()
                    .map(|(index, _)| files[*index].name.as_str())
                    .chain(std::iter::once(repeat.as_str()))
                    .collect::<Vec<_>>()
                    .join(" -> ");
                return Err(LoadError::syntax(
                    &file.name,
                    parse_error(import.line, format!("circular import: {chain}")),
                ));
            }
            None => (),
        }
        let text = read_script(&path).map_err(|error| LoadError::file(&requested, from, error))?;
        visited.insert(path.clone(), Visit::Active);
        stack.push((files.len(), 0));
        files.push(SourceFile::parse(&text, Some(path), false)?);
    }
    assemble(files)
}

fn assemble(files: Vec<SourceFile>) -> Result<Script, LoadError> {
    let uses_imports = !files[0].imports.is_empty();
    let mut defined = BTreeMap::new();
    for file in &files {
        for (name, definition) in &file.functions {
            if let Some((first_source, first_line)) =
                defined.insert(name.as_str(), (file.name.as_str(), definition.line))
            {
                return Err(LoadError::syntax(
                    &file.name,
                    duplicate_error(definition.line, name, first_source, first_line),
                ));
            }
        }
    }

    let mut import_keys = BTreeMap::new();
    for file in &files {
        let missing = file
            .rows()
            .filter_map(|row| match &row.control {
                Some(FunctionControl::Call(name)) if !defined.contains_key(name.as_str()) => {
                    Some((row.line, name))
                }
                _ => None,
            })
            .min_by_key(|(line, _)| *line);
        if let Some((line, name)) = missing {
            return Err(LoadError::syntax(
                &file.name,
                parse_error(line, format!("undefined function ${name}")),
            ));
        }
        if uses_imports {
            for row in file.rows() {
                for key in row.keys.keys() {
                    import_keys
                        .entry(key.clone())
                        .or_insert_with(|| (file.name.clone(), row.line));
                }
            }
        }
    }

    let mut files = files.into_iter();
    let root = files.next().unwrap();
    let functions = root
        .functions
        .into_iter()
        .chain(files.flat_map(|file| file.functions))
        .map(|(name, definition)| (name, definition.body))
        .collect();
    Ok(Script {
        rows: root.entry,
        functions,
        import_keys,
    })
}
