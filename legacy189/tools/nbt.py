"""Bounded Java-edition NBT/Anvil reader for the offline legacy migration tools.

No world writes, arbitrary object loading, external chunks, or silent codec fallback.
Retains NBT tag types so block entity data need not be guessed during migration.
"""
from __future__ import annotations

import io
import pathlib
import struct
import zlib
from dataclasses import dataclass

MAX_BYTES = 32 * 1024 * 1024
MAX_DEPTH = 64
MAX_NODES = 1_000_000


@dataclass(frozen=True)
class Tag:
    kind: int
    value: object

    def plain(self):
        if self.kind == 10:
            return {name: value.plain() for name, value in self.value.items()}
        if self.kind == 9:
            return [value.plain() for value in self.value[1]]
        return self.value


def encode(root: Tag) -> bytes:
    """Write typed NBT without guessing numeric or array types during migration."""
    output = io.BytesIO()

    def number(fmt, value):
        output.write(struct.pack(">" + fmt, value))

    def string(value):
        # DataOutput.writeUTF encodes UTF-16 units as modified UTF-8, including NUL.
        utf16 = value.encode("utf-16-be", errors="strict")
        units = "".join(chr(struct.unpack(">H", utf16[i:i + 2])[0]) for i in range(0, len(utf16), 2))
        raw = units.encode("utf-8", errors="surrogatepass").replace(b"\0", b"\xc0\x80")
        if len(raw) > 65535:
            raise ValueError("NBT string exceeds modified UTF length")
        number("H", len(raw)); output.write(raw)

    def payload(tag, depth=0):
        if depth > MAX_DEPTH:
            raise ValueError("NBT depth exceeded")
        kind, value = tag.kind, tag.value
        if 1 <= kind <= 6:
            number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind], value)
        elif kind == 7:
            number("i", len(value)); output.write(value)
        elif kind == 8:
            string(value)
        elif kind == 9:
            child, values = value
            if child == 0 and values or any(item.kind != child for item in values):
                raise ValueError("NBT list element type mismatch")
            number("B", child); number("i", len(values))
            for item in values:
                payload(item, depth + 1)
        elif kind == 10:
            for name, item in value.items():
                if item.kind == 0:
                    raise ValueError("End tag cannot be a named field")
                number("B", item.kind); string(name); payload(item, depth + 1)
            number("B", 0)
        elif kind in (11, 12):
            number("i", len(value))
            for item in value:
                number("i" if kind == 11 else "q", item)
        else:
            raise ValueError("Unknown NBT kind")
        if output.tell() > MAX_BYTES:
            raise ValueError("NBT byte budget exceeded")

    if root.kind != 10:
        raise ValueError("NBT root must be a compound")
    number("B", 10); string(""); payload(root)
    return output.getvalue()


def decompress(data: bytes, codec: int) -> bytes:
    if codec not in (1, 2, 3):
        raise ValueError(f"Unsupported Anvil codec: {codec}")
    if codec == 3:
        if len(data) > MAX_BYTES:
            raise ValueError("NBT byte budget exceeded")
        return data
    decoder = zlib.decompressobj(31 if codec == 1 else 15)
    result = decoder.decompress(data, MAX_BYTES + 1)
    if len(result) > MAX_BYTES or not decoder.eof or decoder.unused_data:
        raise ValueError("Truncated, concatenated, or oversized compressed NBT")
    return result


