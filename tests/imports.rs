use minecraft_input::{FunctionControl, Script, ScriptSource};
use std::{
    fs, io,
    path::{Path, PathBuf},
    sync::atomic::{AtomicUsize, Ordering},
};

struct Sources(PathBuf);

impl Sources {
    fn new() -> Self {
        static NEXT_ID: AtomicUsize = AtomicUsize::new(0);
        loop {
            let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
            let path = std::env::temp_dir().join(format!(
                "minecraft-input-imports-{}-{id}",
                std::process::id()
            ));
            match fs::create_dir(&path) {
                Ok(()) => return Self(path),
                Err(error) if error.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(error) => panic!("{error}"),
            }
        }
    }

    fn write(&self, path: &str, source: &str) {
        let path = self.0.join(path);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, source).unwrap();
    }

    fn inline(&self, source: &str) -> io::Result<Script> {
        ScriptSource::Inline(source).load(&self.0)
    }

    fn file(&self, path: &str) -> io::Result<Script> {
        ScriptSource::Path(Path::new(path)).load(&self.0)
    }
}

impl Drop for Sources {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[test]
fn inline_import_uses_its_base_and_parses_json_path_before_its_comment() {
    let files = Sources::new();
    files.write(
        "lib/quoted path.input",
        "FUNCTION $fromLibrary\nBEGIN\n$fromRoot()\nEND",
    );
    let script = files
        .inline(
            r#"  IMPORT "lib//quoted\u0020path.input" // shared inputs
FUNCTION $fromRoot
BEGIN
Key(w)
END
BEGIN
$fromLibrary()
END"#,
        )
        .unwrap();
    let program = script.compile(Default::default());
    assert_eq!(program.runs.len(), 1);
    assert_eq!(program.runs[0].line, 7);
    assert_eq!(program.runs[0].ticks, 0);
    assert!(matches!(
        &program.runs[0].control,
        Some(FunctionControl::Call(name)) if name == "fromLibrary"
    ));
    assert!(matches!(
        &program.functions["fromLibrary"][0].control,
        Some(FunctionControl::Call(name)) if name == "fromRoot"
    ));
    assert!(
        program.functions["fromRoot"][0]
            .key_actions
            .contains_key("w")
    );
}

#[test]
fn parse_inline_import_accepts_absolute_paths_and_resolves_relative_paths_from_cwd() {
    let files = Sources::new();
    files.write("lib.input", "FUNCTION $absolute\nBEGIN\nEND");
    let absolute = serde_json::to_string(&files.0.join("lib.input")).unwrap();
    let script = Script::parse(&format!(
        "IMPORT {absolute}\nIMPORT \"tests/walk.input\"\nBEGIN\n$absolute()\nEND"
    ))
    .unwrap();
    let program = script.compile(Default::default());
    assert_eq!(program.functions.len(), 1);
    assert_eq!(program.runs.len(), 1);
}

#[test]
fn each_file_resolves_its_imports_and_a_shared_dependency_registers_once() {
    let files = Sources::new();
    files.write(
        "tasks/root.input",
        "IMPORT \"../lib/walk.input\"\nIMPORT \"../lib/sub/mine.input\"\nBEGIN\n$walk()\nEND",
    );
    files.write(
        "lib/walk.input",
        "IMPORT \"common.input\"\nFUNCTION $walk\nBEGIN\n$shared()\nEND",
    );
    files.write(
        "lib/sub/mine.input",
        "IMPORT \".././common.input\"\nFUNCTION $mine\nBEGIN\n$shared()\nEND",
    );
    files.write("lib/common.input", "FUNCTION $shared\nBEGIN\nKey(a)\nEND");
    let program = files
        .file("tasks/root.input")
        .unwrap()
        .compile(Default::default());
    assert_eq!(
        program
            .functions
            .keys()
            .map(String::as_str)
            .collect::<Vec<_>>(),
        ["mine", "shared", "walk"],
    );
    assert!(program.functions["shared"][0].key_actions.contains_key("a"));
}

#[cfg(unix)]
#[test]
fn a_symlinked_source_uses_its_real_directory_and_registers_only_once() {
    let files = Sources::new();
    files.write(
        "lib/actual.input",
        "IMPORT \"common.input\"\nFUNCTION $actual\nBEGIN\n$common()\nEND",
    );
    files.write("lib/common.input", "FUNCTION $common\nBEGIN\nEND");
    std::os::unix::fs::symlink("lib/actual.input", files.0.join("alias.input")).unwrap();
    let program = files
        .inline("IMPORT \"alias.input\"\nIMPORT \"lib/actual.input\"\nBEGIN\n$actual()\nEND")
        .unwrap()
        .compile(Default::default());
    assert_eq!(program.functions.len(), 2);
    assert!(program.functions.contains_key("common"));
}

#[test]
fn imported_entry_does_not_contribute_operations_to_the_root_program() {
    let files = Sources::new();
    files.write(
        "demo.input",
        "FUNCTION $demo\nBEGIN\nKey(w)\nEND\nBEGIN\nCLEAR\nON(Hurt)\nBEGIN\n$demo()\nEND\nKey(a)\nPAUSE\nEND",
    );
    let library = files
        .file("demo.input")
        .unwrap()
        .compile(Default::default());
    assert!(library.runs.iter().any(|run| run.clear));
    assert!(library.runs.iter().any(|run| run.handler.is_some()));
    assert!(library.runs.iter().any(|run| run.pause));
    let imported = files
        .inline("IMPORT \"demo.input\"\nBEGIN\nKey(d)\nEND")
        .unwrap()
        .compile(Default::default());
    assert_eq!(imported.runs.len(), 1);
    assert!(imported.runs[0].key_actions.contains_key("d"));
    assert!(imported.functions["demo"][0].key_actions.contains_key("w"));
}

#[test]
fn imported_scripts_record_key_locations_in_all_bodies() {
    let files = Sources::new();
    files.write(
        "library.input",
        "FUNCTION $demo\nBEGIN\nKey(w)\nEND\nBEGIN\nON(Hurt)\nBEGIN\nKeyRelease(s)\nEND\nKey(a)\nEND",
    );
    let script = files
        .inline("IMPORT \"library.input\"\nBEGIN\nKey(d)\nEND")
        .unwrap();
    let library = files.0.join("library.input").canonicalize().unwrap();
    let library = library.to_str().unwrap();
    for (key, source, line) in [
        ("w", library, 3),
        ("s", library, 8),
        ("a", library, 10),
        ("d", "<inline>", 3),
    ] {
        assert_eq!(script.key_location(key), Some((source, line)));
    }
    assert_eq!(script.key_location("x"), None);
    let no_imports = files.file("library.input").unwrap();
    assert_eq!(no_imports.key_location("w"), None);
}

#[test]
fn root_path_requires_an_entry_even_when_a_library_has_one() {
    let files = Sources::new();
    files.write("root.input", "IMPORT \"demo.input\"\n");
    files.write("demo.input", "BEGIN\nEND");
    let error = files.file("root.input").unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    let message = error.to_string();
    assert!(message.contains("root.input: line 2"), "{message}");
    assert!(message.contains("missing BEGIN"), "{message}");
}

#[test]
fn duplicate_root_and_library_definitions_report_both_sites() {
    let files = Sources::new();
    files.write(
        "library.input",
        "// shared routines\nFUNCTION $duplicate\nBEGIN\nEND",
    );
    let error = files
        .inline("IMPORT \"library.input\"\n\nFUNCTION $duplicate\nBEGIN\nEND\nBEGIN\nEND")
        .unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    let message = error.to_string();
    assert!(message.contains("$duplicate"), "{message}");
    assert!(message.contains("<inline>: line 3"), "{message}");
    assert!(message.contains("library.input: line 2"), "{message}");
}

#[test]
fn duplicate_unused_definitions_in_different_libraries_report_both_sites() {
    let files = Sources::new();
    files.write("first.input", "FUNCTION $duplicate\nBEGIN\nEND");
    files.write("second.input", "\nFUNCTION $duplicate\nBEGIN\nEND");
    let error = files
        .inline("IMPORT \"first.input\"\nIMPORT \"second.input\"\nBEGIN\nEND")
        .unwrap_err();
    let message = error.to_string();
    assert!(message.contains("$duplicate"), "{message}");
    assert!(message.contains("first.input: line 1"), "{message}");
    assert!(message.contains("second.input: line 2"), "{message}");
}

#[test]
fn cycle_diagnostic_lists_the_chain_from_the_root_through_the_repeated_file() {
    let files = Sources::new();
    files.write(
        "tasks/root.input",
        "IMPORT \"../lib/first.input\"\nBEGIN\nEND",
    );
    files.write("lib/first.input", "IMPORT \"second.input\"");
    files.write("lib/second.input", "\nIMPORT \"./first.input\"");
    let error = files.file("tasks/root.input").unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::InvalidData);
    let message = error.to_string();
    assert!(message.contains("second.input: line 2"), "{message}");
    let mut chain = message.as_str();
    for file in ["root.input", "first.input", "second.input", "first.input"] {
        chain = chain
            .split_once(file)
            .unwrap_or_else(|| panic!("{message}"))
            .1;
    }
}

