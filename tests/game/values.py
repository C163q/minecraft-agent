"""Exercise values across MCP submissions, actual input ticks, observations and stop frames."""
import json
from pathlib import Path
import time
from textwrap import dedent

from game_state import png_image
from pause_binding import observer_input


def exercise_values(work, log, play, rpc, block, inspect):
    def program(text, image=False):
        return {"script": dedent(text).strip(), "include_screenshot": image}

    def run(text, ticks, **options):
        return play(dict(program(text), **options), ticks, "values")

    def reject(arguments):
        result = rpc("tools/call", {"name": "input_script", "arguments": arguments})
        assert result.get("isError"), result["content"][0]
        return result["content"]

    values = run("""
        FUNCTION $copy($object, $delta)
        BEGIN
            SET $object.pos[0] = $object.pos[0] + $delta
            RETURN $object
        END
        BEGIN
            LET $original = {
                id: "minecraft:stone", // output keeps the full identifier
                pos: [1, 2, 3],
                "complex.key": "a//b"
            }
            LET $copy = $copy($original, 4)
            LET $large = 18446744073709551615 * 2
            Print("{{{}}} {}", $copy.id, $copy.pos)
            Wait(Floor(5 / 2))
            RETURN {copy: $copy, original: $original, large: $large, missing: $original.no.such}
        END
    """, 2)
    value = values["return_value"]
    assert value["original"]["pos"] == [1, 2, 3] and value["copy"]["pos"] == [5, 2, 3], values
    assert value["copy"]["complex.key"] == "a//b" and value["missing"] is None
    assert value["large"] == 36893488147419103230 and isinstance(value["large"], int)
    assert values["stdout"] == "{minecraft:stone} [5,2,3]\n", values
    assert run("BEGIN\nRETURN null\nEND", 0)["return_value"] is None
    assert "return_value" not in run("BEGIN\nRETURN\nEND", 0)

    waiting = run("""
        FUNCTION $later($n)
        BEGIN
            Print("once")
            Wait($n)
            RETURN 9
        END
        BEGIN
            LET $v = $later(5)
            Print("old {}", $v)
            RETURN $v
        END
    """, 2, max_ticks=2)
    assert waiting["pause_reason"] == "max_ticks" and waiting["stdout"] == "once\n"
    assert "return_value" not in waiting and inspect()["queue"]["entries"][0]["remaining_ticks"] == "3"
    resumed = run('BEGIN\nPrint("new")\nWait(4)\nRETURN 17\nEND', 4)
    assert resumed["stdout"] == "new\nold 9\n" and resumed["return_value"] == 17, resumed

    # One actual user input row, with button and key names coming from script values.
    held = run("""
        BEGIN
            LET $k = "j"
            LET $b = "middle"
            KeyPress($k) MousePress($b) MouseMove(Px(1 + 1), Px(0))
            WaitMouseMove()
            RETURN null
        END
    """, 1)
    assert held["return_value"] is None
    assert inspect()["held"] == {"keys": {"j": "persistent"}, "buttons": {"middle": "persistent"}}
    run('BEGIN\nLET $b = "middle"\nKeyRelease("j") MouseRelease($b)\nEND', 1)

    # A failing old submission must not consume the new submission's aligned tick.
    run('BEGIN\nLET $k = "j"\nPAUSE\nPrint("old before error")\nKeyPress($k) KeyRelease(j)\nEND', 0)
    before = inspect()
    for invalid in ('Wait(5 / 2)', 'Wait($undeclared)', 'Key("no_such_key")',
                    'LET $x = 1\nLET $x = 3', 'Print("{}")', 'Wait($user())'):
        reject(block("CLEAR", invalid))
        assert inspect() == before, invalid
    offset = len(log.read_text())
    blocks = reject(block("Key(b)", "Wait(1)"))
    state = json.loads(blocks[0]["text"])
    assert state["pause_reason"] == "error" and state["world"] == "paused" and state["ticks"] == 0, state
    assert state["stdout"] == "old before error\n" and state["script_error"]["line"] == 5, state
    assert png_image(blocks[1]).size == (state["width"], state["height"])
    assert "AGENT_KEY " not in log.read_text()[offset:]
    assert [row["source"] for row in inspect()["queue"]["entries"]] == ["Key(b)", "Wait(1)"]
    assert run("BEGIN\nWait(2)\nEND", 2)["pause_reason"] == "end"
    assert "AGENT_KEY 5 1" in log.read_text()[offset:]

    library = work / "values.mcinput"
    library.write_text('FUNCTION $fail($arg)\nBEGIN\nPrint("entered")\nRETURN $arg.wrong\nEND\n')
    result, = reject(program('IMPORT "values.mcinput"\nBEGIN\nLET $result = $fail(1)\nEND'))
    failed = json.loads(result["text"])
    assert failed["script_error"]["file"] == str(library) and failed["script_error"]["line"] == 4
    assert failed["script_error"]["call_stack"][-1]["name"] == "fail" and failed["stdout"] == "entered\n"
    library.write_text('BEGIN\nWait(Floor(0.5))\nEND\n')
    diagnostic, = reject(program('IMPORT "values.mcinput"\nBEGIN\nPrint("unreachable")\nEND'))
    assert str(library) in diagnostic["text"] and "line 2" in diagnostic["text"], diagnostic

    message, = reject(dict(block('Print("prior")', 'LET $zero = 0', 'ScreenShot("absent-values/result.png")',
                                 'LET $bad = 1 / $zero'), include_screenshot=True))
    combined = json.loads(message["text"])
    assert combined["pause_reason"] == "error" and combined["stdout"] == "prior\n" and combined["ticks"] == 0
    assert "division by zero" in combined["script_error"]["message"] and "absent-values" in combined["capture_error"]

    # Game-dependent coordinate conversion is part of preflight for dynamic inputs.
    run("BEGIN\nKeyPress(j) MousePress(middle) MouseMove(Px(90), Px(0), Px(3)) PAUSE\nWait(4)\nEND", 1)
    old = inspect()
    for move in ("MouseMoveTo(Px($x), Px(0))", "MouseMove(Percentage($x * 1e306), Px(0))",
                 "MouseMove(Px($x * 1e290), Px(0), Px(1))"):
        offset = len(log.read_text())
        error, = reject(program('BEGIN\nLET $x = 50\nPrint("starting")\nKeyPress(b) ' + move + '\nEND'))
        state = json.loads(error["text"])
        assert state["pause_reason"] == "error" and state["ticks"] == 0 and state["script_error"]["line"] == 4
        assert state["stdout"] == "starting\n" and "AGENT_KEY 5 1" not in log.read_text()[offset:]
        surviving = inspect()
        assert all(old[key] == surviving[key] for key in ("held", "moves", "queue"))
    run("BEGIN\nCLEAR\nKeyRelease(j) MouseRelease(middle)\nEND", 1)

    # Closing the inventory on the same tick must not turn an absolute cursor step into camera motion.
    from pause import mouse_state
    run("BEGIN\nKey(e)\nEND", 1)
    before = mouse_state(work, "view")[:2]
    run("BEGIN\nLET $x = 52\nKey(escape) MouseMoveTo(Percentage($x), Percentage(50))\nEND", 1)
    assert mouse_state(work, "view")[:2] == before
    run("BEGIN\nCLEAR\nWait(1)\nEND", 1)

    # A static motion in an older row can fail while an unrelated dynamic key is aligned with it.
    run("BEGIN\nPAUSE\nMouseMove(Px(1e290), Px(0), Px(1))\nEND", 0)
    owner = inspect()["queue"]["entries"][0]["block"]
    rejected, = reject(program('BEGIN\nLET $k = "j"\nKey($k)\nWait(1)\nEND'))
    assert json.loads(rejected["text"])["script_error"]["block"] == owner
    assert all(row["block"] != owner for row in inspect()["queue"]["entries"])
    run("BEGIN\nWait(2)\nEND", 2)

    (work / "test-hit").write_text("client-drop")
    result = run("""
        FUNCTION $watch($k)
        BEGIN
            ON(HpLost($hit))
            BEGIN
                LET $k = $k
                SET $k = "s"
                Print("inner {}", $k)
                RETURN {event: $hit.event, key: $k}
            END
            SET $k = "j"
            Wait(20)
        END
        BEGIN
            LET $k = "outer"
            LET $result = $watch("w")
            Print("outer {}", $k)
            RETURN $result
        END
    """, 1)
    assert result["return_value"] == {"event": "HpLost", "key": "s"} and result["events"][0]["event"] == "HpLost", result
    assert result["stdout"] == "inner s\nouter outer\n", result

    # A listener may mutate either the index or another element while the RHS is running.
    (work / "test-hit").write_text("client-drop")
    assigned = run("""
        FUNCTION $later
        BEGIN
            Wait(3)
            RETURN 9
        END
        BEGIN
            LET $items = [0, 0]
            LET $i = 0
            ON(HpLost)
            BEGIN
                SET $i = 1
                SET $items[1] = 8
                Wait(10)
            END
            SET $items[$i] = $later()
            RETURN $items
        END
    """, 3)
    assert assigned["return_value"] == [9, 8], assigned

    # The handler pauses on the boundary immediately before the first dynamic wait tick.
    (work / "test-hit").write_text("client-drop")
    boundary = run("""
        BEGIN
            LET $duration = 7
            ON(HpLost)
            BEGIN
                PAUSE
                SET $duration = 2
                Wait(10)
            END
            Wait(1)
            Wait($duration)
            Print("wait finished")
            RETURN
        END
    """, 1)
    assert boundary["pause_reason"] == "pause", boundary
    resumed = run("BEGIN\nWait(3)\nEND", 3)
    assert resumed["stdout"] == "wait finished\n", resumed

    run('BEGIN\nPAUSE\nPrint("manual")\nWait(1)\nRETURN 8\nEND', 0)
    observer_input(work, "press f8")
    observer_input(work, "release f8")
    deadline = time.monotonic() + 5
    while inspect()["pause_reason"] != "end":
        assert time.monotonic() < deadline, inspect()
        time.sleep(.04)
    fresh = run("BEGIN\nEND", 0)
    assert "stdout" not in fresh and "return_value" not in fresh
    # Run the user reference examples through the same public tool, including a native break notification.
    reference = (Path(__file__).resolve().parents[2] / "docs/input-script/values.md").read_text()
    step, copy, mine = (example.split("\n```", 1)[0] for example in reference.split("```text\n")[1:])
    example = run(step, 7)
    assert example["return_value"] == {"key": "w", "ticks": 7} and example["stdout"] == "walked for 7 input ticks\n"
    assert run(copy, 0)["return_value"] == [1, 4]
    observer_input(work, "action-fixture creative")
    run("BEGIN\nCLEAR\nWait(6)\nEND", 6)
    mined = run(mine, None)
    assert mined["return_value"] == "minecraft:stone" and mined["events"][0]["id"] == mined["return_value"], mined
    assert not inspect()["held"]["buttons"]
    print("PASS: expression results, event data, function values, scopes, stopped errors and per-request stdout.")
