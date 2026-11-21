"""Optional live Codex check using the same MCP binary and independent game as run.py."""
import base64
import io
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

from PIL import Image


PROMPT = """只用 minecraft MCP 的游戏截图观察这个单人世界。让准星对准红色方块的中心。
先调用 inspect_input，记录开始时脚本左键的保持方式。然后观察，调用 cursor_hint 在你选的点标记并看图核对，再按返回的转角转动视角。
请保持站位不动，用停止帧检查最后准星是否在方块上；需要时可重新选点微调。
脚本语法按需查阅，入口为 docs/input-script/README.md。读取资料时每次只执行 cat 加一个或多个文档相对路径；
只从工具返回的图片判断游戏内容。最后返回 JSON，包含 initial_left（首次查询得到的左键保持方式）、centered（布尔值）与 observation（你看到的情况）。"""


def red(pixel):
    r, g, b = pixel[:3]
    return r > 60 and r > 2 * g and r > 2 * b


def centered(image):
    x, y = image.width // 2, image.height // 2
    # Avoid the white crosshair itself.
    return sum(red(image.getpixel((x + dx, y + dy))) for dx in (-9, 9) for dy in (-9, 9)) >= 3


def check_doc_command(command, docs):
    words = shlex.split(command)
    if len(words) == 3 and words[0].endswith(("sh", "bash")) and words[1] in {"-lc", "-c"}:
        words = shlex.split(words[2])
    assert len(words) >= 2 and words[0] == "cat", command
    assert all(str(Path(arg)) in docs or arg == "docs/input-script/*.md" for arg in words[1:]), command


def verify_session(trial, docs):
    frames = {}
    saw_inspect = saw_target_hint = saw_turn = False
    final = None
    count = 0
    with (trial / "events.jsonl").open() as events:
        for line in events:
            event = json.loads(line)
            if event["type"] != "item.completed":
                continue
            item = event["item"]
            if item["type"] == "command_execution":
                check_doc_command(item["command"], docs)
                continue
            if item["type"] in {"agent_message", "reasoning", "plan_update"}:
                continue
            assert item["type"] == "mcp_tool_call" and item["server"] == "minecraft", item["type"]
            result = item.get("result") or {}
            if result.get("isError"):
                continue
            assert item["status"] == "completed", item.get("error")
            if item["tool"] == "inspect_input":
                if not frames:
                    state = json.loads(result["content"][0]["text"])
                    assert state["world"] == "paused" and state["held"]["buttons"]["left"] == "temporary", state
                    saw_inspect = True
                continue
            text, image = result["content"]
            status = json.loads(text["text"])
            assert image["type"] == "image" and image["mimeType"] == "image/png"
            data = base64.b64decode(image["data"], validate=True)
            rgb = Image.open(io.BytesIO(data)).convert("RGB")
            tool = item["tool"]
            count += 1
            (trial / f"{count:02d}-{tool}.png").write_bytes(data)
            if tool == "input_script":
                assert status["world"] == "paused"
                if not frames:
                    assert not centered(rgb), "initial pose already points at the landmark"
                frames[status["screenshot_id"]] = rgb
                script = item["arguments"]["script"]
                if saw_target_hint and "MouseMove(" in script and "Rotate(" in script and status["ticks"] > 0:
                    saw_turn = True
                final = rgb
            else:
                assert tool == "cursor_hint"
                source = frames[item["arguments"]["screenshot_id"]]
                x, y = status["x"], status["y"]
                assert source.size == rgb.size and rgb.getpixel((x, y)) == (255, 0, 0)
                if status.get("rotation") and red(source.getpixel((x, y))):
                    saw_target_hint = True
    assert saw_inspect and saw_target_hint and saw_turn, "missing on-target hint or subsequent angular movement"
    assert centered(final), "final frame missed the red landmark"
    answer = json.loads((trial / "answer.json").read_text())
    assert answer["initial_left"] == "temporary" and answer["centered"] is True and answer["observation"].strip(), answer
    print(f"PASS: Codex saw {count} MCP images and centered the selected red block. Evidence: {trial}", flush=True)


def exercise_codex(work, env, binary):
    root = Path(__file__).resolve().parents[2]
    trial = work / "codex-mouse"
    trial.mkdir()
    schema = trial / "answer.schema.json"
    schema.write_text(json.dumps({
        "type": "object", "properties": {"initial_left": {"type": "string"}, "centered": {"type": "boolean"}, "observation": {"type": "string"}},
        "required": ["initial_left", "centered", "observation"], "additionalProperties": False,
    }))
    with tempfile.TemporaryDirectory(prefix="mc-codex-docs-") as directory:
        runner = Path(directory)
        docs = Path("docs/input-script")
        shutil.copytree(root / docs, runner / docs)
        pages = {str(page.relative_to(runner)) for page in (runner / docs).glob("*.md")}
        args = [
            "codex", "-a", "never", "exec", "--ignore-user-config", "--ephemeral", "--skip-git-repo-check",
            "-s", "read-only", "-C", directory, "-m", env.get("MINECRAFT_AGENT_CODEX_MODEL", "gpt-6.2-astra"),
            "--disable", "apps", "--disable", "plugins", "-c", 'model_reasoning_effort="medium"',
            "-c", f'projects.{json.dumps(directory)}.trust_level="trusted"',
            "-c", f'mcp_servers.minecraft.command={json.dumps(str(Path(binary).resolve()))}',
            "-c", f'mcp_servers.minecraft.env.MINECRAFT_AGENT_SOCKET={json.dumps(env["MINECRAFT_AGENT_SOCKET"])}',
            "-c", "mcp_servers.minecraft.required=true",
            "-c", 'mcp_servers.minecraft.tools.inspect_input.approval_mode="approve"',
            "-c", 'mcp_servers.minecraft.tools.cursor_hint.approval_mode="approve"',
            "-c", 'mcp_servers.minecraft.tools.input_script.approval_mode="approve"',
            "--output-schema", str(schema), "--json", "-o", str(trial / "answer.json"), PROMPT,
        ]
        with (trial / "events.jsonl").open("w") as out, (trial / "stderr.log").open("w") as err:
            subprocess.run(args, env=env, cwd=runner, stdin=subprocess.DEVNULL,
                           stdout=out, stderr=err, timeout=240, check=True)
        verify_session(trial, pages)
