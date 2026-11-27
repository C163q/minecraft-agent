use minecraft_input::{Keyboard, Script, ScriptSource};
use std::{collections::BTreeSet, path::Path};

fn keys(names: &[&str]) -> BTreeSet<String> {
    names.iter().map(|name| name.to_string()).collect()
}

#[test]
fn waits_expand_before_the_entire_chord() {
    let script = Script::parse("BEGIN\nWait(1)\nWait(2) Key(w) Key(a)\nEND\n").unwrap();
    let frames: Vec<_> = Keyboard::default().play(&script).collect();
    assert_eq!(frames.len(), 4);
    assert!(frames[..3].iter().all(|frame| frame.held.is_empty()));
    assert_eq!(frames[3].pressed, keys(&["w", "a"]));
    assert_eq!(frames[3].held, frames[3].pressed);
    assert_eq!(
        frames.iter().map(|f| f.line).collect::<Vec<_>>(),
        [2, 3, 3, 3]
    );
}

#[test]
fn comments_skip_ticks_but_blank_lines_consume_them() {
    let script = Script::parse(
        "BEGIN // entry\r\nKey(w)\r\n// skip\r\n  // skip\r\n\t// skip\r\nKey(w) // hold\r\n \t\r\n\r\nEND\r\n",
    )
    .unwrap();
    let f: Vec<_> = Keyboard::default().play(&script).collect();
    assert_eq!(f.len(), 4);
    assert_eq!(f[0].pressed, keys(&["w"]));
    assert_eq!(f[1].held, keys(&["w"]));
    assert!(f[1].pressed.is_empty() && f[1].released.is_empty());
    assert_eq!(f[2].released, keys(&["w"]));
    assert_eq!(
        f.iter().map(|frame| frame.line).collect::<Vec<_>>(),
        [2, 6, 7, 8]
    );
    assert!(f[3].held.is_empty() && f[3].released.is_empty());
}

#[test]
fn explicit_hold_lasts_six_ticks() {
    let script = Script::parse("BEGIN\nKeyPress(w)\nWait(5)\nKeyRelease(w)\nEND").unwrap();
    let f: Vec<_> = Keyboard::default().play(&script).collect();
    assert_eq!(f.len(), 7);
    assert_eq!(f[0].pressed, keys(&["w"]));
    assert!(
        f[1..6]
            .iter()
            .all(|f| f.held == keys(&["w"]) && f.pressed.is_empty() && f.released.is_empty())
    );
    assert!(f[6].held.is_empty());
    assert_eq!(f[6].released, keys(&["w"]));
}

#[test]
fn transfer_to_persistent_hold_across_blocks_has_no_edges() {
    let mut k = Keyboard::default();
    let first = Script::parse("BEGIN\nKey(w) Key(a)\nEND").unwrap();
    let second = Script::parse("BEGIN\nKeyPress(w)\nWait(1)\nKeyRelease(w)\nEND").unwrap();
    assert_eq!(k.play(&first).count(), 1);
    let f: Vec<_> = k.play(&second).collect();
    assert_eq!(f[0].pressed, keys(&[]));
    assert_eq!(f[0].released, keys(&["a"]));
    assert_eq!(f[0].held, keys(&["w"]));
    assert!(f[1].pressed.is_empty() && f[1].released.is_empty());
    assert_eq!(f[2].released, keys(&["w"]));
}

#[test]
fn very_long_wait_can_be_consumed_one_tick_at_a_time() {
    let s = Script::parse("BEGIN\nWait(4294967296) Key(w)\nEND").unwrap();
    assert!(
        Keyboard::default()
            .play(&s)
            .take(2)
            .all(|f| f.held.is_empty())
    );
}

#[test]
fn invalid_scripts_report_the_source_line() {
    for (body, line) in [
        ("Key(w)", 1),
        ("BEGIN\nEND\nKey(w)", 3),
        ("BEGIN\nKey(w)", 3),
        ("BEGIN\nKey(w)\nBogus(a)\nEND", 3),
        ("BEGIN\nBEGIN\nEND", 2),
        ("BEGIN\nWait(0)\nEND", 2),
        ("BEGIN\nWait(-1)\nEND", 2),
        ("BEGIN\nKey(w) Wait(2)\nEND", 2),
        ("BEGIN\nWait(1) Wait(2)\nEND", 2),
        ("BEGIN\nKeyPress(a) KeyRelease(a)\nEND", 2),
        ("BEGIN\nKey()\nEND", 2),
    ] {
        assert_eq!(Script::parse(body).unwrap_err().line, line, "{body:?}");
    }
}

