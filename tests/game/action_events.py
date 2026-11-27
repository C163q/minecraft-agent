"""Assert native action confirmations and their listener origins through stdio."""
from textwrap import dedent
from typing import NamedTuple

from game_state import query_game
from pause_binding import observer_input


DIMENSION = "minecraft:overworld"
TARGET = {"x": 12, "y": 96, "z": 3}


class ServerState(NamedTuple):
    block_id: str
    open_state: str
    grounded: bool
    target_uses: int


def exercise_action_events(work, log, play, rpc, block, inspect):
    def send(lines, ticks=None, image=None, world="paused"):
        return play(dict(block(*lines), include_screenshot=image is not None), ticks, image or "action-status", world)

    def handler(event, *body):
        return [f"ON({event})", "BEGIN", *(body or ("PAUSE",)), "END"]

    def watch(names, *inputs, ticks=None, image=None, world="paused", body=("PAUSE",)):
        script = ["CLEAR"]
        for event in names:
            script.extend(handler(event, *body))
        return send([*script, *inputs], ticks, image, world)

    def server(*, lower=False):
        observer_input(work, "action-fixture read-lower" if lower else "action-fixture read")
        block_id, open_state, grounded, uses = (work / "action-state").read_text().split()
        return ServerState(block_id, open_state, grounded == "true", int(uses))

    def setup(scene, target_id=None):
        observer_input(work, f"action-fixture {scene}")
        assert send(["CLEAR", "Wait(6)"], 6)["events"] == []
        assert server().grounded, scene
        if target_id:
            state = query_game(rpc)
            expected_pos = dict(TARGET, y=95) if scene == "till" else TARGET
            assert state["target"]["position"] == expected_pos and state["target"]["id"] == target_id, state
            assert state["target"]["face"] == ("up" if scene == "till" else "north"), state
            expected_items = {
                "till": (None, "minecraft:iron_hoe"),
                "strip": ("minecraft:iron_axe", None),
                "survival": ("minecraft:iron_pickaxe", None),
                "creative": ("minecraft:iron_pickaxe", None),
            }
            items = tuple((state["player"][hand] or {}).get("id") for hand in ("main_hand", "off_hand"))
            assert items == expected_items.get(scene, (None, None)), state

    def payloads(result, names):
        records = result["events"]
        assert [record["event"] for record in records] == names, result
        for record in records:
            listeners = record["listeners"]
            assert listeners and all(origin["event"] == record["event"] for origin in listeners), result
        return [{key: value for key, value in record.items() if key not in {"event", "listeners"}}
                for record in records]

    def paused_by(result, names):
        assert result["pause_reason"] == ("pause" if names else "end"), result
        assert [origin["event"] for origin in result.get("pause_listeners", [])] == names, result

    def interaction(pose, hand, item, before, after):
        return {"dimension": DIMENSION, "position": pose, "hand": hand, "item": item,
                "before": before, "after": after}

    door = "minecraft:oak_door"
    closed = {"id": door, "facing": "north", "half": "upper", "hinge": "left", "open": False, "powered": False}
    opened = dict(closed, open=True)
    setup("door", door)
    result = watch(["BlockInteracted"], "MousePress(right)", "Wait(18)")
    assert payloads(result, ["BlockInteracted"]) == [interaction(TARGET, "main_hand", None, closed, opened)], result
    paused_by(result, ["BlockInteracted"])
    assert inspect()["held"]["buttons"] == {"right": "persistent"}, result
    observer_input(work, "action-fixture wait-paused-ticks")
    state = server()
    assert state.open_state == "true" and state.target_uses == 1, state
    resumed = send(["CLEAR", "MouseRelease(right)", "Wait(6)"], 7)
    assert payloads(resumed, []) == [] and server() == state, (resumed, server())
    assert inspect()["held"]["buttons"] == {}, resumed

    setup("door", door)
    assert server()[:2] == (door, "false")
    # Let the one-tick click release before stopping. Vanilla keeps processing held use in paused client ticks.
    after_click = ("Wait(2)", "PAUSE")
    registered = send(["CLEAR", *handler("BlockInteracted", *after_click), "PAUSE", "Wait(30)"], 0)
    assert payloads(registered, []) == [] and registered["pause_reason"] == "pause", registered
    old, = inspect()["listeners"]
    assert old["event"] == "BlockInteracted" and old["line"] == 3, old
    result = send([*handler("BlockInteracted", *after_click), "Mouse(right)", "Wait(22)"], image="door-open")
    assert payloads(result, ["BlockInteracted"]) == [interaction(TARGET, "main_hand", None, closed, opened)], result
    paused_by(result, ["BlockInteracted", "BlockInteracted"])
    previous, current = result["events"][0]["listeners"]
    assert result["pause_listeners"] == [previous, current], result
    assert previous == {key: value for key, value in old.items() if key != "state"}, result
    assert previous["block"] < current["block"] and current["line"] == 2, result
    top, bottom = server()[:2], server(lower=True)[:2]
    assert top == bottom == (door, "true"), (top, bottom, result)
    assert payloads(send(["CLEAR_LISTENERS", "Wait(2)"], 0), []) == []
    send(["CLEAR", "Wait(4)"], 4)

    result = play({"script": dedent("""\
        FUNCTION $close_door
        BEGIN
            ON(BlockInteracted)
            BEGIN
                Wait(2)
                RETURN
            END
            Mouse(right)
            Wait(25)
        END
        BEGIN
            CLEAR
            $close_door()
            ScreenShot("door-after-return.png")
        END
    """)}, None, "door-closed")
    assert payloads(result, ["BlockInteracted"]) == [interaction(TARGET, "main_hand", None, opened, closed)], result
    paused_by(result, [])
    assert result["ticks"] < 26 and server()[:2] == server(lower=True)[:2] == (door, "false"), result
    origin, = result["events"][0]["listeners"]
    caller, = origin["call_stack"]
    assert (origin["line"], caller["name"], caller["line"]) == (3, "close_door", 13), origin
    assert (work / "door-after-return.png").read_bytes().startswith(b"\x89PNG")

    for scene, pose, hand, item, before, after in [
        ("till", dict(TARGET, y=95), "off_hand", "minecraft:iron_hoe",
         {"id": "minecraft:dirt"}, {"id": "minecraft:farmland", "moisture": 0}),
        ("strip", TARGET, "main_hand", "minecraft:iron_axe",
         {"id": "minecraft:oak_log", "axis": "y"}, {"id": "minecraft:stripped_oak_log", "axis": "y"}),
    ]:
        setup(scene, before["id"])
        result = watch(["BlockInteracted"], "Mouse(right)", "Wait(18)", image=scene, body=after_click)
        assert payloads(result, ["BlockInteracted"]) == [interaction(pose, hand, item, before, after)], result
        paused_by(result, ["BlockInteracted"])
        assert server()[0] == after["id"], result

    setup("miss", "minecraft:stone")
    result = watch(["BlockInteracted"], "Mouse(right)", "Wait(10)", ticks=11)
    assert payloads(result, []) == [] and server()[0] == "minecraft:stone", result
    paused_by(result, [])

    setup("survival", "minecraft:stone")
    result = watch(["BlockBroken"], "MousePress(left)", "Wait(1)", "MouseRelease(left)", "Wait(8)", ticks=11)
    assert payloads(result, []) == [] and server()[0] == "minecraft:stone", result
    paused_by(result, [])
    broken = {"dimension": DIMENSION, "position": TARGET, "id": "minecraft:stone"}
    result = watch(["BlockBroken"], "MousePress(left)", "Wait(30)", image="survival-break")
    assert payloads(result, ["BlockBroken"]) == [broken] and server()[0] == "minecraft:air", result
    paused_by(result, ["BlockBroken"])
    assert result["ticks"] > 1, result
    send(["CLEAR", "MouseRelease(left)"], 1)
    setup("creative", "minecraft:stone")
    result = watch(["BlockBroken"], "Mouse(left)", "Wait(12)")
    assert payloads(result, ["BlockBroken"]) == [broken] and server()[0] == "minecraft:air", result
    paused_by(result, ["BlockBroken"])
    setup("creative-door", door)
    assert server(lower=True)[0] == door
    result = watch(["BlockBroken"], "Mouse(left)", "Wait(12)")
    assert payloads(result, ["BlockBroken"]) == [dict(broken, id=door)], result
    assert server()[0] == server(lower=True)[0] == "minecraft:air", result
    paused_by(result, ["BlockBroken"])

    setup("chest", "minecraft:chest")
    result = send(["CLEAR", *handler("BlockInteracted", "Wait(5)"), *handler("ScreenOpened", "Wait(5)"),
                   "Mouse(right)", "Wait(20)"], image="action-chest")
    observed = {event["event"]: event for event in result["events"]}
    assert len(result["events"]) == len(observed) == 2, result
    chest = {"id": "minecraft:chest", "facing": "north", "type": "single", "waterlogged": False}
    clicked = observed["BlockInteracted"]
    assert {key: value for key, value in clicked.items() if key not in {"event", "listeners"}} == (
        interaction(TARGET, "main_hand", None, chest, chest)), result
    for event, line in [("BlockInteracted", 3), ("ScreenOpened", 7)]:
        origin, = observed[event]["listeners"]
        assert (origin["event"], origin["line"]) == (event, line), result
    assert observed["ScreenOpened"]["screen"] == query_game(rpc)["screen"] == "ContainerScreen", result
    assert server()[0] == chest["id"], result
    for screen, open_key, close_key in [
        (None, None, "escape"), ("InventoryScreen", "e", "escape"), ("AdvancementsScreen", "l", "escape"),
    ]:
        if open_key:
            world = "not_needed" if screen == "AdvancementsScreen" else "paused"
            result = watch(["ScreenOpened"], f"Key({open_key})", "Wait(12)", image=screen, world=world)
            assert payloads(result, ["ScreenOpened"]) == [{"screen": screen}], result
            paused_by(result, ["ScreenOpened"])
            assert query_game(rpc)["screen"] == screen, result
        result = watch(["ScreenOpened"], f"Key({close_key})", "Wait(5)", ticks=6)
        assert payloads(result, []) == [] and query_game(rpc)["screen"] is None, result
        paused_by(result, [])

    setup("flat")
    result = watch(["LeftGround"], "KeyPress(w)", "Wait(3)", "KeyRelease(w)", ticks=5)
    assert payloads(result, []) == [] and server()[2], result
    for _ in range(2):
        result = watch(["LeftGround"], "Key(space)", "Wait(12)", image="jump-off-ground")
        point, = payloads(result, ["LeftGround"])
        paused_by(result, ["LeftGround"])
        assert point["dimension"] == DIMENSION and 95 < point["position"]["y"] < 96, result
        # Re-registering and continuing during the same jump cannot create another takeoff.
        result = watch(["LeftGround"], "Wait(4)", ticks=4)
        assert payloads(result, []) == [] and query_game(rpc)["player"]["position"]["y"] > 95, result
        result = watch(["LeftGround"], "Wait(18)", ticks=18)
        assert payloads(result, []) == [] and server()[2], result
    setup("edge")
    result = watch(["LeftGround"], "KeyPress(w)", "Wait(22)", image="walk-off-edge")
    paused_by(result, ["LeftGround"])
    point, = payloads(result, ["LeftGround"])
    assert point["dimension"] == DIMENSION and 1.2 < point["position"]["z"] < 1.7, result
    assert 94.5 < point["position"]["y"] <= 95, result
    result = watch(["LeftGround"], "KeyRelease(w)", "Wait(6)", ticks=7)
    height = query_game(rpc)["player"]["position"]["y"]
    assert payloads(result, []) == [] and 81 < height < 95, (result, height)

    setup("shield")
    result = watch(["Hurt", "DamageBlocked"], "MousePress(right)", "Wait(10)", "MouseRelease(right)", ticks=12)
    assert payloads(result, []) == [], result
    for mode, expected, amount in [("shield", ["DamageBlocked"], 1), ("partial-shield", ["Hurt", "DamageBlocked"], .5),
                                   ("gold", ["Hurt"], None)]:
        offset = len(log.read_text())
        pending = work / "test-hit.tmp"
        pending.write_text(mode)
        pending.replace(work / "test-hit")
        # Both packets can be observed across an input boundary. Give each handler
        # time to observe the second packet before the first handler stops playback.
        result = send(["CLEAR", *handler("Hurt", "Wait(3)", "PAUSE"),
                       *handler("DamageBlocked", "Wait(3)", "PAUSE"),
                       "Wait(18)"], image=f"action-{mode}")
        direct = [origin["event"] for origin in result["pause_listeners"]]
        assert direct and direct == [event for event in expected if event in direct], result
        paused_by(result, direct)
        observed = ["DamageBlocked", "Hurt"] if mode == "partial-shield" else expected
        assert payloads(result, observed) == [
            {"item": "minecraft:shield", "blocked_damage": amount} if event == "DamageBlocked" else {}
            for event in observed
        ], result
        assert f"AGENT_HIT {mode} {str(mode != 'shield').lower()}" in log.read_text()[offset:], mode

    observer_input(work, "action-fixture restore")
    assert send(["CLEAR", "Wait(6)"], 6)["events"] == []
    print("PASS: native block interactions and mining, screen transitions, takeoff edges, blocked damage and MCP event records.")
