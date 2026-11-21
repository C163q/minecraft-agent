use minecraft_input::{Keyboard, Script};
use serde_json::json;
use std::collections::BTreeSet;

#[test]
fn mixed_script_compiles_movements_and_barriers_in_order() {
    let script = Script::parse(
        "BEGIN\n\
         MouseMoveTo(Px(908), Px(365), Px(10), Px(10)) MousePress(left) KeyPress(w)\n\
         MouseMove(Percentage(-8.5), Rotate(1.25), Px(80)) MouseMove(Px(0), Px(6))\n\
         WaitMouseMove() MouseRelease(left) KeyRelease(w)\n\
         CLEAR\nEND",
    )
    .unwrap();
    let program = script.compile(BTreeSet::new());
    assert_eq!(
        program
            .runs
            .iter()
            .map(|r| (r.line, r.ticks))
            .collect::<Vec<_>>(),
        [(2, 1), (3, 1), (4, 0), (4, 1), (5, 0)]
    );
    let runs = &program.runs;
    assert_eq!(runs[0].buttons["left"], "press");
    assert_eq!(
        runs[0].moves[0],
        json!({"absolute":true,"args":[
            {"unit":"px","value":908.0},{"unit":"px","value":365.0},
            {"unit":"px","value":10.0},{"unit":"px","value":10.0}
        ]})
    );
    assert_eq!(runs[1].moves.len(), 2);
    assert_eq!(
        runs[1].moves[0],
        json!({"absolute":false,"args":[
            {"unit":"percentage","value":-8.5},{"unit":"rotate","value":1.25},
            {"unit":"px","value":80.0}
        ]})
    );
    assert!(runs[2].wait_mouse && runs[2].held.contains("w") && runs[2].buttons.is_empty());
    assert_eq!(runs[3].buttons["left"], "release");
    assert!(runs[3].held.is_empty());
    assert!(runs[4].clear && !runs[4].wait_mouse);
    assert!(program.persistent.is_empty());
}

#[test]
fn mouse_only_line_advances_the_keyboard_clock() {
    let script = Script::parse(
        "BEGIN\nKey(w)\nMouse(left)\nWait(4294967296) Mouse(middle) MousePress(right)\nEND",
    )
    .unwrap();
    let frames: Vec<_> = Keyboard::default().play(&script).take(3).collect();
    assert_eq!(frames[1].released, BTreeSet::from(["w".to_owned()]));
    assert_eq!(frames[1].line, 3);
    let runs = script.compile(BTreeSet::new()).runs;
    assert_eq!(runs[2].ticks, 4294967296);
}

#[test]
fn button_handoff_and_keyboard_names_are_independent() {
    let script = Script::parse("BEGIN\nMouse(left) KeyRelease(left) MousePress(left) Mouse(middle) Mouse(right)\nMousePress(left) Mouse(left)\nEND").unwrap();
    let runs = script.compile(BTreeSet::new()).runs;
    assert_eq!(runs[0].buttons["left"], "press");
    assert_eq!(runs[0].buttons["right"], "temporary");
    assert_eq!(runs[0].buttons["middle"], "temporary");
    assert_eq!(runs[1].buttons["left"], "press");
}

#[test]
fn invalid_mouse_commands_report_the_original_line() {
    for input in [
        "Mouse(unknown)",
        "MousePress(left) MouseRelease(left)",
        "MouseRelease(right) Mouse(right)",
        "WaitMouse()",
        "WaitMouseMove(2)",
        "Mouse(left) WaitMouseMove()",
        "Wait(2) WaitMouseMove()",
        "MouseMove(Px(10),Px(20)",
        "MouseMove(Px(1),Px(2)))",
        "MouseMove(Px(0) Px(1))",
        "MouseMove(1,2)",
        "MouseMove(Px(0),)",
        "MouseMove(Px(0),Pc(3))",
        "MouseMove(Px(1))",
        "MouseMove(Px(0),Px(1),Px(2),Px(3),Px(4))",
        "MouseMove(Px(NaN),Px(0))",
        "MouseMove(Px(inf),Rotate(0))",
        "MouseMove(Px(1e999),Px(1))",
        "MouseMove(Px(1),Px(0),Px(0))",
        "MouseMove(Px(1),Px(0),Px(0),Px(1))",
        "MouseMove(Px(1),Px(0),Px(1),Px(-1))",
        "CLEAR Mouse(left)",
        "Mouse(left)Mouse(right)",
    ] {
        let text = format!("BEGIN\nKey(w)\n{input}\nEND");
        assert_eq!(Script::parse(&text).expect_err(input).line, 3, "{input}");
    }
    assert!(Script::parse("BEGIN\nMouseMove(Px(-100), Px(0), Px(10), Px(0))\nEND").is_ok());
}
