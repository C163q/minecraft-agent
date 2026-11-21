"""Check actual event delivery, queue origins and explicit frame persistence over stdio."""
import json


def exercise_events(work, log, play, rpc, block, inspect):
    def send(lines, ticks=None, name="event-status", image=False):
        return play(dict(block(*lines), include_screenshot=image), ticks, name)

    def hit(mode):
        (work / "test-hit").write_text(mode)

    def handler(event, *rows):
        return [f"ON({event})", "BEGIN", *rows, "END"]

    def query(**arguments):
        result = rpc("tools/call", {"name": "inspect_input", "arguments": arguments})
        assert not result.get("isError"), result
        return result["structuredContent"]

    def origins(result):
        return [entry["event"] for entry in result.get("pause_listeners", [])]

    for first, reason in [(["Key(s)"], "end"), (["PAUSE"], "pause"), (["Key(s) PAUSE"], "end")]:
        send(["CLEAR"], 0)
        hit("client-drop")
        result = send([*handler("HpLost", *first), "Key(w)"], 1)
        assert result["pause_reason"] == reason, result
        assert origins(result) == (["HpLost"] if reason == "pause" else [])
        state = inspect()
        assert not state["listeners"] and "w" in state["held"]["keys"]
        if first != ["PAUSE"]:
            entry = state["queue"]["entries"][0]
            assert entry["listener"]["event"] == "HpLost" and entry["listener"]["line"] == 2
            resumed = send(["Wait(1)"], 1)
            assert resumed["pause_reason"] == ("pause" if first == ["Key(s) PAUSE"] else "end")
            assert "s" in inspect()["held"]["keys"]

    send(["CLEAR"], 0)
    hit("client-drop")
    result = send([*handler("HpLost", "KeyRelease(w) PAUSE", "Key(s)"), "Wait(5)"], 2)
    assert origins(result) == ["HpLost"]
    state = inspect()
    assert state["listeners"] == [dict(result["pause_listeners"][0], state="handling")]
    invalid = rpc("tools/call", {"name": "input_script", "arguments": block(
        "CLEAR", *handler("Hurt", "Key(no_such_key)"))})
    assert invalid.get("isError") and "line 5" in invalid["content"][0]["text"], invalid["content"][0]
    assert inspect() == state
    saved = query(queue="inline", limit=1)
    send(["CLEAR_LISTENERS"], 0)
    assert not inspect()["listeners"]
    old_page = query(queue="inline", cursor=saved["queue"]["next_cursor"])
    assert old_page["listeners"] == state["listeners"]
    query(queue="file", path="event-queue.json")
    snap = json.loads((work / "event-queue.json").read_text())
    assert snap["listeners"] == [] and any(r.get("listener") == result["pause_listeners"][0]
                                           for r in snap["queue"]["entries"])

    send(["CLEAR"], 0)
    both = [*handler("HpLost", "PAUSE"), *handler("HpLost", "PAUSE"), "Wait(4)"]
    hit("client-drop")
    result = send(both, 1)
    assert [o["line"] for o in result["pause_listeners"]] == [2, 6], result

    for mode, event, expected, server_result in [
            ("micro", "HpLost", True, "micro true"),
            ("gold", "Hurt", True, "gold true"),
            ("gold", "HpLost", False, "gold true"),
            ("shield", "Hurt", False, "shield false"),
            ("cooldown", "Hurt", True, "cooldown true")]:
        send(["CLEAR"], 0)
        hit(mode)
        response = send([*handler(event, "PAUSE"), "Wait(12)"])
        assert (origins(response) == [event]) == expected, (mode, response)
        assert "AGENT_HIT " + server_result in log.read_text(), mode
    assert "AGENT_REPEAT 1 false" in log.read_text() and "AGENT_REPEAT 2 true" in log.read_text()
    send(["CLEAR"], 0)
    for mode, expected in [("repeat-weak", False), ("repeat-strong", True)]:
        hit("prime")
        send(["Wait(3)"], 3)
        hit(mode)
        result = send([*handler("Hurt", "PAUSE"), "Wait(10)"])
        assert (origins(result) == ["Hurt"]) == expected, (mode, result)
        assert f"AGENT_HIT {mode} {str(expected).lower()}" in log.read_text()
        send(["CLEAR"], 0)
    hit("restore")
    send(["Wait(4)"], 4)

    result = send(['ScreenShot("explicit.png") PAUSE'], 0, "returned", True)
    assert result["pause_reason"] == "pause" and (work / "explicit.png").read_bytes() == (work / "returned.png").read_bytes()
    send(['ScreenShot("explicit-text.png") PAUSE'], 0)
    assert (work / "explicit-text.png").is_file()
    send(["PAUSE", 'ScreenShot("after-resume.png")', "Key(s) PAUSE"], 0)
    assert not (work / "after-resume.png").exists()
    state = send(["Wait(2)"], 1)
    assert state["pause_reason"] == "pause" and (work / "after-resume.png").is_file()
    assert inspect()["held"]["keys"] == {"s": "temporary"}
    send(["CLEAR"], 0)

    previous = (work / "explicit.png").read_bytes()
    failed = rpc("tools/call", {"name": "input_script", "arguments": block(
        'ScreenShot("explicit.png") PAUSE', "Key(d)")})
    assert failed.get("isError") and (work / "explicit.png").read_bytes() == previous
    assert [r["source"] for r in inspect()["queue"]["entries"]] == ["Key(d)"]
    send(["CLEAR"], 0)

    hit("respawn")
    response = send([*handler("HpLost", "PAUSE"), "Wait(20)"])
    assert response["pause_reason"] == "end" and not origins(response), response
    assert "AGENT_RESPAWN" in log.read_text()
    hit("change-level")
    moved = rpc("tools/call", {"name": "input_script", "arguments":
                dict(block(*handler("Hurt", "Key(s)"), "Wait(200)"), include_screenshot=False)})
    assert moved.get("isError") and "world changed" in moved["content"][0]["text"], moved
    fresh = inspect()
    assert not fresh["listeners"] and fresh["queue"]["total"] == 0, fresh
    print("PASS: event offsets, repeated observations, listener origins, golden hearts, shield, cooldown and explicit PNGs.")
