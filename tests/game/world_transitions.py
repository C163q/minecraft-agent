"""Drive native single-player menus through MCP and observe the destination frame."""
import re
import time

from PIL import Image

from game_state import TITLE_STATE, png_image, query_game
from pause_binding import observer_input


def exercise_world_transitions(work, log, play, rpc, block, inspect, delays=None):
    def fixture(command):
        observer_input(work, f"world-transition {command}")
        return (work / "world-transition-state").read_text()

    def locate(control):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            point = fixture(control)
            if point != "missing":
                return tuple(map(float, point.split()))
            time.sleep(.05)
        raise AssertionError(("missing native control", control, query_game(rpc)))

    def move_to(point):
        x, y = point
        return f"MouseMoveTo(Px({x}), Px({y}))"

    def send(*lines, ticks=None, image=None, world="not_needed", **options):
        arguments = dict(block(*lines), **options)
        if image is None:
            arguments["include_screenshot"] = False
        return play(arguments, ticks, image or "transition-text", world, timeout=40)

    def ordinary(result):
        assert result["pause_reason"] == "end" and "transition" not in result, result

    def move(control):
        ordinary(send(move_to(locate(control)), "WaitMouseMove()"))

    def click(control):
        move(control)
        ordinary(send("Mouse(left)", ticks=1))

    def await_screen(*screens):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            screen = query_game(rpc)["screen"]
            if screen in screens:
                return screen
            time.sleep(.05)
        raise AssertionError(("unexpected screen", screen, screens))

    def transition(result, direction, screen, status="completed"):
        assert result["pause_reason"] == "world_changed", result
        assert result["transition"] == {"direction": direction, "status": status, "input_cleared": True}, result
        assert "screen" in result and result["screen"] == screen, result

    def cleared():
        state = inspect()
        assert state["held"] == {"keys": {}, "buttons": {}}, state
        assert state["moves"] == state["listeners"] == [], state
        assert state["queue"] == {"total": 0, "entries": [], "next_cursor": None}, state

    def assert_world_paused():
        offset = len(log.read_text())
        marker = work / "measure-world"
        marker.touch()
        try:
            for _ in range(3):
                state = query_game(rpc)
                assert state["world"] == "paused" and state["screen"] is None, state
                assert state["player"]["dimension"] == "minecraft:overworld", state
                time.sleep(.1)
        finally:
            marker.unlink()
        measured = re.findall(r"AGENT_WORLD (true|false) (\d+)", log.read_text()[offset:])
        assert len(measured) >= 2 and len(set(measured)) == 1 and measured[0][0] == "true", measured

    assert query_game(rpc) == TITLE_STATE
    click("button menu.singleplayer")
    # When no save exists, 26.3 opens the creation form after reading the list.
    if (work / "saves" / "test" / "level.dat").exists():
        await_screen("SelectWorldScreen")
        click("button selectWorld.create")
    await_screen("CreateWorldScreen")
    ordinary(send("Key(left_control) Key(a)", "Key(t)", "Key(e)", "Key(s)", "Key(t)", "Key(a)", ticks=6))
    assert fixture("flat") == "testa"
    move("button selectWorld.create")
    created = send('Print("enter")', "Mouse(left)", ticks=1, max_ticks=1, image="transition-created", world="paused")
    transition(created, "enter", None)
    assert created["stdout"] == "enter\n"
    marked = rpc("tools/call", {"name": "cursor_hint", "arguments": {
        "screenshot_id": created["screenshot_id"], "x": created["width"] // 2, "y": created["height"] // 2,
    }})
    assert not marked.get("isError"), marked["content"][0]
    _, image = marked["content"]
    assert png_image(image).size == (created["width"], created["height"])
    assert_world_paused()
    cleared()

    ordinary(send("Key(escape)", ticks=1))
    await_screen("PauseScreen")
    quit_position = move_to(locate("button menu.returnToMenu"))
    ordinary(send("Wait(1)", "Key(escape)", "Wait(1)", ticks=3, world="paused"))
    assert fixture("toggle-sneak enable") == "false"
    staged = play({
        "script": ("FUNCTION $old\nBEGIN\nKeyPress(w) KeyPress(left_shift) MousePress(middle) PAUSE\nWait(1000)\nKey(y)\nEND\n"
                   "BEGIN\n$old()\nEND"),
        "include_screenshot": False,
    }, 1, "transition-retained")
    assert staged["pause_reason"] == "pause", staged
    before = inspect()
    assert before["held"] == {"keys": {"w": "persistent", "left_shift": "persistent"},
                              "buttons": {"middle": "persistent"}}, before
    assert fixture("toggle-sneak read") == "true"
    assert before["queue"]["total"] == 2, before
    assert before["queue"]["entries"][0]["call_stack"][0]["name"] == "old", before
    offset = len(log.read_text())
    # Left-click starts disconnect. The right-click on that same tick and the
    # queued handler and script actions must never reach native input.
    left = send("ON(ScreenOpened)", "BEGIN", "Wait(1000)", "Key(y)", "END",
                "Key(escape)", quit_position, "Wait(20)",
                "Mouse(left) Mouse(right) MouseMove(Px(1), Px(0), Px(0.1))", "Key(y)", "Wait(200)",
                ticks=23, max_ticks=23, image="transition-left")
    transition(left, "leave", "TitleScreen")
    handler, = left["events"]
    assert handler["event"] == "ScreenOpened" and handler["screen"] == "PauseScreen", left
    origin, = handler["listeners"]
    assert origin["event"] == "ScreenOpened" and origin["line"] == 2, origin
    native = log.read_text()[offset:]
    buttons = re.findall(r"AGENT_MOUSE (\d+ [01])", native)
    assert buttons[0] == "1 1" and sorted(buttons[1:]) == ["1 0", "2 0"], buttons
    assert re.findall(r"AGENT_KEY 26 ([01])", native) == ["0"], native
    assert "AGENT_KEY 28 1" not in native, native
    current, rendered = query_game(rpc, include_screenshot=True)
    assert {key: current[key] for key in TITLE_STATE} == TITLE_STATE, current
    # The opaque center of the Singleplayer button is stable as the panorama
    # moves. A saving frame with title metadata cannot match these pixels.
    x, y = (round(coordinate) for coordinate in locate("button menu.singleplayer"))
    button = (x - 24, y - 4, x + 24, y + 4)
    with Image.open(work / "transition-left.png") as screenshot:
        assert screenshot.size == rendered.size
        assert screenshot.convert("RGB").crop(button).tobytes() == rendered.crop(button).tobytes()
    cleared()

    click("button menu.singleplayer")
    assert await_screen("SelectWorldScreen") == "SelectWorldScreen"
    click("row testa")
    move("button selectWorld.select")
    reopened = send("Mouse(left)", ticks=1, world="paused")
    transition(reopened, "enter", None)
    assert fixture("toggle-sneak read") == "false", "script-held sneak restored in the next world"
    assert_world_paused()
    cleared()
    offset = len(log.read_text())
    resumed = send("Wait(2)", ticks=2, world="paused")
    ordinary(resumed)
    assert resumed["events"] == [], resumed
    samples = re.findall(r"AGENT_SAMPLE ([01]{3})", log.read_text()[offset:])
    assert samples == ["000", "000"], samples

    ordinary(send("Key(escape)", ticks=1))
    move("button menu.returnToMenu")
    transition(send("Mouse(left)", ticks=1), "leave", "TitleScreen")
    assert fixture("require-backup testa") == "ready"
    click("button menu.singleplayer")
    await_screen("SelectWorldScreen")

    click("button selectWorld.create")
    await_screen("CreateWorldScreen")
    blocked = work / "saves" / fixture("block-create")
    try:
        move("button selectWorld.create")
        failed = send("Mouse(left)", ticks=1, image="transition-create-failed")
        transition(failed, "enter", "SelectWorldScreen", "failed")
        cleared()
    finally:
        blocked.unlink()

    def open_backup_prompt(image=None):
        click("row testa")
        move("button selectWorld.select")
        prompt = send("Mouse(left)", ticks=1, image=image)
        transition(prompt, "enter", "BackupConfirmScreen", "needs_input")
        assert query_game(rpc)["screen"] == "BackupConfirmScreen"
        cleared()

    open_backup_prompt("transition-backup")
    move("button gui.cancel")
    cancelled = send("Mouse(left)", ticks=1)
    transition(cancelled, "enter", "SelectWorldScreen", "cancelled")
    cleared()

    open_backup_prompt()
    move("button selectWorld.backupJoinSkipButton")
    confirmed = send("Mouse(left)", ticks=1, world="paused")
    transition(confirmed, "enter", None)
    assert_world_paused()
    cleared()
    print("PASS: native create, save and quit, reopen, backup prompt, cancellation, failure and MCP frames.")
    if delays:
        from transition_delays import exercise_delays
        exercise_delays(work, rpc, block, send, move, click, await_screen, *delays)