#[test]
fn both_sources_produce_the_same_sequence() {
    let base = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests");
    let file = ScriptSource::Path(Path::new("walk.input"))
        .load(&base)
        .unwrap();
    let text = ScriptSource::Inline(include_str!("walk.input"))
        .load(&base)
        .unwrap();
    assert_eq!(
        Keyboard::default().play(&file).collect::<Vec<_>>(),
        Keyboard::default().play(&text).collect::<Vec<_>>()
    );
    assert!(
        ScriptSource::Path(Path::new("missing.input"))
            .load(&base)
            .is_err()
    );
}

#[test]
fn published_examples_match_the_documented_held_keys() {
    let references = [
        include_str!("../docs/input-script/timing.md"),
        include_str!("../docs/input-script/keyboard.md"),
    ];
    let examples: Vec<_> = references
        .iter()
        .flat_map(|page| page.split("```text\n").skip(1))
        .map(|example| example.split_once("\n```").unwrap().0)
        .collect();
    let expected: &[&[&[&str]]] = &[
        &[&["w"], &["w", "space"], &[], &[], &["a"]],
        &[&["w"], &[]],
        &[&["w"], &["w"], &["w"], &["w"], &["w"], &["w"], &["w"], &[]],
        &[&["a", "left_shift"], &["b"]],
        &[&["space"], &[]],
    ];
    assert_eq!(examples.len(), expected.len());
    for (text, ticks) in examples.into_iter().zip(expected) {
        let script = Script::parse(text).unwrap();
        let actual: Vec<_> = Keyboard::default()
            .play(&script)
            .map(|frame| frame.held)
            .collect();
        assert_eq!(
            actual,
            ticks.iter().map(|tick| keys(tick)).collect::<Vec<_>>()
        );
    }
}

#[test]
fn pause_after_wait_or_input_takes_no_additional_tick() {
    let script = Script::parse(
        "BEGIN\nPAUSE\nWait(5) PAUSE\nWait(1) KeyRelease(w) KeyPress(a) Key(d) PAUSE\nEND",
    )
    .unwrap();
    let program = script.compile(BTreeSet::new());
    assert_eq!(
        program
            .runs
            .iter()
            .map(|r| (r.line, r.ticks))
            .collect::<Vec<_>>(),
        [(2, 0), (3, 5), (3, 0), (4, 1), (4, 1), (4, 0)]
    );
    assert_eq!(Keyboard::default().play(&script).count(), 7);
}

#[test]
fn pause_must_end_its_line_and_clear_stands_alone() {
    for body in [
        "PAUSE Key(w)",
        "PAUSE PAUSE",
        "Key(w)PAUSE",
        "CLEAR PAUSE",
        "Pause",
        "PAUSE()",
    ] {
        assert_eq!(
            Script::parse(&format!("BEGIN\n{body}\nEND"))
                .unwrap_err()
                .line,
            2
        );
    }
}

#[test]
fn listener_registration_skips_body_and_preserves_key_lines() {
    let text = "BEGIN\nCLEAR_LISTENERS\nON(Hurt)\nBEGIN\nKeyRelease(s) PAUSE\nEND\nKey(w)\nEND";
    let file = std::env::temp_dir().join(format!(
        "minecraft-event-script-{}.input",
        std::process::id()
    ));
    std::fs::write(&file, text).unwrap();
    let script = ScriptSource::Path(&file).load(Path::new(".")).unwrap();
    std::fs::remove_file(file).unwrap();
    assert_eq!(Keyboard::default().play(&script).count(), 1);
    assert_eq!(script.compile(Default::default()).keys["s"], 5);
    for (bad, line) in [
        ("ON(Miss)\nBEGIN\nEND", 2),
        ("ON(Hurt)\nKey(a)", 3),
        ("ON(Hurt)\nBEGIN\nKeyPress(w) KeyRelease(w)\nEND", 4),
    ] {
        assert_eq!(
            Script::parse(&format!("BEGIN\n{bad}\nEND"))
                .unwrap_err()
                .line,
            line
        );
    }
}

#[test]
fn explicit_screenshot_is_zero_ticks_and_accepts_quoted_paths() {
    let script =
        Script::parse("BEGIN\nScreenShot(\"dir//hit (1).png\") PAUSE // stop\nEND").unwrap();
    assert_eq!(Keyboard::default().play(&script).count(), 0);
    assert!(Script::parse("BEGIN\nPAUSE ScreenShot(\"hit.png\")\nEND").is_err());
}

#[test]
fn player_events_compile_as_distinct_listeners() {
    for name in [
        "Death",
        "CollideX",
        "CollideZ",
        "BlockBroken",
        "LeftGround",
        "ScreenOpened",
        "DamageBlocked",
        "BlockInteracted",
    ] {
        let script = Script::parse(&format!(
            "BEGIN\nON({name})\nBEGIN\nPAUSE\nEND\nWait(4)\nEND"
        ))
        .unwrap();
        let program = script.compile(Default::default());
        assert_eq!(program.runs[0].handler.as_ref().unwrap().0, name);
        assert_eq!(program.runs[0].ticks, 0);
    }
}

