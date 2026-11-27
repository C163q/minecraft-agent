"""Exercise the 30-second wall-clock deadline and transport cancellation in the actual loading loops."""
from contextlib import contextmanager
import json
import time

from game_state import query_game


def exercise_delays(work, rpc, block, send, move, click, await_screen, submit, receive, client):
    @contextmanager
    def delay(direction, *, no_frame=False):
        entered = work / "delay-started"
        marker = work / f"delay-{direction}"
        no_draw = work / "delay-without-frame"
        entered.unlink(missing_ok=True)
        if no_frame:
            no_draw.touch()
        marker.touch()
        try:
            yield entered
        finally:
            marker.unlink(missing_ok=True)
            no_draw.unlink(missing_ok=True)

    def wait_until(condition, seconds):
        deadline = time.monotonic() + seconds
        while not condition():
            if time.monotonic() >= deadline:
                raise AssertionError("game did not reach expected transition state")
            time.sleep(.05)

    def prepare_exit():
        send("Key(escape)", ticks=1)
        await_screen("PauseScreen")
        move("button menu.returnToMenu")

    def prepare_entry():
        click("button menu.singleplayer")
        await_screen("SelectWorldScreen")
        click("row testa")
        move("button selectWorld.select")

    def playable():
        state = query_game(rpc)
        if state["player"] is None or state["screen"] is not None:
            return False
        assert state["world"] == "unpaused", state
        return True

    prepare_exit()
    with delay("leave") as entered:
        args = dict(block("Mouse(left)"), include_screenshot=False)
        request = submit("tools/call", {"name": "input_script", "arguments": args})
        wait_until(entered.exists, 8)
        client.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/cancelled",
                                       "params": {"requestId": request}}) + "\n")
        client.stdin.flush()
        assert receive(request, 8).get("isError")
        # This query must be serviced during the native render-only shutdown loop.
        state = query_game(rpc, timeout=8)
        assert state["player"] is None and state["screen"] != "TitleScreen", state
    await_screen("TitleScreen")

    prepare_entry()
    with delay("enter") as entered:
        t0 = time.monotonic()
        state = send("Mouse(left)", ticks=1, image="transition-loading")
        assert entered.exists() and 29 <= time.monotonic() - t0 < 40
        assert state["transition"] == {"direction": "enter", "status": "in_progress",
                                       "input_cleared": True}, state
        assert state["screen"] and state["screenshot_id"], state
    wait_until(playable, 20)

    prepare_exit()
    result = send("Mouse(left)", ticks=1)
    assert result["screen"] == "TitleScreen", result
    prepare_entry()
    with delay("enter", no_frame=True) as entered:
        args = dict(block("Mouse(left)"), include_screenshot=False)
        t0 = time.monotonic()
        reply = rpc("tools/call", {"name": "input_script", "arguments": args}, timeout=43)
        assert entered.exists() and 29 <= time.monotonic() - t0 < 43
        assert reply.get("isError"), reply
    wait_until(playable, 20)
    print("PASS: cancelled save, timed-out entry with loading PNG, bounded no-frame error and no late pause.")
