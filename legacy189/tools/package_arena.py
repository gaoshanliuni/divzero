"""Export a new clean 1.8.9 arena template from a closed, verified native save.

Only arena chunks, required Forge registry metadata and a new DivZero world
identity are exported. Player/agent data, drops, scores, logs and service data
never enter the output. Source files are read-only; existing output is refused.
"""
from __future__ import annotations
import argparse
import gzip
import hashlib
import json
import pathlib
import struct
import time
import uuid
import zipfile
import zlib
from nbt import Tag, decompress, encode, parse

NAME = "DivZero PvP 1.8.9"


def compound(**values):
    return Tag(10, values)


def clean_level(root: Tag) -> Tag:
    data = root.value["Data"].value
    if data["version"].value != 19133:
        raise ValueError("Expected a native legacy Anvil save")
    data.pop("Player", None)
    data.update(LevelName=Tag(8, NAME), SpawnX=Tag(3, 0), SpawnY=Tag(3, 101), SpawnZ=Tag(3, 766),
                GameType=Tag(3, 0), allowCommands=Tag(1, 0), hardcore=Tag(1, 0), Difficulty=Tag(1, 2),
                raining=Tag(1, 0), thundering=Tag(1, 0), Time=Tag(4, 6000), DayTime=Tag(4, 6000), LastPlayed=Tag(4, int(time.time() * 1000)))
    rules = data["GameRules"].value
    rules.update(doMobSpawning=Tag(8, "false"), doDaylightCycle=Tag(8, "false"), doFireTick=Tag(8, "false"), keepInventory=Tag(8, "false"))
    # Client-only Mods have no world registrations in this supported template.
    fml = root.value["FML"].value
    records = fml["ModList"].value[1]
    fml["ModList"] = Tag(9, (10, [entry for entry in records if entry.value["ModId"].value in ("minecraft", "mcp", "FML", "Forge", "mineagent_runtime")]))
    return root


def clean_identity(root: Tag) -> Tag:
    data = root.value["data"].value
    if data["schema"].value != 1 or not data.get("arenaHash", Tag(8, "")).value:
        raise ValueError("Arena has not passed native import verification")
    return compound(data=compound(schema=Tag(3, 1), arenaHash=data["arenaHash"], identity=Tag(8, str(uuid.uuid4())),
                                  modernCombat=Tag(1, 1), enabled=Tag(9, (8, [])), agents=Tag(9, (10, []))))


def read_chunk(raw, x, z, clean=True):
    index = (x % 32) + (z % 32) * 32
    sector = int.from_bytes(raw[index * 4:index * 4 + 3], "big")
    sectors = raw[index * 4 + 3]
    if sector < 2 or not sectors or (sector + sectors) * 4096 > len(raw):
        raise ValueError(f"Arena chunk missing: {x},{z}")
    at = sector * 4096
    length = struct.unpack(">I", raw[at:at + 4])[0]
    if length < 2 or length + 4 > sectors * 4096:
        raise ValueError("Invalid chunk size")
    result = parse(decompress(raw[at + 5:at + 4 + length], raw[at + 4]))
    level = result.value["Level"].value
    if level["xPos"].value != x or level["zPos"].value != z:
        raise ValueError("Arena chunk coordinate mismatch")
    if level.get("TileEntities", Tag(9, (10, []))).value[1]:
        raise ValueError("Arena template contains a block entity needing explicit review")
    if clean:
        level["Entities"] = Tag(9, (10, []))
    return result


def write_region(chunks):
    header = bytearray(8192)
    body = bytearray()
    for (x, z), chunk in sorted(chunks.items()):
        compressed = zlib.compress(encode(chunk))
        packed = struct.pack(">IB", len(compressed) + 1, 2) + compressed
        sectors = (len(packed) + 4095) // 4096
        if sectors > 255:
            raise ValueError("External chunks are not supported")
        offset = 2 + len(body) // 4096
        index = ((x % 32) + (z % 32) * 32) * 4
        header[index:index + 4] = offset.to_bytes(3, "big") + bytes([sectors])
        header[4096 + index:4100 + index] = struct.pack(">I", int(time.time()))
        body.extend(packed); body.extend(b"\0" * (sectors * 4096 - len(packed)))
    return bytes(header + body)


def package(source, output, archive):
    source = source.resolve(strict=True); output = output.resolve(); archive = archive.resolve()
    if output.exists() or archive.exists() or output.is_relative_to(source) or source.is_relative_to(output) or archive.is_relative_to(source):
        raise ValueError("Export requires new paths outside the source world")
    level = clean_level(parse(decompress((source / "level.dat").read_bytes(), 1)))
    identity = clean_identity(parse(decompress((source / "data/divzero_legacy_world.dat").read_bytes(), 1)))
    files = {"level.dat": gzip.compress(encode(level), mtime=0), "data/divzero_legacy_world.dat": gzip.compress(encode(identity), mtime=0)}
    for rx in (-1, 0):
        region = f"r.{rx}.1.mca"
        raw = (source / "region" / region).read_bytes()
        chunks = {(x, z): read_chunk(raw, x, z) for x in range(-2, 2) if x // 32 == rx for z in range(47, 52)}
        files["region/" + region] = write_region(chunks)
    # Validate the serialized results before creating any output directory.
    for name in ("level.dat", "data/divzero_legacy_world.dat"):
        parse(decompress(files[name], 1))
    for rx in (-1, 0):
        raw = files[f"region/r.{rx}.1.mca"]
        for x in range(-2, 2):
            if x // 32 == rx:
                for z in range(47, 52):
                    if read_chunk(raw, x, z, clean=False).value["Level"].value["Entities"].value[1]:
                        raise ValueError("Entities survived export")
    output.mkdir(parents=True)
    for name, data in files.items():
        destination = output / name; destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as stream: stream.write(data)
    manifest = {"kind": "legacy189-arena-development-template", "minecraft": "1.8.9", "forge": "11.15.1.2318", "requiredMod": "mineagent_runtime",
                "fullModParity": False, "chunks": 20, "players": 0, "agents": 0, "commandsEnabled": False,
                "arenaHash": identity.value["data"].value["arenaHash"].value,
                "files": {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}}
    (output / "arena-template.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    archive.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as package_file:
        for file in sorted(output.rglob("*")):
            if file.is_file(): package_file.write(file, NAME + "/" + file.relative_to(output).as_posix())
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--zip", type=pathlib.Path, required=True)
    args = parser.parse_args()
    print(json.dumps(package(args.source, args.output, args.zip), ensure_ascii=False))