def parse(data: bytes) -> Tag:
    if len(data) > MAX_BYTES:
        raise ValueError("NBT byte budget exceeded")
    stream = io.BytesIO(data)
    remaining_nodes = MAX_NODES

    def read(size):
        if size < 0 or size > MAX_BYTES:
            raise ValueError("Invalid NBT length")
        result = stream.read(size)
        if len(result) != size:
            raise ValueError("Truncated NBT")
        return result

    def number(fmt):
        return struct.unpack(">" + fmt, read(struct.calcsize(">" + fmt)))[0]

    def string():
        # Java DataInput's modified UTF-8 encodes NUL and UTF-16 surrogate pairs.
        raw = read(number("H")).replace(b"\xc0\x80", b"\0")
        text = raw.decode("utf-8", errors="surrogatepass")
        return text.encode("utf-16", errors="surrogatepass").decode("utf-16")

    def count(width=1):
        size = number("i")
        if size < 0 or size > MAX_BYTES // width:
            raise ValueError("Invalid NBT array length")
        return size

    def payload(kind, depth=0):
        nonlocal remaining_nodes
        remaining_nodes -= 1
        if remaining_nodes < 0 or depth > MAX_DEPTH:
            raise ValueError("NBT structural budget exceeded")
        if 1 <= kind <= 6:
            value = number({1: "b", 2: "h", 3: "i", 4: "q", 5: "f", 6: "d"}[kind])
        elif kind == 7:
            value = read(count())
        elif kind == 8:
            value = string()
        elif kind == 9:
            child, length = number("B"), count()
            if not 0 <= child <= 12 or (length and child == 0) or length > remaining_nodes:
                raise ValueError("Invalid NBT list")
            value = (child, [payload(child, depth + 1) for _ in range(length)])
        elif kind == 10:
            value = {}
            while (child := number("B")):
                name = string()
                if name in value:
                    raise ValueError("Duplicate NBT compound key")
                value[name] = payload(child, depth + 1)
        elif kind in (11, 12):
            width, fmt = (4, "i") if kind == 11 else (8, "q")
            length = count(width)
            value = list(struct.unpack(">" + str(length) + fmt, read(length * width)))
        else:
            raise ValueError(f"Unsupported NBT tag: {kind}")
        return Tag(kind, value)

    kind = number("B")
    if kind != 10:
        raise ValueError("NBT root must be a compound")
    string()
    result = payload(kind)
    if stream.read(1):
        raise ValueError("Trailing NBT bytes")
    return result


def region_chunk(region_root: pathlib.Path, x: int, z: int) -> dict:
    path = region_root / f"r.{x // 32}.{z // 32}.mca"
    with path.open("rb") as stream:
        size = path.stat().st_size
        if size < 8192 or size % 4096:
            raise ValueError("Invalid Anvil file size")
        stream.seek(((x % 32) + (z % 32) * 32) * 4)
        location = stream.read(4)
        sector, sectors = int.from_bytes(location[:3], "big"), location[3]
        if sector < 2 or sectors == 0 or (sector + sectors) * 4096 > size:
            raise ValueError(f"Missing or invalid chunk {x},{z}")
        stream.seek(sector * 4096)
        length = int.from_bytes(stream.read(4), "big")
        if length < 2 or length + 4 > sectors * 4096:
            raise ValueError("Invalid Anvil chunk length")
        codec = stream.read(1)[0]
        result = parse(decompress(stream.read(length - 1), codec)).plain()
        if result.get("xPos") != x or result.get("zPos") != z:
            raise ValueError("Anvil chunk coordinate mismatch")
        return result


def block_states(section: dict) -> list[dict]:
    states = section.get("block_states")
    if not isinstance(states, dict):
        raise ValueError("Missing section block states")
    palette = states.get("palette")
    if not isinstance(palette, list) or not 1 <= len(palette) <= 4096:
        raise ValueError("Invalid block palette")
    for entry in palette:
        if not isinstance(entry, dict) or not isinstance(entry.get("Name"), str):
            raise ValueError("Invalid block state")
        if not set(entry).issubset({"Name", "Properties"}):
            raise ValueError("Unrecognized block-state field")
        properties = entry.get("Properties", {})
        if not isinstance(properties, dict) or not all(
            isinstance(k, str) and isinstance(v, str) for k, v in properties.items()
        ):
            raise ValueError("Invalid block properties")
    words = states.get("data", [])
    if len(palette) == 1:
        if words:
            raise ValueError("Unexpected singleton palette data")
        return [palette[0]] * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per_word = 64 // bits
    if len(words) != (4096 + per_word - 1) // per_word:
        raise ValueError("Invalid padded block-state storage length")
    result = []
    for index in range(4096):
        value = (words[index // per_word] >> ((index % per_word) * bits)) & ((1 << bits) - 1)
        if value >= len(palette):
            raise ValueError("Palette index out of range")
        result.append(palette[value])
    return result
