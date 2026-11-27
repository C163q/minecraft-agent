"""Exercise input script imports through the MCP in the disposable world."""
import re
from textwrap import dedent


def exercise_imports(work, play, rpc, block, inspect):
    def write(path, source):
        file = work / "imports" / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(dedent(source).strip() + "\n", encoding="utf-8")
        return file

    def run(name, args, ticks, reason="end"):
        result = play(dict(args, include_screenshot=False), ticks, name)
        assert result["pause_reason"] == reason, result

    def importing(path, *lines):
        return {"script": f'IMPORT "{path}"\n' + block(*lines)["script"]}

    write("lib/common.input", """
        FUNCTION $common
        BEGIN
            Key(k)
        END
        BEGIN
            CLEAR
            KeyPress(j) PAUSE
            KeyRelease(j)
        END
    """)
    run("import-before-inline", block("PAUSE", "KeyPress(b)"), 0, "pause")
    run("import-inline", importing("imports/lib/common.input", "$common()"), 1)
    state = inspect()
    assert state["held"]["keys"] == {"b": "persistent", "k": "temporary"}, state
    assert state["queue"]["total"] == 0, state

    run("import-before-direct", block("PAUSE", "KeyPress(q)"), 0, "pause")
    run("import-direct-library", {"path": "imports/lib/common.input"}, 1, "pause")
    state = inspect()
    assert state["held"]["keys"] == {"b": "persistent", "j": "persistent"}, state
    assert [row["source"] for row in state["queue"]["entries"]] == ["KeyRelease(j)"], state
    run("import-direct-resume", block("KeyRelease(b)"), 1)
    state = inspect()
    assert state["held"]["keys"] == {} and state["queue"]["total"] == 0, state

    write("lib/left.input", """
        IMPORT "common.input"
        FUNCTION $left
        BEGIN
            $common()
        END
    """)
    write("lib/branch/right.input", """
        IMPORT ".././common.input"
        FUNCTION $right
        BEGIN
            $common()
            Key(h)
        END
    """)
    write("tasks/explore.input", """
        IMPORT "../lib/left.input"
        IMPORT "../lib/branch/right.input"
        BEGIN
            $left()
            $right()
        END
    """)
    run("import-diamond", {"path": "imports/tasks/explore.input"}, 3)
    state = inspect()
    assert state["held"]["keys"] == {"h": "temporary"}, state
    assert state["queue"]["total"] == 0, state

    library = """
        FUNCTION $deferred
        BEGIN
            PAUSE
            $shared()
        END
        FUNCTION $shared
        BEGIN
            Key({key})
        END
    """
    shared = "imports/lib/shared.input"
    write("lib/shared.input", library.format(key="j"))
    run("import-old-definition", importing(shared, "$deferred()"), 0, "pause")
    write("lib/shared.input", library.format(key="k"))
    run("import-new-definition", importing(shared, "$shared()"), 1)
    state = inspect()
    assert state["held"]["keys"] == {"j": "temporary", "k": "temporary"}, state
    assert state["queue"]["total"] == 0, state

    invalid = write("lib/invalid.input", """
        FUNCTION $unused
        BEGIN
            Key(h)
        END
        // The library entry is a demo.
        BEGIN
            Wait(2)
            Key(no_such_import_key)
        END
    """)
    run("import-before-rejection", block("KeyPress(b) PAUSE", "Key(j)"), 1, "pause")
    before = inspect()
    duplicate = write("lib/duplicate.input", """
        // Both libraries declare the same unused function.
        FUNCTION $unused
        BEGIN
        END
    """)
    conflict = rpc("tools/call", {"name": "input_script", "arguments": {
        "script": 'IMPORT "imports/lib/invalid.input"\nIMPORT "imports/lib/duplicate.input"\nBEGIN\nCLEAR\nEND',
    }})
    assert conflict.get("isError"), conflict
    message = conflict["content"][0]["text"]
    assert "$unused" in message and f"{invalid}: line 1" in message and f"{duplicate}: line 2" in message, message
    assert inspect() == before
    invalid_args = importing("imports/lib/invalid.input", "CLEAR", "KeyPress(q)")
    failed = rpc("tools/call", {"name": "input_script",
                               "arguments": dict(invalid_args, include_screenshot=False)})
    assert failed.get("isError"), failed
    diagnostic = failed["content"][0]["text"]
    assert str(invalid) in diagnostic and re.search(r"\bline 8\b", diagnostic), diagnostic
    assert "no_such_import_key" in diagnostic, diagnostic
    after = inspect()
    assert after == before, (before, after)
    run("import-rejection-resume", block("KeyRelease(b)"), 1)
    state = inspect()
    assert state["held"]["keys"] == {"j": "temporary"} and state["queue"]["total"] == 0, state
    print("PASS: inline and file imports, shared dependencies, library entry isolation, "
          "paused definitions and key validation.")