#[test]
fn function_diagnostics_include_the_original_line() {
    for (source, line, detail) in [
        (
            "FUNCTION $check\nBEGIN\nEND\nFUNCTION $check\nBEGIN\nEND\nBEGIN\nEND",
            4,
            "$check",
        ),
        ("FUNCTION $1bad\nBEGIN\nEND\nBEGIN\nEND", 1, "$1bad"),
        (
            "FUNCTION $unused\nBEGIN\nON(Hurt)\nBEGIN\n$missing()\nEND\nEND\nBEGIN\nEND",
            5,
            "$missing",
        ),
        (
            "FUNCTION $unused\nBEGIN\nKey(w) KeyRelease(w)\nEND\nBEGIN\nEND",
            3,
            "conflicting actions",
        ),
        (
            "FUNCTION $unused\nBEGIN\nON(Hurt)\nBEGIN\nON(Hurt)\nBEGIN\nEND\nEND\nEND\nBEGIN\nEND",
            5,
            "ON",
        ),
        (
            "FUNCTION $Check\nBEGIN\nEND\nBEGIN\n$check()\nEND",
            5,
            "$check",
        ),
        ("BEGIN\n$check(1)\nEND", 2, "$check"),
        ("BEGIN\n$check() Key(w)\nEND", 2, "$check"),
        ("BEGIN\nRETURN PAUSE\nEND", 2, "RETURN"),
        ("BEGIN\nEND\nFUNCTION $check\nBEGIN\nEND", 3, "END"),
    ] {
        let error = Script::parse(source).unwrap_err();
        assert_eq!(error.line, line, "{source}");
        assert!(error.message.contains(detail), "{error}");
    }
}

#[test]
#[should_panic(expected = "requires game playback")]
fn fixed_tick_preview_explicitly_rejects_return() {
    let script = Script::parse("BEGIN\nRETURN\nKey(w)\nEND").unwrap();
    Keyboard::default().play(&script).count();
}

#[test]
fn fallible_fixed_tick_preview_rejects_function_scripts_without_advancing_keyboard() {
    let script =
        Script::parse("FUNCTION $noop\nBEGIN\nEND\nBEGIN\n$noop()\nKeyPress(a)\nEND").unwrap();
    let mut keyboard = Keyboard::default();
    assert!(matches!(
        keyboard.try_play(&script),
        Err(minecraft_input::PreviewError)
    ));
    let plain = Script::parse("BEGIN\nWait(1)\nEND").unwrap();
    assert!(
        keyboard
            .try_play(&plain)
            .unwrap()
            .next()
            .unwrap()
            .held
            .is_empty()
    );
}

#[test]
fn multiline_value_statements_keep_strings_comments_and_the_first_source_line() {
    let script = Script::parse(
        r#"BEGIN
LET $value = {
    message: "before // after", // only the suffix is a comment

    numbers: [1,
        -2]
}
ON(Hurt($value))
BEGIN
LET $copy = $value
END
Key(w)
END"#,
    )
    .unwrap();
    let program = script.compile(Default::default());
    assert_eq!(
        program
            .runs
            .iter()
            .map(|run| (run.line, run.ticks))
            .collect::<Vec<_>>(),
        [(2, 0), (8, 0), (12, 1)]
    );
    assert!(program.runs[0].source.contains("before // after"));
    assert!(Keyboard::default().try_play(&script).is_err());
}

#[test]
fn listener_names_resolve_at_registration_and_before_inner_declarations() {
    for (source, line, detail) in [
        ("BEGIN\nLET $x = $x\nEND", 2, "undeclared variable $x"),
        (
            "BEGIN\nLET $x = 1\nLET $x = 2\nEND",
            3,
            "duplicate variable declaration $x",
        ),
        (
            "BEGIN\nON(Hurt)\nBEGIN\nSET $x = 2\nEND\nLET $x = 1\nEND",
            4,
            "undeclared variable $x",
        ),
        (
            "FUNCTION $f($x)\nBEGIN\nLET $x = 2\nEND\nBEGIN\nEND",
            3,
            "duplicate variable declaration $x",
        ),
        (
            "FUNCTION $f\nBEGIN\nRETURN $x\nEND\nBEGIN\nLET $x = 1\nEND",
            3,
            "undeclared variable $x",
        ),
    ] {
        let error = Script::parse(source).unwrap_err();
        assert_eq!(error.line, line, "{error}");
        assert!(error.message.contains(detail), "{error}");
    }
    Script::parse("BEGIN\nLET $x = 1\nON(Hurt)\nBEGIN\nLET $x = $x\nSET $x = 2\nEND\nEND").unwrap();
}

