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
    assert {t["name"] for t in rpc("tools/list", {})["tools"]} == {"input_script", "cursor_hint"}

    def play(args, expected_ticks, name, world="paused"):
        reply = rpc("tools/call", {"name": "input_script", "arguments": args})
        assert not reply.get("isError"), reply["content"][0]
        status, image = reply["content"]
        status = json.loads(status["text"])
        assert status["world"] == world and (expected_ticks is None or status["ticks"] == expected_ticks), status
        assert status["screenshot_id"] and status["width"] > 0 and status["height"] > 0, status
        png = base64.b64decode(image["data"], validate=True)
        assert png.startswith(b"\x89PNG\r\n\x1a\n") and image["mimeType"] == "image/png"
        (work / f"{name}.png").write_bytes(png)
        return status

    def block(*lines):
        return {"script": "\n".join(["BEGIN", *lines, "END"])}

    def trace(offset):
        text = log.read_text()[offset:]
        return re.findall(r"AGENT_SAMPLE ([01]{3})", text), re.findall(r"AGENT_KEY (\d+ [01])", text)

    initial = play(block(), 0, "initial")
    if codex_env is not None:
        from codex_mouse import exercise_codex
        exercise_codex(work, codex_env, root / "target/debug/minecraft-mcp")
        (work / "reset-view").touch()
        deadline = time.monotonic() + 4
        while not (work / "view-reset").exists():
            if time.monotonic() > deadline:
                raise TimeoutError("observer failed to restore camera")
            time.sleep(.02)
        initial = play(block("Wait(2)"), 2, "initial")
    exercise_mouse(work, log, play, rpc, block, initial)
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
