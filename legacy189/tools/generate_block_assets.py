"""Deterministic original block textures/models for the legacy arena adapter.
No Minecraft textures are copied into the distribution. Existing vanilla leaves
are referenced by resource ID, so the player's resource packs still apply.
"""
import json
import pathlib
import random
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1] / "game/src/main/resources/assets/mineagent_runtime"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def png(path, pixels):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    data = b"".join(b"\0" + bytes(component for rgb in pixels[y * 16:(y + 1) * 16] for component in rgb) for y in range(16))
    encoded = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(data, 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def main():
    colors = {"polished_deepslate": (69, 70, 73), "smooth_stone": (156, 156, 156), "smooth_quartz": (235, 231, 223)}
    for name, color in colors.items():
        randomizer = random.Random(name)
        pixels = []
        for y in range(16):
            for x in range(16):
                grain = randomizer.randrange(-5, 6)
                if name == "polished_deepslate" and (y in (0, 8) or (x + (8 if y > 8 else 0)) % 16 == 0):
                    grain -= 15
                if name == "smooth_stone" and (x in (0, 15) or y in (0, 15)):
                    grain -= 12
                pixels.append(tuple(max(0, min(255, component + grain)) for component in color))
        png(ROOT / "textures/blocks" / (name + ".png"), pixels)
        write_json(ROOT / "blockstates" / (name + ".json"), {"variants": {"normal": {"model": "mineagent_runtime:" + name}}})
        write_json(ROOT / "models/block" / (name + ".json"), {"parent": "block/cube_all", "textures": {"all": "mineagent_runtime:blocks/" + name}})
        write_json(ROOT / "models/item" / (name + ".json"), {"parent": "mineagent_runtime:block/" + name})
    name = "acacia_leaves"
    variants = {f"distance={distance},persistent={str(persistent).lower()}": {"model": "mineagent_runtime:acacia_leaves"}
                for distance in range(1, 8) for persistent in (False, True)}
    write_json(ROOT / "blockstates/acacia_leaves.json", {"variants": variants})
    # A cube with tint-enabled faces follows the active biome foliage color.
    faces = {face: {"texture": "#all", "cullface": face, "tintindex": 0} for face in ("down", "up", "north", "south", "west", "east")}
    write_json(ROOT / "models/block/acacia_leaves.json", {"parent": "block/block", "textures": {"all": "blocks/leaves_acacia", "particle": "blocks/leaves_acacia"},
               "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}]})
    write_json(ROOT / "models/item/acacia_leaves.json", {"parent": "mineagent_runtime:block/acacia_leaves"})


if __name__ == "__main__":
    main()
