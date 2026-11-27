"""Exercise input functions through the MCP in the disposable single-player world."""
from textwrap import dedent
import time

from pause_binding import observer_input


def exercise_functions(work, log, play, rpc, block, inspect):
    def script(source, image=False):
        return {"script": dedent(source).strip(), "include_screenshot": image}

    def run(name, source, ticks, reason="end", image=False):
        result = play(script(source, image), ticks, name)
        assert result["pause_reason"] == reason, result
        return result

    def send(*lines, ticks):
        result = play(dict(block(*lines), include_screenshot=False), ticks, "function-status")
        assert result["pause_reason"] == "end", result

    def hit(mode):
        (work / "test-hit").write_text(mode)

    def queue_state():
        reply = rpc("tools/call", {"name": "inspect_input", "arguments": {"queue": "inline", "limit": 100}})
        assert not reply.get("isError"), reply
        return reply["structuredContent"]

    run("function-paused-at-end", """
        FUNCTION $hold
        BEGIN
            PAUSE
        END
        BEGIN
            $hold()
        END
    """, 0, "pause")
    state = inspect()
    assert state["queue"]["total"] == 0, state
    frame, = state["active_calls"]
    assert frame["next"] == "END" and frame["last_line"] == 3, frame
    call, = frame["call_stack"]
    assert call["name"] == "hold" and call["line"] == 6, call
    send("Wait(1)", ticks=1)
    assert not inspect().get("active_calls")

    clear_owner = """
        BEGIN
            ON(HpLost)
            BEGIN
                CLEAR
                {after_clear}
            END
            PAUSE
            Wait(20)
        END
    """
    for after_clear, ticks in [("RETURN", 1), ("Key(s)", 2)]:
        run("function-clear-owner", clear_owner.format(after_clear=after_clear), 0, "pause")
        hit("client-drop")
        send("Wait(20)", ticks=ticks)
        state = inspect()
        assert state["queue"]["total"] == 0, state
        if after_clear == "Key(s)":
            assert state["held"]["keys"] == {"s": "temporary"}, state

    watch = """
        FUNCTION $watch
        BEGIN
            ON(Hurt)
            BEGIN
                RETURN
            END
            Wait(20)
        END
        BEGIN
            $watch()
            Key(s)
        END
    """
    for damage in (False, True):
        offset = len(log.read_text())
        if damage:
            hit("micro")
        result = run("function-hurt" if damage else "function-timeout", watch, None if damage else 21)
        if damage:
            assert "AGENT_HIT micro true" in log.read_text()[offset:], result
            assert 1 < result["ticks"] < 21, result
        state = inspect()
        assert state["held"]["keys"] == {"s": "temporary"}, state
        assert state["listeners"] == [] and state["queue"]["total"] == 0, state
    hit("restore")
    send("Wait(4)", ticks=4)

    # The inner listener is defined first, but the outer listener registers first.
    hit("client-drop")
    run("function-listener-order", """
        FUNCTION $inner
        BEGIN
            ON(HpLost)
            BEGIN
                ScreenShot("inner-should-not-run.png")
                PAUSE
            END
            Wait(20)
        END
        FUNCTION $outer
        BEGIN
            ON(HpLost)
            BEGIN
                RETURN
            END
            $inner()
            KeyPress(b)
        END
        BEGIN
            $outer()
            Key(s)
        END
    """, 2)
    assert not (work / "inner-should-not-run.png").exists()
    state = inspect()
    assert state["held"]["keys"] == {"s": "temporary"}, state
    assert state["listeners"] == [] and state["queue"]["total"] == 0, state

    hit("client-drop")
    result = run("function-handler-stop", """
        FUNCTION $helper
        BEGIN
            ScreenShot("function-handler-file.png") PAUSE
            RETURN
            KeyPress(q)
        END
        FUNCTION $watch
        BEGIN
            ON(HpLost)
            BEGIN
                $helper()
                KeyPress(j)
                RETURN
            END
            Wait(20)
        END
        BEGIN
            $watch()
            Key(s)
        END
    """, 1, "pause", image=True)
    assert (work / "function-handler-file.png").read_bytes() == (work / "function-handler-stop.png").read_bytes()
    state = queue_state()
    origin, = result["pause_listeners"]
    assert origin["event"] == "HpLost" and origin["line"] == 9, origin
    assert state["listeners"] == [dict(origin, state="handling")], state
    pending = next(row for row in state["queue"]["entries"] if row["line"] == 4 and row["source"] == "RETURN")
    stack = pending["call_stack"]
    assert [(frame["name"], frame["line"]) for frame in stack] == [("watch", 18), ("helper", 11)], pending
    assert len({frame["id"] for frame in stack}) == 2 and origin["call_stack"] == stack[:1], pending
    send("Wait(1)", "Key(k)", ticks=2)
    state = inspect()
    assert state["held"]["keys"] == {"j": "persistent", "k": "temporary", "s": "temporary"}, state
    assert state["listeners"] == [] and state["queue"]["total"] == 0, state
    send("KeyRelease(j)", ticks=1)

    shared = """
        FUNCTION $shared
        BEGIN
            PAUSE
            Key({body})
            RETURN
            KeyPress({skipped})
        END
        BEGIN
            $shared()
            Key({after})
        END
    """
    run("function-old-definition", shared.format(body="j", skipped="q", after="s"), 0, "pause")
    run("function-new-definition", shared.format(body="k", skipped="b", after="w"), 0, "pause")
    rows = {row["source"]: row for row in queue_state()["queue"]["entries"]}
    old, new = rows["Key(j)"], rows["Key(k)"]
    assert old["block"] != new["block"], rows
    old_frame, = old["call_stack"]
    new_frame, = new["call_stack"]
    assert old_frame["name"] == new_frame["name"] == "shared", rows
    assert old_frame["line"] == new_frame["line"] == 9 and old_frame["id"] != new_frame["id"], rows
    send("Wait(1)", ticks=1)
    assert inspect()["held"]["keys"] == {"j": "temporary", "k": "temporary"}
    send("Wait(1)", ticks=1)
    state = inspect()
    assert state["held"]["keys"] == {"s": "temporary", "w": "temporary"}, state
    assert state["queue"]["total"] == 0, state

    run("function-old-top-level", """
        BEGIN
            PAUSE
            RETURN
            KeyPress(q)
        END
    """, 0, "pause")
    state = inspect()
    first = state["queue"]["entries"][0]
    assert first["source"] == "RETURN" and first["line"] == 3, state
    send("CLEAR_LISTENERS", ticks=0)
    assert inspect()["queue"] == state["queue"]
    send("Key(j)", "Key(k)", ticks=2)
    state = inspect()
    assert state["held"]["keys"] == {"k": "temporary"} and state["queue"]["total"] == 0, state
    send("RETURN", "KeyPress(q)", ticks=0)
    assert inspect()["held"]["keys"] == {"k": "temporary"}

    run("function-before-error", """
        BEGIN
            KeyPress(j) PAUSE
            Key(k)
        END
    """, 1, "pause")
    before = inspect()
    recursion = """
        FUNCTION $recurse
        BEGIN
            $recurse()
        END
        BEGIN
            {before_call}
            $recurse()
            KeyPress(q)
        END
    """
    screenshot_error = script(recursion.format(before_call='ScreenShot("absent-dir/error.png")'))
    combined = rpc("tools/call", {"name": "input_script", "arguments": screenshot_error})
    assert combined.get("isError"), combined
    diagnostic = combined["content"][0]["text"]
    assert "$recurse" in diagnostic and "line 3" in diagnostic and "absent-dir" in diagnostic, diagnostic
    failed = rpc("tools/call", {"name": "input_script", "arguments": script(
        recursion.format(before_call="// Enter immediately."))})
    assert failed.get("isError"), failed
    text = failed["content"][0]["text"]
    assert "$recurse" in text and "line 3" in text, text
    state = inspect()
    assert state["world"] == "paused" and state["pause_reason"] == "error", state
    assert "$recurse" in state["last_error"] and "line 3" in state["last_error"], state
    assert state["queue"] == before["queue"] and state["held"] == before["held"], state
    send("KeyRelease(j) Key(b)", ticks=1)
    state = inspect()
    assert state["held"]["keys"] == {"b": "temporary", "k": "temporary"}, state
    assert state.get("last_error") is None and state["queue"]["total"] == 0, state

    run("function-manual-error", recursion.format(before_call="PAUSE"), 0, "pause")
    observer_input(work, "press f8")
    observer_input(work, "release f8")
    deadline = time.monotonic() + 5
    while True:
        state = inspect()
        if state["world"] == "paused" and state["pause_reason"] == "error":
            break
        assert time.monotonic() < deadline, state
        time.sleep(.05)
    assert "$recurse" in state["last_error"] and "line 3" in state["last_error"], state
    assert state["queue"]["total"] == 0 and "q" not in state["held"]["keys"], state
    send("Wait(1)", ticks=1)
    assert inspect().get("last_error") is None
    print("PASS: function timeout, Hurt return, listener order, helper stop frame, same-name overlap and depth errors.")
