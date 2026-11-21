"""Assertions on the real MCP images, Minecraft button events and server container."""
import base64
import io
import json
import re
import time

from PIL import Image


def exercise_mouse(work, log, play, rpc, block, initial):
    def hint(args):
        reply = rpc("tools/call", {"name": "cursor_hint", "arguments": args})
        assert not reply.get("isError"), reply["content"][0]
        note, image = reply["content"]
        return json.loads(note["text"]), Image.open(io.BytesIO(base64.b64decode(image["data"]))).convert("RGB")

    def buttons_since(offset):
        entries = re.findall(r"AGENT_MOUSE (\d+) ([01]) at=([\d.-]+),([\d.-]+)", log.read_text()[offset:])
        return [(int(button), int(action), float(x), float(y)) for button, action, x, y in entries]

    def diamond_color(rgb):
        r, g, b = rgb
        return g > 120 and b > 110 and g > 1.75 * r and b > 1.75 * r

    def landmark_color(rgb):
        r, g, b = rgb
        return r > 60 and r > 2 * g and r > 2 * b

    with Image.open(work / "initial.png") as source:
        source = source.convert("RGB")
        w, h = source.size
        # Fixture has one red block ahead, away from the HUD and player's hand.
        pts = [(x, y) for y in range(h // 5, 3 * h // 4, 2)
               for x in range(w // 8, 5 * w // 8, 2) if landmark_color(source.getpixel((x, y)))]
        assert len(pts) > 40, "red landmark missing in initial PNG"
        x, y = (round(sum(p[i] for p in pts) / len(pts)) for i in range(2))
        args = {"screenshot_id": initial["screenshot_id"], "x": x, "y": y}
        note, marked = hint(args)
        assert marked.size == source.size and marked.getpixel((x, y)) == (255, 0, 0)
        angle = note["rotation"]
        assert angle["yaw_delta_deg"] < -2 and abs(angle["pitch_delta_deg"]) < 40, note
        turns = ", ".join(f"Rotate({angle[k]:.8f})" for k in ("yaw_delta_deg", "pitch_delta_deg"))
    play(block(f"MouseMove({turns})", "WaitMouseMove()"), None, "aimed")
    with Image.open(work / "aimed.png") as aimed:
        # Exclude the crosshair itself.
        assert sum(landmark_color(aimed.getpixel((w // 2 + dx, h // 2 + dy))[:3])
                   for dx in (-9, 9) for dy in (-9, 9)) >= 3, "landmark off crosshair"
    old, _ = hint(args)
    assert old["rotation"] == angle
    external, _ = hint({"path": str(work / "initial.png"), "x": "50%", "y": "50%"})
    assert external["rotation"] is None
    disabled, _ = hint(dict(args, include_rotation=False))
    assert disabled.get("rotation") is None

    # At yaw +6 the crosshair misses the chest to the right. A +6-degree turn
    # fits in one tick and brings the ray inside the chest before the click.
    offset = len(log.read_text())
    prepare = f"MouseMove(Rotate({6 - angle['yaw_delta_deg']:.8f}), Rotate({-angle['pitch_delta_deg']:.8f}))"
    play(block(prepare, "WaitMouseMove()", "Mouse(right)", "Wait(2)"), None, "miss-chest")
    assert "AGENT_OPEN chest" not in log.read_text()[offset:]
    play(block("MouseMove(Rotate(6), Rotate(0)) Mouse(right)", "Wait(5)"), 6, "open-chest")
    assert "AGENT_OPEN chest" in log.read_text()[offset:], "same-tick turn and click missed chest"
    play(block("Key(escape)", "Wait(1)"), 2, "close-chest")

    play(block("MouseMove(Px(160), Px(0), Px(80))"), 1, "pending")
    play(block("WaitMouseMove()", "WaitMouseMove()"), 3, "drained")
    play(block("MouseMove(Px(-80), Px(0), Px(80)) MouseMove(Px(80), Px(0), Px(80))",
               "WaitMouseMove()"), 1, "cancelled-out")
    play(block("MouseMove(Px(400), Px(0))", "CLEAR", "WaitMouseMove()"), 1, "clear")

    inventory = play(block("Key(e)", "Wait(2)"), 3, "inventory")
    note, _ = hint({"screenshot_id": inventory["screenshot_id"], "x": 0, "y": 0})
    assert note["rotation"] is None, note
    with Image.open(work / "inventory.png") as image:
        image = image.convert("RGB")
        # The test server placed a diamond in the player's first hotbar slot.
        pts = [(x, y) for y in range(h // 2, 4 * h // 5, 2) for x in range(w // 5, 4 * w // 5, 2)
               if diamond_color(image.getpixel((x, y)))]
        assert len(pts) >= 4, "diamond missing in inventory screenshot"
        x, y = (round(sum(p[i] for p in pts) / len(pts)) for i in range(2))
    offset = len(log.read_text())
    play(block(f"MouseMoveTo(Px({x}), Px({y}))", "WaitMouseMove()", "MousePress(left)",
               "WaitMouseMove()", "MouseRelease(left)", "Wait(3)"), None, "picked-up")
    events = buttons_since(offset)
    assert events == [(1, 1, x, y), (1, 0, x, y)], events
    deadline = time.monotonic() + 3
    while "AGENT_CARRY diamond" not in log.read_text()[offset:]:
        assert time.monotonic() < deadline, (x, y, "server did not pick up diamond")
        time.sleep(.05)
    play(block("MouseMove(Px(13), Px(6)) Mouse(right)", "Wait(1)"), 2, "same-tick")
    last = buttons_since(offset)[-2:]
    assert last == [(3, 1, x + 13, y + 6), (3, 0, x + 13, y + 6)], last

    # Waypoints use screenshot geometry. Two overlapping moves +90/-90 finish
    # back at the drag start; a continuously re-targeted absolute move would not.
    play(block("MouseMoveTo(Percentage(50), Percentage(50))", "WaitMouseMove()"), None, "midpoint")
    cx, cy = w // 2, h // 2
    offset = len(log.read_text())
    play(block("MousePress(middle)", f"MouseMoveTo(Px({cx+90}), Px({cy}), Px(30), Px(0))",
               "MouseMove(Px(-90), Px(0), Px(80))", "WaitMouseMove()", "MouseRelease(middle)"),
         5, "overlapping-drag")
    assert buttons_since(offset) == [(2, 1, cx, cy), (2, 0, cx, cy)]
    offset = len(log.read_text())
    play(block("MouseMove(Rotate(1.5), Px(0)) Mouse(middle)", "Wait(1)"), 2, "gui-rotate")
    assert buttons_since(offset) == [(2, 1, cx + 10, cy), (2, 0, cx + 10, cy)]

    play(block("Key(escape)", "Wait(1)"), 2, "leave-inventory")
    print("PASS: stopping-frame aim and realigning, retained and concurrent moves, GUI picking and server item transfer.")