#[test]
fn invalid_library_rows_and_references_are_rejected_with_their_own_line() {
    let files = Sources::new();
    for (source, line, detail) in [
        ("BEGIN\nBogus(w)\nEND", 2, "Bogus"),
        (
            "BEGIN\nON(Hurt)\nBEGIN\n$missing()\nEND\nEND",
            4,
            "$missing",
        ),
        ("FUNCTION $unused\nBEGIN\n$missing()\nEND", 3, "$missing"),
    ] {
        files.write("library.input", source);
        let error = files
            .inline("IMPORT \"library.input\"\nBEGIN\nEND")
            .unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        let message = error.to_string();
        assert!(
            message.contains(&format!("library.input: line {line}")),
            "{message}"
        );
        assert!(message.contains(detail), "{message}");
    }
}

#[test]
fn import_requires_a_nonempty_json_string_and_must_precede_all_blocks() {
    let files = Sources::new();
    files.write("library.input", "BEGIN\nEND");
    for (source, line) in [
        ("IMPORT library.input\nBEGIN\nEND", 1),
        ("IMPORT \"\"\nBEGIN\nEND", 1),
        (r#"IMPORT "lib\u0000.input""#, 1),
        (r#"IMPORT "lib\q.input""#, 1),
        ("IMPORT \"library.input\" PAUSE\nBEGIN\nEND", 1),
        (
            "FUNCTION $f\nBEGIN\nEND\nIMPORT \"library.input\"\nBEGIN\nEND",
            4,
        ),
        ("BEGIN\nIMPORT \"library.input\"\nEND", 2),
        ("BEGIN\nEND\nIMPORT \"library.input\"", 3),
    ] {
        let error = files.inline(source).unwrap_err();
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        let message = error.to_string();
        assert!(
            message.contains(&format!("<inline>: line {line}")),
            "{message}"
        );
    }
}

#[test]
fn failed_import_reports_declaration_and_target() {
    let files = Sources::new();
    files.write("root.input", "\nIMPORT \"missing.input\"\nBEGIN\nEND");
    let error = files.file("root.input").unwrap_err();
    assert_eq!(error.kind(), io::ErrorKind::NotFound);
    let message = error.to_string();
    assert!(message.contains("root.input: line 2"), "{message}");
    assert!(message.contains("missing.input"), "{message}");
}

#[test]
fn loading_again_reads_new_definitions_without_affecting_a_loaded_script() {
    let files = Sources::new();
    let root = "IMPORT \"library.input\"\nBEGIN\n$action()\nEND";
    files.write("library.input", "FUNCTION $action\nBEGIN\nKey(a)\nEND");
    let first = files.inline(root).unwrap();
    files.write("library.input", "FUNCTION $action\nBEGIN\nKey(d)\nEND");
    let second = files.inline(root).unwrap();
    let first = first.compile(Default::default());
    let second = second.compile(Default::default());
    assert!(first.functions["action"][0].key_actions.contains_key("a"));
    assert!(second.functions["action"][0].key_actions.contains_key("d"));
}

#[test]
fn ordinary_inline_load_errors_keep_the_line_label() {
    let files = Sources::new();
    let error = files.inline("BEGIN\nWait(0)\nEND").unwrap_err().to_string();
    assert!(error.contains("line 2:"), "{error}");
}

#[test]
fn a_parse_error_in_an_imported_source_reports_the_file_and_line() {
    let files = Sources::new();
    files.write("invalid.input", "BEGIN\nWait(0)\nEND");
    let path = serde_json::to_string(&files.0.join("invalid.input")).unwrap();
    let error = Script::parse(&format!("IMPORT {path}\nBEGIN\nEND")).unwrap_err();
    assert_eq!(error.line, 2);
    let message = error.to_string();
    assert!(message.contains("invalid.input"), "{message}");
    assert_eq!(message.matches("line 2:").count(), 1, "{message}");
}

#[cfg(unix)]
#[test]
fn root_and_import_reject_nonregular_files() {
    let files = Sources::new();
    let root_error = files.file("/dev/null").unwrap_err();
    assert_eq!(root_error.kind(), io::ErrorKind::InvalidInput);
    let message = root_error.to_string();
    assert!(
        message.contains("/dev/null") && message.contains("regular file"),
        "{message}"
    );

    let import_error = files
        .inline("IMPORT \"/dev/null\"\nBEGIN\nEND")
        .unwrap_err();
    assert_eq!(import_error.kind(), io::ErrorKind::InvalidInput);
    let message = import_error.to_string();
    assert!(message.contains("<inline>: line 1"), "{message}");
    assert!(
        message.contains("/dev/null") && message.contains("regular file"),
        "{message}"
    );
}
