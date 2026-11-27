"""Observe native player deaths and horizontal contacts through the public MCP."""
import json
from typing import NamedTuple
import time


class PlayerState(NamedTuple):
    x: float
    z: float
    step_z: float
    death_screen: bool
    dead: bool


def exercise_player_events(work, log, send, hit, handler, inspect, rpc, codex_env=None):
    def control_file(name):
        path = work / name
        path.touch()
        deadline = time.monotonic() + 4
        while path.exists():
            if time.monotonic() > deadline:
                raise TimeoutError(name)
            time.sleep(.02)

    def pose():
        control_file("inspect-player")
        x, z, dz, screen, dead = (work / "player-state").read_text().split()
        return PlayerState(float(x), float(z), float(dz), screen == "true", dead == "true")

    def listen(names, *inputs, ticks=None, image=None):
        lines = ["CLEAR"]
        for event in names:
            lines.extend(handler(event, "PAUSE"))
        result = send([*lines, *inputs], ticks, image or "player-event", bool(image))
        listeners = result.get("pause_listeners", [])
        for origin in listeners:
            assert lines[origin["line"] - 2] == f'ON({origin["event"]})', result
        assert result["pause_reason"] == ("pause" if listeners else "end"), result
        return [item["event"] for item in listeners]

    axes = ["CollideZ", "CollideX"]
    def room():
        hit("collision-room")
        send(["CLEAR", "KeyRelease(w) KeyRelease(d) KeyRelease(s)", "Wait(25)"], 26)
        state = pose()
        assert abs(state.x - .5) < .01 and abs(state.z - .5) < .01, state

    room()
    assert listen(axes, "Wait(3)", ticks=3) == []
    assert listen(axes, "KeyPress(w)", "Wait(2)", ticks=3) == []
    assert listen(axes, "Wait(32)", image="collision-z") == ["CollideZ"]
    state = pose()
    assert abs(state.x - .5) < .01 and 1.69 < state.z < 1.71, state
    assert state.step_z > .0001, state  # First contact still allowed a partial step.
    assert inspect()["world"] == "paused"
    # A rejected candidate tick must preserve the current contact edge as well as held W.
    bad = rpc("tools/call", {"name": "input_script", "arguments": {
        "script": "BEGIN\nCLEAR\nLET $n = 0\nWait($n)\nEND", "include_screenshot": False}})
    assert bad.get("isError") and json.loads(bad["content"][0]["text"])["ticks"] == 0
    hit("slide-then-push")
    assert listen(axes, "Wait(5)", ticks=5) == []
    assert listen(axes, "KeyRelease(w) KeyPress(s)", "Wait(4)", ticks=5) == []
    assert pose().z < state.z - .1
    assert listen(axes, "KeyRelease(s) KeyPress(w)", "Wait(32)") == ["CollideZ"]

    room()
    assert listen(axes, "KeyPress(d)", "Wait(3)", ticks=4) == []
    assert listen(axes, "KeyPress(w)", "Wait(32)") == ["CollideX"]
    state = pose()
    assert -.71 < state.x < -.69 and .5 < state.z < 1.69, state
    assert listen(axes, "Wait(32)") == ["CollideZ"]
    assert pose().z > state.z

    room()
    assert listen(axes, "KeyPress(d) KeyPress(w)", "Wait(32)", image="collision-corner") == axes
    state = pose()
    assert -.71 < state.x < -.69 and 1.69 < state.z < 1.71, state
    send(["CLEAR", "KeyRelease(w) KeyRelease(d)"], 1)

    if codex_env is not None:
        from codex_mouse import exercise_collision_codex
        room()
        exercise_collision_codex(work, codex_env)
        send(["CLEAR", "KeyRelease(w)"], 1)

    hit("lethal-totem")
    assert listen(["Death"], "Wait(12)", ticks=12) == []
    assert pose()[3:] == (False, False)
    for mode in ["immediate", "screen"]:
        hit(f"lethal-{mode}")
        assert listen(["Death"], "Wait(45)", image=f"death-{mode}") == ["Death"]
        if mode == "screen":
            assert pose()[3:] == (True, True)
            control_file("request-respawn")
        send(["CLEAR", "Wait(20)"], 20)
        resumed = pose()
        assert resumed[3:] == (False, False), (mode, resumed)
    for mode, dead in [("totem", "false"), ("immediate", "true"), ("screen", "true")]:
        assert f"AGENT_LETHAL lethal-{mode} dead={dead} accepted=true offhandEmpty=true" in log.read_text()
    print("PASS: Death with both respawn rules and totem, axis contact edges, sliding, recontact and corner order.")
