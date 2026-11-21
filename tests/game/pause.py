"""Pause boundaries, overlapping script blocks and a manually resumed 26.3 world."""
import json
import re
import time

from pause_binding import assert_saved_pause_key, observer_input


def exercise_pause(work, log, play, rpc, block, submit, receive):
    def inspect(**args):
        reply = rpc("tools/call", {"name": "inspect_input", "arguments": args})
        assert not reply.get("isError"), reply
        return reply["structuredContent"]

    def rows(state):
        return [row["source"] for row in state["queue"]["entries"]]

    def stopped(lines, ticks, name, reason="end"):
        result = play(block(*lines), ticks, name)
        assert result["pause_reason"] == reason, result

    def wait_state(reason):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            state = inspect()
            if reason is None:
                if state["world"] == "unpaused" and state["pause_reason"] is None:
                    return state
            elif state["world"] == "paused" and state["pause_reason"] == reason:
                return state
            time.sleep(.05)
        raise AssertionError((reason, state))

    stopped(["Key(a) Key(w)", "Key(s) PAUSE", "Key(d)", "Key(q)"], 2, "pause-mid", "pause")
    state = inspect()
    assert state["held"]["keys"] == {"s": "temporary"} and rows(state) == ["Key(d)", "Key(q)"], state
    entries = state["queue"]["entries"]
    assert entries[0]["block"] == entries[1]["block"] and [r["line"] for r in entries] == [4, 5]
    for invalid in ["CLEAR", "KeyPress(w) KeyRelease(w)"]:
        # An unrecognized key late in an otherwise valid block must leave the existing stop intact.
        rejected = rpc("tools/call", {"name": "input_script", "arguments": block(invalid, "Key(no_such_key)")})
        assert rejected.get("isError") and inspect() == state, rejected
    offset = len(log.read_text())
    stopped(["Key(space)", "Wait(1)", "Key(b)"], 3, "pause-merge")
    native = log.read_text()[offset:]
    assert re.findall(r"AGENT_SAMPLE ([01]{3})", native) == ["010", "000", "000"]
    assert set(re.findall(r"AGENT_KEY (\d+) 1", native)) == {"7", "44", "20", "5"}, native[-1600:]

    # A new short block returns at its END while the older source still has a row.
    stopped(["PAUSE", "Key(w)", "Key(d) PAUSE", "Key(q)"], 0, "pause-before", "pause")
    stopped(["KeyRelease(w)"], 1, "pause-short")
    assert rows(inspect()) == ["Key(d)", "PAUSE", "Key(q)"]
    stopped(["Key(space)", "Key(b)"], 1, "pause-old-stop", "pause")
    assert sorted(rows(inspect())) == ["Key(b)", "Key(q)"]
    stopped(["CLEAR", "Wait(1)"], 1, "pause-cleared")
    assert inspect()["queue"]["total"] == 0

    stopped(["KeyPress(w) MousePress(middle) PAUSE", "Key(w) Mouse(middle) Key(d)", "Key(q)"],
            1, "pause-held", "pause")
    offset = len(log.read_text())
    stopped(["KeyRelease(w) MouseRelease(middle)"], 1, "pause-override")
    state = inspect()
    assert state["held"] == {"keys": {"d": "temporary"}, "buttons": {}} and rows(state) == ["Key(q)"]
    native = log.read_text()[offset:]
    assert re.findall(r"AGENT_KEY 26 ([01])", native) == ["0"]
    assert re.findall(r"AGENT_MOUSE 2 ([01])", native) == ["0"]

    # An idle barrier can resolve at zero ticks; a blocked barrier allows a newer block to run.
    stopped(["CLEAR", "MouseMove(Px(150), Px(0), Px(50)) PAUSE", "WaitMouseMove()", "KeyPress(d)"],
            1, "pause-barrier", "pause")
    stopped(["Key(j)"], 1, "pause-parallel-wait")
    state = inspect()
    assert state["held"]["keys"] == {"j": "temporary"} and state["moves"][0]["remaining_px"]["x"] == 50
    stopped(["Wait(2)"], 2, "pause-complete-barrier")
    assert inspect()["held"]["keys"] == {"d": "persistent"}
    stopped(["CLEAR", "KeyRelease(d)"], 1, "pause-clear-hold")

    # One pinned page survives replacement of the live queue; no expansion of a u64 wait.
    large = ["Key(b)"] * 24 + ["Wait(18446744073709551615)"]
    stopped(["PAUSE", *large], 0, "pause-export", "pause")
    short = inspect()
    assert short["queue"]["total"] == 25 and len(rows(short)) == 5
    first = inspect(queue="inline", limit=3)
    saved = inspect(queue="file", path="queue-snapshot.json")
    assert saved["queue"]["total"] == 25 and "entries" not in saved["queue"]
    assert saved["queue"]["path"] == str(work / "queue-snapshot.json")
    contents = json.loads((work / "queue-snapshot.json").read_text())
    assert len(rows(contents)) == 25 and contents["queue"]["entries"][-1]["remaining_ticks"] == "18446744073709551615"
    stopped(["CLEAR"], 0, "pause-clear-before-tick")
    next_page = inspect(queue="inline", cursor=first["queue"]["next_cursor"], limit=3)
    assert [r["line"] for r in next_page["queue"]["entries"]] == [6, 7, 8] and next_page["queue"]["total"] == 25

    # A key that was synthetically held at the stop must not prevent a physical key edge.
    stopped(["Key(w) KeyPress(f8) Key(a)", "Wait(1)", "Key(d)"], 1, "pause-synthetic", "hotkey")
    assert inspect()["held"]["keys"]["f8"] == "persistent"
    observer_input(work, "press f8")
    state = wait_state("end")
    assert state["held"]["keys"] == {"d": "temporary", "f8": "persistent"}, state
    observer_input(work, "repeat f8")
    observer_input(work, "release f8")
    assert inspect()["pause_reason"] == "end"
    stopped(["KeyRelease(f8) MouseMove(Px(80), Px(0), Px(10)) Mouse(middle)"], 1, "pause-before-free")
    observer_input(work, "press f8")
    observer_input(work, "release f8")
    wait_state(None)
    deadline = time.monotonic() + 3
    while True:
        state = inspect()
        if state["moves"] == [] and state["held"] == {"buttons": {}, "keys": {}}:
            break
        assert time.monotonic() < deadline, state
        time.sleep(.05)
    observer_input(work, "press f8")
    observer_input(work, "release f8")
    wait_state("hotkey")

    # Binding through the controls UI changes which native key stops the interpreter.
    observer_input(work, "bind f8")
    assert_saved_pause_key(work, "f8")
    assert inspect()["pause_reason"] == "hotkey"
    observer_input(work, "bind f9")
    assert_saved_pause_key(work, "f9")
    stopped(["Key(f8)", "Key(f9) Key(d)", "Wait(1)"], 2, "pause-rebound", "hotkey")
    assert rows(inspect()) == ["Wait(1)"]
    observer_input(work, "press f9")
    observer_input(work, "release f9")
    wait_state("end")

    stopped(["Key(e)", "Key(f9)", "Wait(1)"], 2, "pause-inventory", "hotkey")
    observer_input(work, "press f9")
    observer_input(work, "release f9")
    wait_state("end")
    stopped(["Key(escape)", "Wait(1)"], 2, "pause-close-inventory")

    offset = len(log.read_text())
    request = submit("tools/call", {"name":"input_script", "arguments":block("KeyPress(w)", "Wait(4294967296)", "KeyRelease(w)")})
    deadline = time.monotonic() + 5
    while "AGENT_KEY 26 1" not in log.read_text()[offset:]:
        assert time.monotonic() < deadline, "long script never started"
        time.sleep(.02)
    observer_input(work, "press f9")
    observer_input(work, "release f9")
    reply = receive(request)
    assert not reply.get("isError"), reply["content"][0]
    status = json.loads(reply["content"][0]["text"])
    assert status["pause_reason"] == "hotkey" and status["world"] == "paused"
    state = inspect()
    remaining = int(state["queue"]["entries"][0]["remaining_ticks"])
    assert 0 < remaining <= 4294967296 and status["ticks"] + remaining == 4294967297
    stopped(["CLEAR", "KeyRelease(w)"], 1, "pause-release")
    observer_input(work, "bind f8")
    (work / "close-before-fence").touch()
    stopped(["Wait(4)", "Key(escape) PAUSE", "Key(d)"], 5, "pause-menu-race", "pause")
    assert not (work / "close-before-fence").exists()
    assert rows(inspect()) == ["Key(d)"]
    result = play(block("CLEAR", "Wait(3)", "Key(escape) PAUSE", "Key(d)"), 4,
                  "pause-native-menu", world="not_needed")
    assert result["pause_reason"] == "pause"
    observer_input(work, "press escape")
    observer_input(work, "release escape")
    assert rows(wait_state("pause")) == ["Key(d)"]
    stopped(["CLEAR", "Wait(1)"], 1, "pause-after-menu")

    play(block("Key(escape)"), 1, "pause-native-end", world="not_needed")
    observer_input(work, "press escape")
    observer_input(work, "release escape")
    wait_state(None)
    stopped([], 0, "pause-back-in-world")
    print("PASS: overlay, zero-tick PAUSE/CLEAR, old and new END, pinned pages/export, rebound physical and scripted pause, manual free play.")
