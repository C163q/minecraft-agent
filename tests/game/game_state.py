"""Check game observations through the production stdio MCP on Minecraft 26.3."""
import base64
import io
import json
import re
import time

from PIL import Image

from pause_binding import observer_input


TITLE_STATE = {
    "world": "not_needed", "screen": "TitleScreen", "player": None,
    "target": {"kind": "unavailable", "reason": "no_world"},
}


def png_image(content):
    assert content["type"] == "image" and content["mimeType"] == "image/png", content.keys()
    with Image.open(io.BytesIO(base64.b64decode(content["data"], validate=True))) as image:
        assert image.format == "PNG", image.format
        return image.convert("RGB")


def query_game(rpc, *, timeout=25, **arguments):
    reply = rpc("tools/call", {"name": "inspect_game", "arguments": arguments}, timeout)
    assert not reply.get("isError"), reply["content"][0]
    status, *images = reply["content"]
    assert status["type"] == "text", status
    state = json.loads(status["text"])
    assert reply["structuredContent"] == state, state
    if arguments.get("include_screenshot"):
        image, = images
        image = png_image(image)
        assert state["screenshot_id"] and image.size == (state["width"], state["height"]), state
        return state, image
    assert not images and {"screenshot_id", "width", "height"}.isdisjoint(state), state
    return state


def inspect_title(rpc, tool):
    schema = tool["inputSchema"]
    option = schema["properties"]["include_screenshot"]
    assert option["type"] == "boolean" and option["default"] is False, schema
    assert query_game(rpc) == TITLE_STATE


def exercise_game_exit(work, rpc):
    (work / "leave-world").touch()
    deadline = time.monotonic() + 8
    while not (work / "disconnect-ready").exists():
        if time.monotonic() >= deadline:
            raise TimeoutError("observer did not start the native disconnect")
        time.sleep(.01)
    state = None
    while (remaining := deadline - time.monotonic()) > 0:
        state = query_game(rpc, timeout=remaining)
        assert state["world"] == "not_needed" and state["player"] is None, state
        assert state["target"] == TITLE_STATE["target"], state
        if state == TITLE_STATE:
            print("PASS: the same MCP survived the native world exit and observed the title screen.")
            return
    raise AssertionError(("native disconnect did not reach the title screen", state))


def exercise_game(work, log, play, rpc, block, inspect_input):
    def text_only(*lines, ticks):
        return play(dict(block(*lines), include_screenshot=False), ticks, "inspect-game")

    observer_input(work, "inspect-fixture setup")
    target = {"kind": "block", "id": "minecraft:redstone_block",
              "position": {"x": 0, "y": 96, "z": 3}, "face": "north"}
    offset = len(log.read_text())
    marker = work / "measure-world"
    marker.touch()
    try:
        for _ in range(3):
            running = query_game(rpc)
            assert running["world"] == "unpaused" and running["screen"] is None, running
            assert running["target"] == target, running
            time.sleep(.1)
    finally:
        marker.unlink()
    measured = re.findall(r"AGENT_WORLD (true|false) (\d+)", log.read_text()[offset:])
    assert len(measured) >= 2 and all(pause == "false" for pause, _ in measured), measured
    assert int(measured[-1][1]) > int(measured[0][1]), measured

    assert text_only("Wait(1) PAUSE", "Wait(10)", ticks=1)["pause_reason"] == "pause"
    before = inspect_input()
    assert before["queue"]["total"] == 1, before
    observer_input(work, "inspect-fixture air")
    offset = len(log.read_text())
    marker.touch()
    try:
        state, image = query_game(rpc, include_screenshot=True)
        assert state["world"] == "paused" and state["screen"] is None and state["target"] == target, state
        expected_player = {
            "dimension": "minecraft:overworld", "position": {"x": .625, "y": 95.0, "z": .375},
            "rotation": {"yaw": 0.0, "pitch": 3.0},
            "main_hand": {"id": "minecraft:bread", "count": 3},
            "off_hand": {"id": "minecraft:torch", "count": 7},
            "health": {"current": 13.5, "max": 24.0, "absorption": 4.0},
            "food": 15, "air": {"current": 210, "max": 300},
        }
        assert state["player"] == expected_player, state
        cx, cy = (edge // 2 for edge in image.size)

        def red(dx, dy):
            r, g, b = image.getpixel((cx + dx, cy + dy))
            return r > 60 and r > 2 * g and r > 2 * b

        assert sum(red(dx, dy) for dx in (-12, 12) for dy in (-12, 12)) >= 3, "redstone off crosshair"
        reply = rpc("tools/call", {"name": "cursor_hint", "arguments": {
            "screenshot_id": state["screenshot_id"], "x": cx, "y": cy,
        }})
        assert not reply.get("isError"), reply["content"][0]
        note, hint = reply["content"]
        note = json.loads(note["text"])
        assert (note["x"], note["y"]) == (cx, cy) and note["rotation"] is not None, note
        marked = png_image(hint)
        assert marked.size == image.size and marked.getpixel((cx, cy)) == (255, 0, 0)

        observer_input(work, "inspect-fixture cow")
        cow = query_game(rpc, include_screenshot=False)
        entity_id = int((work / "inspect-cow-id").read_text())
        assert cow["target"] == {"kind": "entity", "type": "minecraft:cow", "entity_id": entity_id}, cow
        assert cow["player"]["rotation"] == {"yaw": -90.0, "pitch": 15.0}, cow
        observer_input(work, "inspect-fixture sky")
        miss = query_game(rpc)
        assert miss["target"] == {"kind": "miss"}, miss
        assert inspect_input() == before, before
    finally:
        marker.unlink()
    measured = re.findall(r"AGENT_WORLD (true|false) (\d+)", log.read_text()[offset:])
    assert len(measured) >= 2 and len(set(measured)) == 1 and measured[0][0] == "true", measured
    assert "AGENT_SAMPLE" not in log.read_text()[offset:], "inspect_game consumed a client input tick"

    text_only("CLEAR", "Key(e)", "Wait(2)", ticks=3)
    inventory = query_game(rpc)
    assert inventory["world"] == "paused" and inventory["screen"] == "InventoryScreen", inventory
    assert inventory["target"] == {"kind": "unavailable", "reason": "screen_open"}, inventory
    assert inventory["player"]["main_hand"] == expected_player["main_hand"], inventory
    text_only("Key(escape)", "Wait(1)", ticks=2)
    observer_input(work, "press f8")
    observer_input(work, "release f8")
    observer_input(work, "inspect-fixture restore")
    restored = query_game(rpc)
    assert restored["world"] == "unpaused" and restored["player"]["off_hand"] is None, restored
    print("PASS: title, running and paused observations, player values, crosshair targets, GUI, same-frame PNG and cursor hint.")