#[test]
fn print_checks_literal_format_arity_and_row_boundaries() {
    for statement in [
        "Print($format)",
        "Print(\"{}\")",
        "Print(\"unused\", 1)",
        "Print(\"{name}\", 1)",
        "Print(\"unbalanced }\")",
        "Print(\"no argument follows\",)",
        "Print(\"okay\") PAUSE",
    ] {
        let error = Script::parse(&format!("BEGIN\n{statement}\nEND")).unwrap_err();
        assert_eq!(error.line, 2, "{statement}");
        assert!(error.message.contains("Print"), "{error}");
    }
    Script::parse(
        r#"BEGIN
Print(") // still the string \"] {{}} {} }}", "x")
END"#,
    )
    .unwrap();
}

#[test]
fn control_flow_bindings_are_lexical_even_in_unselected_branches() {
    let script = Script::parse(concat!(
        "BEGIN\nLET $item = [1]\nFOR $item IN $item\nBEGIN\n",
        "SET $item = $item + 1\nIF (false)\nBEGIN\n",
        "LET $item = $item + 1\nCONTINUE\nEND\n",
        "ELIF (true)\nBEGIN\nBREAK\nEND\nEND\n",
        "Print(\"{}\", $item[0])\nEND"
    ))
    .unwrap();
    assert!(Keyboard::default().try_play(&script).is_err());

    for (source, line, detail) in [
        (
            "BEGIN\nIF (true)\nBEGIN\nLET $local = 1\nEND\nELIF ($local)\nBEGIN\nEND\nEND",
            6,
            "$local",
        ),
        (
            "BEGIN\nIF (false)\nBEGIN\n$missing()\nEND\nEND",
            4,
            "$missing",
        ),
        (
            "BEGIN\nFOR $item IN [1]\nBEGIN\nLET $item = 2\nEND\nEND",
            4,
            "duplicate variable",
        ),
        ("BEGIN\nFOR $item IN $item\nBEGIN\nEND\nEND", 2, "$item"),
        (
            "BEGIN\nFOR $item IN [1]\nBEGIN\nEND\nPrint(\"{}\", $item)\nEND",
            5,
            "$item",
        ),
    ] {
        let error = Script::parse(source).unwrap_err();
        assert_eq!(error.line, line, "{source}");
        assert!(error.message.contains(detail), "{error}");
    }
}

#[test]
fn loop_jumps_and_listener_nesting_respect_execution_boundaries() {
    Script::parse(concat!(
        "BEGIN\nON(Hurt)\nBEGIN\nWHILE (true)\nBEGIN\n",
        "IF (true)\nBEGIN\nBREAK\nEND\nCONTINUE\nEND\nEND\nEND"
    ))
    .unwrap();
    for (source, line, detail) in [
        ("BEGIN\nBREAK\nEND", 2, "BREAK"),
        (
            "BEGIN\nIF (false)\nBEGIN\nCONTINUE\nEND\nEND",
            4,
            "CONTINUE",
        ),
        (
            "FUNCTION $f\nBEGIN\nBREAK\nEND\nBEGIN\nWHILE (true)\nBEGIN\n$f()\nEND\nEND",
            3,
            "BREAK",
        ),
        (
            "BEGIN\nWHILE (true)\nBEGIN\nON(Hurt)\nBEGIN\nCONTINUE\nEND\nEND\nEND",
            6,
            "CONTINUE",
        ),
        (
            "BEGIN\nON(Hurt)\nBEGIN\nFOR $i IN [0]\nBEGIN\nON(HpLost)\nBEGIN\nEND\nEND\nEND\nEND",
            6,
            "ON",
        ),
    ] {
        let error = Script::parse(source).unwrap_err();
        assert_eq!(error.line, line, "{source}");
        assert!(error.message.contains(detail), "{error}");
    }
}

#[test]
fn malformed_branch_headers_report_their_own_line() {
    for (body, line, keyword) in [
        ("ELIF (true)\nBEGIN\nEND", 2, "ELIF"),
        ("ELSE\nBEGIN\nEND", 2, "ELSE"),
        (
            "IF (true)\nBEGIN\nEND\nELSE\nBEGIN\nEND\nELSE\nBEGIN\nEND",
            8,
            "ELSE",
        ),
        (
            "IF (true)\nBEGIN\nEND\nELSE\nBEGIN\nEND\nELIF (true)\nBEGIN\nEND",
            8,
            "ELIF",
        ),
        ("IF (true) Key(w)\nBEGIN\nEND", 2, "IF"),
        ("WHILE true\nBEGIN\nEND", 2, "WHILE"),
        ("FOR $i IN\nBEGIN\nEND", 2, "FOR"),
    ] {
        let error = Script::parse(&format!("BEGIN\n{body}\nEND")).unwrap_err();
        assert_eq!(error.line, line, "{body}");
        assert!(error.message.contains(keyword), "{error}");
    }
}
