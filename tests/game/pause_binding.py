"""Drive the isolated observer's SDL events and vanilla Controls screen."""
import time


def observer_input(work, command):
    request = work / "observer-input"
    pending = work / "observer-input.tmp"
    pending.write_text(command)
    pending.replace(request)
    deadline = time.monotonic() + 5
    while request.exists():
        if time.monotonic() >= deadline:
            raise TimeoutError(f"observer did not process {command!r}")
        time.sleep(.01)


def assert_saved_pause_key(work, name):
    entry = f"key_key.minecraft_agent.pause:key.keyboard.{name}"
    assert entry in (work / "options.txt").read_text().splitlines(), entry
