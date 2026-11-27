"""Run control flow against the real MCP, input handlers, event queue and pause fence."""
import json
from pathlib import Path
import re
from textwrap import dedent
import time

from pause_binding import observer_input


def exercise_flow(work, log, play, rpc, inspect):
    def args(source, **options):
        return dict(script=dedent(source).strip(), **options)

    def run(source, ticks, image=None, **options):
        return play(args(source, include_screenshot=image is not None, **options),
                    ticks, image or "control-flow")

    def error(source):
        reply = rpc("tools/call", {"name": "input_script", "arguments": args(source, include_screenshot=False)})
        assert reply.get("isError"), reply["content"][0]
        return reply["content"][0]["text"]

    def hurt():
        (work / "test-hit").write_text("client-drop")

    run("BEGIN\nCLEAR\nWait(1)\nEND", 1)
    reference = (Path(__file__).resolve().parents[2] / "docs/input-script/control-flow.md").read_text()
    example = reference.split("```text\n", 1)[1].split("\n```", 1)[0]
    start = len(log.read_text())
    result = run(example, 2, "control-flow-keys")
    assert result["return_value"] == 5 and result["pause_reason"] == "end", result
    pressed = re.findall(r"AGENT_KEY (\d+) 1 ", log.read_text()[start:])
    assert pressed == ["26", "4"], pressed

    result = run("""
        BEGIN
            LET $obj = {"𐀀":3, "":2, a:1}
            FOR $name IN $obj
            BEGIN
                Print("{}={}", $name, $obj[$name])
                SET $obj["added"] = 9
                SET $obj["𐀀"] = 7
                SET $name = "local"
            END
            RETURN Len($obj)
        END
    """, 0)
    assert result["return_value"] == 4
    assert result["stdout"] == "a=1\n=2\n𐀀=7\n", result

    branched = run("""
        BEGIN
            LET $bad = 3
            IF (!false)
            BEGIN
                Print("selected")
            END

            ELIF ($bad)
            BEGIN
                Key(j)
            END

            ELSE
            BEGIN
                Key(k)
            END

            RETURN {different: false != 0, ordered: 1 + 2 <= 3, equal: {a:[1]} == {a:[1.0]}}
        END
    """, 1)
    assert branched["return_value"] == {"different": True, "ordered": True, "equal": True}
    assert branched["stdout"] == "selected\n"

    # Exercise a deeply nested, valid program end to end (both protocol decoders see it).
    nested_value = "{x:" * 47 + "1" + "}" * 47
    deep = "BEGIN\n" + "IF (true)\nBEGIN\n" * 30 + f"RETURN {nested_value}\n" + "END\n" * 31
    value = run(deep, 0)["return_value"]
    for _ in range(47):
        value = value["x"]
    assert value == 1

    # Selected branch and collection are stable while a paused call is overlapped with new input.
    partial = run("""
        BEGIN
            LET $keys = ["j", "k"]
            LET $enabled = true
            FOR $key IN $keys
            BEGIN
                IF ($enabled)
                BEGIN
                    SET $enabled = false
                    SET $keys[1] = "b"
                    Print("before {}", $key)
                    PAUSE
                    Print("after {}", $key)
                END
                ELSE
                BEGIN
                    Print("next {}", $key)
                END
            END
            RETURN $keys
        END
    """, 0)
    assert partial["stdout"] == "before j\n" and partial["pause_reason"] == "pause"
    state = inspect()
    assert state == inspect() and state["world"] == "paused"
    assert any("after" in entry["source"] for entry in state["queue"]["entries"]), state
    resumed = run("BEGIN\nWait(1)\nRETURN 8\nEND", 1)
    assert resumed["stdout"] == "after j\nnext k\n" and resumed["return_value"] == 8

    # Only the second iteration's registration survives until this pause.
    partial = run("""
        BEGIN
            FOR $i IN [1, 2]
            BEGIN
                ON(HpLost)
                BEGIN
                    Print("hit {}", $i)
                    PAUSE
                END
                IF ($i == 2)
                BEGIN
                    PAUSE
                END
                Wait(1)
            END
        END
    """, 1)
    assert partial["pause_reason"] == "pause" and len(inspect()["listeners"]) == 1
    before = inspect()
    for invalid in (
        "IF (false)\nBEGIN\nLET $v = !0\nEND",
        "IF (true)\nBEGIN\nEND\nELIF (1)\nBEGIN\nEND",
        "FOR $v IN null\nBEGIN\nEND",
        "WHILE (true)\nBEGIN\nON(Hurt)\nBEGIN\nBREAK\nEND\nEND",
        'IF (false)\nBEGIN\nKey(no_such_key)\nEND',
    ):
        diagnostic = error("BEGIN\nCLEAR\n" + invalid + "\nEND")
        assert "line" in diagnostic and inspect() == before, diagnostic
    library = work / "flow-preflight.mcinput"
    library.write_text("BEGIN\nIF (false)\nBEGIN\nWHILE (true)\nBEGIN\nWait(Len([]))\nEND\nEND\nEND")
    diagnostic = error('IMPORT "flow-preflight.mcinput"\nBEGIN\nCLEAR\nEND')
    assert str(library) in diagnostic and "line 6" in diagnostic and inspect() == before, diagnostic
    hurt()
    captured = run("BEGIN\nWait(3)\nEND", 1)
    assert captured["pause_reason"] == "pause" and captured["stdout"] == "hit 2\n", captured
    assert len(captured["pause_listeners"]) == 1 and len(captured["events"][0]["listeners"]) == 1
    run("BEGIN\nCLEAR\nEND", 0)

    for jump in ("BREAK", "CONTINUE"):
        hurt()
        interrupted = run("""
            BEGIN
                FOR $n IN [1]
                BEGIN
                    ON(HpLost)
                    BEGIN
                        Print("hit {}", $n)
                        PAUSE
                        Print("resumed {}", $n)
                    END
                    Wait(1)
                    IF (true)
                    BEGIN
                        Print("owner")
                        JUMP
                    END
                    Print("unreachable")
                END
                Print("outside")
            END
        """.replace("JUMP", jump), 1, "flow-owner-" + jump.lower())
        assert interrupted["pause_reason"] == "pause" and interrupted["stdout"] == "hit 1\nowner\n", interrupted
        assert interrupted["events"][0]["event"] == "HpLost" and inspect()["listeners"], interrupted
        resumed = run("BEGIN\nWait(1)\nEND", 0)
        assert resumed["stdout"] == "resumed 1\noutside\n" and not inspect()["listeners"], resumed

    hurt()
    first = run("""
        FUNCTION $watch
        BEGIN
            IF (true)
            BEGIN
                ON(HpLost)
                BEGIN
                    PAUSE
                    Print("resumed handler")
                END
                ON(HpLost)
                BEGIN
                    RETURN 7
                END
                Wait(1)
                Print("owner")
            END
            RETURN null
        END
        BEGIN
            LET $value = $watch()
            Print("owner returned {}", $value)
        END
    """, 1)
    assert first["pause_reason"] == "pause" and first["stdout"] == "owner\n", first
    assert len(inspect()["listeners"]) == 2
    resumed = run("BEGIN\nWait(1)\nEND", 0)
    assert resumed["stdout"] == "resumed handler\nowner returned 7\n", resumed
    run("BEGIN\nCLEAR\nEND", 0)

    hurt()
    result = run("""
        FUNCTION $child
        BEGIN
            Wait(12)
            RETURN "child"
        END
        FUNCTION $watch
        BEGIN
            WHILE (true)
            BEGIN
                ON(HpLost($hit))
                BEGIN
                    IF ($hit.event == "HpLost")
                    BEGIN
                        RETURN $hit.event
                    END
                END
                LET $unused = $child()
                Print("unreachable")
            END
        END
        BEGIN
            LET $result = $watch()
            RETURN $result
        END
    """, 1)
    assert result["return_value"] == "HpLost" and "stdout" not in result and not inspect()["listeners"]

    result = run("""
        BEGIN
            LET $n = 0
            WHILE ($n < 4096)
            BEGIN
                SET $n = $n + 1
            END
            RETURN $n
        END
    """, 0)
    assert result["return_value"] == 4096
    failure = json.loads(error('BEGIN\nPrint("prior")\nWHILE (true)\nBEGIN\nCONTINUE\nEND\nEND'))
    assert failure["script_error"]["line"] == 3 and failure["stdout"] == "prior\n" and failure["ticks"] == 0

    paused = run("""
        FUNCTION $one
        BEGIN
            Print("never")
        END
        BEGIN
            LET $n = 0
            WHILE ($n < 4096)
            BEGIN
                SET $n = $n + 1
            END
            PAUSE
            $one()
        END
    """, 0)
    assert paused["pause_reason"] == "pause"
    owner = inspect()["queue"]["entries"][0]["block"]
    over = json.loads(error("BEGIN\nWait(1)\nEND"))
    assert over["script_error"]["block"] == owner and over["ticks"] == 0 and "stdout" not in over
    assert any(entry["source"] == "Wait(1)" for entry in inspect()["queue"]["entries"])
    limited = run("BEGIN\nWHILE (true)\nBEGIN\nWait(1)\nEND\nEND", 3, max_ticks=3)
    assert limited["pause_reason"] == "max_ticks"
    run("BEGIN\nCLEAR\nEND", 0)

    hurt()
    exhausted = json.loads(error("""
        FUNCTION $one
        BEGIN
            Print("called")
        END
        BEGIN
            ON(HpLost)
            BEGIN
                LET $i = 0
                WHILE ($i < 4095)
                BEGIN
                    SET $i = $i + 1
                END
                Wait(1)
            END
            Wait(1)
            $one()
            $one()
        END
    """))
    assert exhausted["ticks"] == 1 and exhausted["stdout"] == "called\n", exhausted
    assert exhausted["script_error"]["line"] == 17 and exhausted["events"][0]["event"] == "HpLost", exhausted

    # Physical F8 continues the same iterator at the next element.
    run("BEGIN\nFOR $k IN [\"j\", \"b\"]\nBEGIN\nKey($k) PAUSE\nEND\nEND", 1)
    observer_input(work, "press f8")
    observer_input(work, "release f8")
    deadline = time.monotonic() + 5
    while inspect()["held"]["keys"].get("b") != "temporary":
        assert time.monotonic() < deadline, inspect()
        time.sleep(.04)
    run("BEGIN\nCLEAR\nWait(1)\nEND", 1)
    observer_input(work, "action-fixture door")
    run("BEGIN\nWait(6)\nEND", 6)
    opened = run("""
        BEGIN
            ON(BlockInteracted($hit))
            BEGIN
                IF ($hit.before.open == false)
                BEGIN
                    IF ($hit.after.open)
                    BEGIN
                        RETURN $hit.after.id
                    END
                END
            END
            Mouse(right)
            Wait(20)
        END
    """, None)
    assert opened["return_value"] == "minecraft:oak_door", opened
    assert opened["events"][0]["after"]["open"] is True and not inspect()["listeners"], opened
    print("PASS: control-flow input ticks, snapshots, event scope/return, F8 and shared zero-tick budget.")
