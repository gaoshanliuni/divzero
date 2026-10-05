"""Extract the marked DivZero arena without changing blocks or opening its world.

This is a lossless arena transfer artifact, NOT a playable 1.8.9 save. A native
importer must resolve every state and verify it before enabling the duel marker.
World terrain, player data and modern server configuration are never copied.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import pathlib
from collections import Counter

from nbt import block_states, decompress, parse, region_chunk

MINIMUM = (-18, 97, 754)
MAXIMUM = (18, 110, 818)
SPAWNS = {"lobby": (0.5, 101.0, 766.5), "human": (-4.5, 101.0, 800.5), "ai": (5.5, 101.0, 800.5)}
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def canonical(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def inside(x, y, z):
    return all(a <= v <= b for v, a, b in zip((x, y, z), MINIMUM, MAXIMUM))


def extract(world: pathlib.Path) -> dict:
    world = world.resolve(strict=True)
    marker_path = world / "data/divzero-pvp-map.json"
    if marker_path.stat().st_size > 4096:
        raise ValueError("Oversized map marker")
    marker = json.loads(marker_path.read_text(encoding="utf-8-sig"))
    if marker != {"schema": 1, "map": "divzero_pvp"}:
        raise ValueError("Not the supported marked DivZero PvP map")
    level_file = world / "level.dat"
    if level_file.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("Oversized level.dat")
    level = parse(decompress(level_file.read_bytes(), 1)).plain()["Data"]
    version = level.get("DataVersion")
    if version != 4790:
        raise ValueError(f"Unreviewed source DataVersion: {version}")
    regions = world / "dimensions/minecraft/overworld/region"
    chunks, fingerprints = {}, {}
    for cx in range(MINIMUM[0] // 16, MAXIMUM[0] // 16 + 1):
        for cz in range(MINIMUM[2] // 16, MAXIMUM[2] // 16 + 1):
            chunk = region_chunk(regions, cx, cz)
            if chunk.get("DataVersion") != version or chunk.get("Status") not in ("minecraft:full", "full"):
                raise ValueError("Chunk is incomplete or has a different data version")
            for entity in chunk.get("block_entities", []):
                if inside(entity["x"], entity["y"], entity["z"]):
                    raise ValueError("Arena contains a block entity; typed NBT migration is required")
            sections = {}
            for section in chunk["sections"]:
                sy = section["Y"]
                if MINIMUM[1] // 16 <= sy <= MAXIMUM[1] // 16:
                    if sy in sections:
                        raise ValueError("Duplicate arena section")
                    sections[sy] = block_states(section)
            chunks[(cx, cz)] = sections
            file = regions / f"r.{cx // 32}.{cz // 32}.mca"
            if file.name not in fingerprints:
                fingerprints[file.name] = hashlib.sha256(file.read_bytes()).hexdigest()

    def state_at(x, y, z):
        sections = chunks[(x // 16, z // 16)]
        if y // 16 not in sections:
            raise ValueError("Missing arena section; refusing to guess air")
        return sections[y // 16][((y % 16) * 16 + z % 16) * 16 + x % 16]

    palette, lookup, runs, counts = [], {}, [], Counter()
    # Anvil-compatible Y/Z/X order, with X fastest. Every air block is retained.
    for y in range(MINIMUM[1], MAXIMUM[1] + 1):
        for z in range(MINIMUM[2], MAXIMUM[2] + 1):
            for x in range(MINIMUM[0], MAXIMUM[0] + 1):
                state = state_at(x, y, z)
                key = canonical(state)
                if key not in lookup:
                    lookup[key] = len(palette)
                    palette.append(state)
                index = lookup[key]
                counts[state["Name"]] += 1
                if runs and runs[-1][0] == index:
                    runs[-1][1] += 1
                else:
                    runs.append([index, 1])
    spawn_checks = {}
    for name, position in SPAWNS.items():
        x, y, z = map(math.floor, position)
        feet, head, floor = state_at(x, y, z), state_at(x, y + 1, z), state_at(x, y - 1, z)
        # Offline geometry only. Actual collision shapes still require Native validation.
        if feet["Name"] not in AIR or head["Name"] not in AIR or floor["Name"] in AIR:
            raise ValueError(f"Blocked or unsupported spawn: {name}")
        spawn_checks[name] = {"position": list(position), "support": floor, "nativeCollisionVerified": False}
    result = {
        "schema": 1, "kind": "divzero-arena-transfer", "playable": False,
        "sourceVersion": "26.1.2", "sourceDataVersion": version, "targetVersion": "1.8.9",
        "minimum": list(MINIMUM), "maximum": list(MAXIMUM), "order": "YZX_X_FASTEST",
        "palette": palette, "runs": runs, "blocks": sum(counts.values()),
        "blockCounts": dict(sorted(counts.items())), "spawns": spawn_checks,
        "sourceRegions": dict(sorted(fingerprints.items())),
        "roundLimitSeconds": 180, "countdownSeconds": 5,
        "modernMechanicsRequired": True, "boost": False,
        "notTransferred": ["players", "entities", "world identity", "configuration", "terrain outside bounds", "runtime database"],
        "requiredNativeChecks": ["all block states resolved exactly", "collision and lighting", "offhand and modern items", "duel AI and player damage", "round lifecycle and HUD", "wool cleanup and map protection", "world reopen"],
    }
    result["contentSha256"] = hashlib.sha256(canonical(result)).hexdigest()
    validate(result)
    # Detect a save racing the extraction, without locking or modifying a world.
    for name, expected in fingerprints.items():
        if hashlib.sha256((regions / name).read_bytes()).hexdigest() != expected:
            raise ValueError("Source world changed during extraction; close it and rerun")
    return result


def validate(result):
    if result.get("schema") != 1 or result.get("kind") != "divzero-arena-transfer" or result.get("playable") is not False:
        raise ValueError("Unsupported transfer artifact")
    minimum, maximum = result["minimum"], result["maximum"]
    if minimum != list(MINIMUM) or maximum != list(MAXIMUM) or result["order"] != "YZX_X_FASTEST":
        raise ValueError("Unexpected arena bounds or coordinate order")
    expected = math.prod(b - a + 1 for a, b in zip(minimum, maximum))
    palette, runs = result["palette"], result["runs"]
    if not isinstance(palette, list) or not 1 <= len(palette) <= 4096:
        raise ValueError("Invalid transfer palette")
    counts = Counter()
    for run in runs:
        if not isinstance(run, list) or len(run) != 2:
            raise ValueError("Invalid transfer run")
        index, count = run
        if type(index) is not int or type(count) is not int or not 0 <= index < len(palette) or not 1 <= count <= expected:
            raise ValueError("Invalid transfer index or run length")
        counts[palette[index]["Name"]] += count
    if sum(counts.values()) != expected or result["blocks"] != expected or dict(counts) != result["blockCounts"]:
        raise ValueError("Transfer block counts differ")
    unsigned = {k: v for k, v in result.items() if k != "contentSha256"}
    if hashlib.sha256(canonical(unsigned)).hexdigest() != result.get("contentSha256"):
        raise ValueError("Transfer checksum mismatch")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    world, output = args.world.resolve(strict=True), args.output.resolve()
    if output.is_relative_to(world) or output.exists():
        raise ValueError("Output must be a new file outside the source world")
    result = extract(world)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation also protects against a destination appearing mid-extraction.
    with output.open("xb") as stream:
        stream.write(canonical(result) + b"\n")
    print(json.dumps({"blocks": result["blocks"], "palette": len(result["palette"]), "contentSha256": result["contentSha256"], "playable": False}))


if __name__ == "__main__":
    main()
