"""Exercise the per-call tick budget over the public MCP and native pause path."""
import re
import time

from pause_binding import observer_input


def exercise_max_ticks(work, log, play, rpc, block, inspect):
    # A real default-length wait takes approximately 30 seconds at 20 TPS.
    result = play(dict(block("Wait(601)"), include_screenshot=False), 600, "default-budget", timeout=75)
    assert result["pause_reason"] == "max_ticks", result
    assert inspect()["queue"]["entries"][0]["remaining_ticks"] == "1"

    result = play(dict(block("CLEAR", "Wait(3)"), max_ticks=3), 3, "exact-budget")
    assert result["pause_reason"] == "end"
    result = play(dict(block(), max_ticks=1), 0, "zero-tick-end")
    assert result["pause_reason"] == "end"

    # Explicit file capture at the budget boundary shares the returned frame.
    (work / "bounded.input").write_text(block("KeyPress(j) MousePress(middle)", "Wait(2)",
        'ScreenShot("bounded-file.png")', "Wait(2)", "KeyRelease(j) MouseRelease(middle)")["script"])
    result = play({"path": "bounded.input", "max_ticks": 3}, 3, "bounded-return")
    assert result["pause_reason"] == "max_ticks"
    assert (work / "bounded-return.png").read_bytes() == (work / "bounded-file.png").read_bytes()
    state = inspect()
    assert state["pause_reason"] == "max_ticks" and state["held"] == {
        "keys": {"j": "persistent"}, "buttons": {"middle": "persistent"}}, state
    assert state["queue"]["entries"][0]["remaining_ticks"] == "2", state
    offset = len(log.read_text())
    marker = work / "measure-world"
    marker.touch()
    try:
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            assert inspect() == state
            measurements = re.findall(r"AGENT_WORLD (true|false) (\d+)", log.read_text()[offset:])
            if len(measurements) >= 3:
                break
            time.sleep(.05)
        assert len(measurements) >= 3 and len(set(measurements)) == 1 and measurements[0][0] == "true", measurements
    finally:
        marker.unlink()
    invalid = rpc("tools/call", {"name": "input_script", "arguments": dict(block("CLEAR"), max_ticks=0)})
    assert invalid.get("isError") and "max_ticks" in invalid["content"][0]["text"] and inspect() == state

    result = play(dict(block("Wait(4) PAUSE"), max_ticks=3, include_screenshot=False), 3, "overlap")
    assert result["pause_reason"] == "max_ticks" and inspect()["held"] == {"keys": {}, "buttons": {}}
    result = play(dict(block("Wait(2)"), max_ticks=1), 1, "retained-pause")
    assert result["pause_reason"] == "pause"
    result = play(dict(block("CLEAR", "Key(f8)", "Wait(1)"), max_ticks=1), 1, "hotkey-priority")
    assert result["pause_reason"] == "hotkey"

    result = play(dict(block("CLEAR", "MouseMove(Px(160), Px(0), Px(80))", "WaitMouseMove()"),
                       max_ticks=2), 2, "bounded-movement")
    assert result["pause_reason"] == "max_ticks" and inspect()["moves"][0]["remaining_px"]["x"] == 60
    observer_input(work, "press f8")
    observer_input(work, "release f8")
    deadline = time.monotonic() + 4
    while inspect()["pause_reason"] != "end":
        if time.monotonic() > deadline:
            raise AssertionError(inspect())
        time.sleep(.05)
    assert not inspect()["moves"] and not inspect()["queue"]["total"]
    print("PASS: default and custom tick limits, server pause, same-frame PNG, overlap and F8 resume.")
