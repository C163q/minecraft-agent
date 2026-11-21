"""Exercise the production MCP binary and mod in a fresh single-player world (Linux)."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import select
import subprocess
import sys
import tempfile
import time

root = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(root / "mod"))
from build import build_jar, game_classpath
from mouse import exercise_mouse
from PIL import Image


def has_pause_icon(path):
    with Image.open(path) as image:
        for scale in range(1, 9):
            def rgb(x, y):
                return image.getpixel(((4 + x) * scale, (4 + y) * scale))[:3]

            bars = [rgb(x, y) for x in (5, 10) for y in (5, 8, 10)]
            backdrop = [rgb(8, y) for y in (3, 7, 12)]
            if all(min(pixel) > 220 for pixel in bars) and all(max(pixel) < 145 for pixel in backdrop):
                return True
    return False


def exercise(client, work, log, codex_env):
    serial = 0

    def submit(method, params):
        nonlocal serial
        serial += 1
        client.stdin.write(json.dumps({"jsonrpc": "2.0", "id": serial, "method": method, "params": params}) + "\n")
        client.stdin.flush()
        return serial

    def receive(request_id):
        if not select.select([client.stdout], [], [], 25)[0]:
            raise TimeoutError(request_id)
        reply = json.loads(client.stdout.readline())
        assert reply["id"] == request_id, reply.keys()
        return reply["result"]

    def rpc(method, params):
        return receive(submit(method, params))

    rpc("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}})
    client.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized"}\n')
    client.stdin.flush()
    tools = {t["name"]: t for t in rpc("tools/list", {})["tools"]}
    assert set(tools) == {"input_script", "cursor_hint", "inspect_input"}
    option = tools["input_script"]["inputSchema"]["properties"]["include_screenshot"]
    assert option["type"] == "boolean" and option["default"] is True

    def play(args, expected_ticks, name, world="paused"):
        reply = rpc("tools/call", {"name": "input_script", "arguments": args})
        assert not reply.get("isError"), reply["content"][0]
        blocks = reply["content"]
        assert blocks[0]["type"] == "text"
        status = json.loads(blocks[0]["text"])
        assert status["world"] == world and (expected_ticks is None or status["ticks"] == expected_ticks), status
        if args.get("include_screenshot") is False:
            assert len(blocks) == 1 and {"screenshot_id", "width", "height"}.isdisjoint(status), reply
            return status
        _, image = blocks
        assert status["screenshot_id"] and status["width"] > 0 and status["height"] > 0, status
        png = base64.b64decode(image["data"], validate=True)
        assert png.startswith(b"\x89PNG\r\n\x1a\n") and image["mimeType"] == "image/png"
        path = work / f"{name}.png"
        path.write_bytes(png)
        assert has_pause_icon(path) == (world == "paused"), f"unexpected pause indicator in {path}"
        return status

    def block(*lines):
        return {"script": "\n".join(["BEGIN", *lines, "END"])}

    def trace(offset):
        text = log.read_text()[offset:]
        return re.findall(r"AGENT_SAMPLE ([01]{3})", text), re.findall(r"AGENT_KEY (\d+ [01])", text)

    def inspect():
        reply = rpc("tools/call", {"name": "inspect_input", "arguments": {}})
        assert not reply.get("isError"), reply
        text, = reply["content"]
        state = json.loads(text["text"])
        assert reply["structuredContent"] == state
        return state

    assert inspect()["world"] == "unpaused"
    initial = play(block(), 0, "initial")
    for invalid in (None, "false", 0):
        rejected = rpc("tools/call", {"name": "input_script", "arguments": dict(block(), include_screenshot=invalid)})
        assert rejected.get("isError") and "include_screenshot" in rejected["content"][0]["text"]
    status = play(dict(block("Wait(1) PAUSE", "Wait(1)"), include_screenshot=False), 1, "text-only-pause")
    assert status["pause_reason"] == "pause" and inspect()["queue"]["total"] == 1
    (work / "text-only.input").write_text(block("Wait(1)")["script"])
    status = play({"path": "text-only.input", "include_screenshot": False}, 1, "text-only-end")
    assert status["pause_reason"] == "end" and inspect()["queue"]["total"] == 0
    status = play(dict(block("Key(f8)", "Wait(1)"), include_screenshot=False), 1, "text-only-hotkey")
    assert status["pause_reason"] == "hotkey"
    initial = play(dict(block("Wait(1)"), include_screenshot=True), 1, "initial")
    if codex_env is not None:
        from codex_mouse import exercise_codex
        play(block("Mouse(left) PAUSE", "Wait(1)"), 1, "before-codex")
        exercise_codex(work, codex_env, root / "target/debug/minecraft-mcp")
        (work / "reset-view").touch()
        deadline = time.monotonic() + 4
        while not (work / "view-reset").exists():
            if time.monotonic() > deadline:
                raise TimeoutError("observer failed to restore camera")
            time.sleep(.02)
        initial = play(block("Wait(2)"), 2, "initial")
    exercise_mouse(work, log, play, rpc, block, initial)

    play(block("Mouse(left)"), 1, "temporary-left")
    expected = {"world": "paused", "held": {"keys": {}, "buttons": {"left": "temporary"}},
                "moves": [], "max_speed_px_per_tick": 50, "pause_reason": "end",
                "queue": {"total": 0, "entries": [], "next_cursor": None}}
    assert inspect() == expected
    bad = rpc("tools/call", {"name": "inspect_input", "arguments": {"advance": True}})
    assert bad.get("isError")
    play(block("WaitMouseMove()"), 0, "inspect-idle")
    assert inspect() == expected
    play(block("MousePress(left) KeyPress(left_shift)", "Mouse(left) Key(a)"), 2, "inspect-handoff")
    assert inspect()["held"] == {"keys": {"left_shift": "persistent", "a": "temporary"},
                                 "buttons": {"left": "persistent"}}
    play(block("MouseRelease(left) KeyRelease(left_shift) MouseMove(Px(160), Px(0), Px(80))"), 1, "inspect-pending")
    expected["held"]["buttons"].clear()
    expected["moves"] = [{"remaining_px": {"x": 110, "y": 0}, "requested_speed_px_per_tick": 80}]
    offset = len(log.read_text())
    (work / "measure-world").touch()
    try:
        for _ in range(3):
            assert inspect() == expected
            time.sleep(.1)
    finally:
        (work / "measure-world").unlink()
    measured = re.findall(r"AGENT_WORLD (true|false) (\d+)", log.read_text()[offset:])
    assert len(measured) >= 2 and len(set(measured)) == 1 and measured[0][0] == "true", measured
    assert trace(offset) == ([], [])
    play(block("Wait(1)"), 1, "inspect-step")
    assert inspect()["moves"][0]["remaining_px"]["x"] == 60
    play(block("MouseMove(Px(-100), Px(0), Px(40), Px(0)) MouseMove(Px(-160), Px(0), Px(20))"),
         1, "inspect-overlap")
    assert inspect()["moves"] == [
        {"remaining_px": {"x": -60, "y": 0}, "requested_speed_px_per_tick": {"x": 40, "y": 0}},
        {"remaining_px": {"x": -140, "y": 0}, "requested_speed_px_per_tick": 20},
    ]
    play(block("CLEAR"), 0, "inspect-clear")
    assert inspect()["moves"] == []
    from pause import exercise_pause
    exercise_pause(work, log, play, rpc, block, submit, receive)
    print("PASS: read-only snapshots, held state, per-move remainders, server pause and world time.")
    offset = len(log.read_text())
    (work / "jump.input").write_text(block("KeyPress(w)", "Wait(5)", "Key(space)", "KeyRelease(w)")["script"])
    play({"path": "jump.input"}, 8, "jump")
    samples, events = trace(offset)
    assert samples == ["100"] * 6 + ["110", "000"], samples
    assert events[:2] == ["26 1", "44 1"] and sorted(events[2:]) == ["26 0", "44 0"], events
    offset = len(log.read_text())
    play(block("Key(w)"), 1, "hold")
    for invalid in [block("Wait(0)"), block("Key(a)", "KeyRelease(no_such_key)")]:
        rejected = rpc("tools/call", {"name": "input_script", "arguments": invalid})
        assert rejected.get("isError") and "line " in rejected["content"][0]["text"]
    play(block("KeyPress(w)", "Wait(1)", "KeyRelease(w)"), 3, "transfer")
    samples, events = trace(offset)
    assert samples == ["100"] * 3 + ["000"] and events == ["26 1", "26 0"], (samples, events)
    offset = len(log.read_text())
    play(block("Key(a)", "Key(k)", "Wait(1)"), 3, "rebind")
    samples, events = trace(offset)
    assert samples == ["000", "001", "000"], (samples, events)
    offset = len(log.read_text())
    play(block("Key(w) Key(left_shift)", "Wait(1)"), 2, "shift-chord")
    _, events = trace(offset)
    assert events == ["225 1", "26 1", "26 0", "225 0"], events
    masks = re.findall(r"AGENT_KEY 26 [01] mods=(\d+)", log.read_text()[offset:])
    assert all(int(mask) & 1 for mask in masks) and len(masks) == 2, masks
    play(block("Key(escape)"), 1, "menu", world="not_needed")
    assert inspect()["world"] == "not_needed"
    play(block("Wait(1)", "Key(escape)", "Wait(1)"), 3, "close-menu")
    (work / "slow").touch()
    time.sleep(.4)
    offset = len(log.read_text())
    play({"path": "jump.input"}, 8, "slow-jump")
    time.sleep(.5)
    frame_ids = re.findall(r"AGENT_SAMPLE [01]{3} frame=(\d+)", log.read_text()[offset:])
    samples, _ = trace(offset)
    assert len(set(frame_ids)) < 8 and samples == ["100"] * 6 + ["110", "000"], (samples, frame_ids)

    offset = len(log.read_text())
    long_call = submit("tools/call", {"name": "input_script", "arguments": block("KeyPress(w) MousePress(middle) MouseMove(Px(30000), Px(0), Px(1))", "Wait(4294967296)")})
    deadline = time.monotonic() + 5
    while "AGENT_KEY 26 1" not in log.read_text()[offset:]:
        if time.monotonic() > deadline:
            raise TimeoutError("long wait did not start")
        time.sleep(.02)
    client.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": long_call}}) + "\n")
    client.stdin.flush()
    assert receive(long_call)["isError"]
    play(block("WaitMouseMove()", "Wait(1)"), 1, "after-cancel")
    buttons = re.findall(r"AGENT_MOUSE (\d+ [01])", log.read_text()[offset:])
    assert buttons == ["2 1", "2 0"], buttons
    samples, events = trace(offset)
    assert samples[-1] == "000" and events == ["26 1", "26 0"], (samples, events)
    assert not has_pause_icon(work / "resumed.png"), "pause indicator persisted after resuming"
    print("PASS: eight ticks, handoff, remapping, invalid input, native pause, catch-up ticks, cancellation and END images.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("--codex", action="store_true", help="also verify the image and aim in a live Codex CLI session")
    args = parser.parse_args()
    profile = args.profile.resolve()
    cp = game_classpath(profile)
    tmp = root / ".tmp"
    tmp.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(dir=tmp, prefix="input-"))
    print(work, flush=True)
    classes = work / "movement-classes"
    classes.mkdir()
    subprocess.run(["javac", "--release", "25", "-d", classes, root / "mod/src/main/java/mcagent/Movement.java",
                    root / "tests/mouse/MovementTest.java"], check=True)
    subprocess.run(["java", "-cp", classes, "mcagent.MovementTest"], check=True)
    mods = work / "mods"
    build_jar(root / "mod/src/main", mods / "minecraft-agent.jar", cp)
    build_jar(root / "tests/game", mods / "observer.jar", cp)
    queue_cp = os.pathsep.join([str(mods / "minecraft-agent.jar"), cp])
    subprocess.run(["javac", "--release", "25", "-proc:none", "-cp", queue_cp, "-d", classes,
                    root / "tests/queue/InputQueueTest.java"], check=True)
    subprocess.run(["java", "-cp", os.pathsep.join([str(classes), queue_cp]), "mcagent.InputQueueTest"], check=True)
    subprocess.run(["cargo", "build", "--offline", "--bin", "minecraft-mcp"], cwd=root, check=True)
    (work / "options.txt").write_text("onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:4\nsimulationDistance:5\n")
    java = ["java", "-Xmx2G", "-XX:ActiveProcessorCount=4", "--enable-native-access=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED", "-cp", cp,
            "net.fabricmc.loader.impl.launch.knot.KnotClient", "--username", "LocalProbe", "--version", "26.3",
            "--gameDir", str(work), "--assetsDir", str(profile.parents[2] / "assets"), "--assetIndex",
            json.loads(profile.read_text())["assetIndex"]["id"], "--uuid", "00000000000040008000000000000001",
            "--accessToken", "0", "--versionType", "release", "--width", "800", "--height", "480"]
    log = work / "console.log"
    with tempfile.TemporaryDirectory() as socket_dir, log.open("w") as out:
        env = dict(os.environ, MINECRAFT_AGENT_SOCKET=f"{socket_dir}/game.sock")
        game = subprocess.Popen(java, cwd=work, env=env, stdout=out, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 100
            while not (work / "ready").is_file():
                if game.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"Game setup failed; see {log}")
                time.sleep(.1)
            client = subprocess.Popen([root / "target/debug/minecraft-mcp"], env=env, cwd=work,
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
            try:
                exercise(client, work, log, env if args.codex else None)
            finally:
                client.stdin.close()
                try:
                    client.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    client.kill()
                    client.wait()
        finally:
            (work / "done").touch()
            try:
                game.wait(timeout=15)
            except subprocess.TimeoutExpired:
                game.kill()
                game.wait()
        assert game.returncode == 0


if __name__ == "__main__":
    main()
