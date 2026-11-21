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
fn player_death_and_axis_collisions_compile_as_distinct_listeners() {
    for name in ["Death", "CollideX", "CollideZ"] {
        let script = Script::parse(&format!(
            "BEGIN\nON({name})\nBEGIN\nPAUSE\nEND\nWait(4)\nEND"
        ))
        .unwrap();
        let program = script.compile(Default::default());
        assert_eq!(program.runs[0].handler.as_ref().unwrap().0, name);
        assert_eq!(program.runs[0].ticks, 0);
    }
}
