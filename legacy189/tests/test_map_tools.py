import gzip
import hashlib
import json
import pathlib
import struct
import sys
import tempfile
import unittest
import zlib
from unittest.mock import patch

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools"))
import inventory
import map_blueprint as blueprint
from nbt import MAX_BYTES, block_states, decompress, parse, region_chunk


def text(value):
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def tag(kind, name, payload):
    return bytes([kind]) + text(name) + payload


def root(*tags):
    return b"\x0a\0\0" + b"".join(tags) + b"\0"


class NbtTests(unittest.TestCase):
    def test_java_strings_nested_values_and_all_array_types(self):
        payload = root(
            tag(10, "Data", tag(8, "name", text("地图")) + b"\0"),
            tag(7, "bytes", struct.pack(">i", 3) + b"\x00\x7f\xff"),
            tag(11, "ints", struct.pack(">iii", 2, -4, 99)),
            tag(12, "longs", struct.pack(">iqq", 2, -1, 2**48)),
            tag(9, "list", b"\x03" + struct.pack(">iii", 2, 42, -9)),
        )
        parsed = parse(payload)
        self.assertEqual(10, parsed.kind)
        self.assertEqual("地图", parsed.plain()["Data"]["name"])
        self.assertEqual(b"\0\x7f\xff", parsed.plain()["bytes"])
        self.assertEqual([-4, 99], parsed.plain()["ints"])
        self.assertEqual([-1, 2**48], parsed.plain()["longs"])
        self.assertEqual([42, -9], parsed.plain()["list"])

    def test_modified_utf8_supplementary_character_and_nul(self):
        raw = b"\xed\xa0\xbd\xed\xb8\x80\xc0\x80"
        value = parse(root(tag(8, "name", struct.pack(">H", len(raw)) + raw)))
        self.assertEqual("😀\0", value.plain()["name"])

    def test_negative_truncated_duplicate_and_trailing_data_are_rejected(self):
        malformed = [root(tag(7, "a", struct.pack(">i", -1))), root()[:-1], root() + b"extra",
                     root(tag(1, "x", b"\x01"), tag(1, "x", b"\x02")),
                     root(tag(9, "a", b"\0" + struct.pack(">i", 1)))]
        for payload in malformed:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                parse(payload)

    def test_structural_budget(self):
        value = b"\0"
        for _ in range(70):
            value = tag(10, "x", value) + b"\0"
        with self.assertRaises(ValueError):
            parse(b"\x0a\0\0" + value)

    def test_codecs_limits_truncation_and_external_chunks(self):
        value = root(tag(3, "test", struct.pack(">i", 7)))
        self.assertEqual(value, decompress(gzip.compress(value), 1))
        self.assertEqual(value, decompress(zlib.compress(value), 2))
        self.assertEqual(value, decompress(value, 3))
        for data, codec in [(b"", 4), (b"", 130), (zlib.compress(value)[:-2], 2),
                            (zlib.compress(value) + b"tail", 2),
                            (zlib.compress(b"0" * (MAX_BYTES + 1)), 2)]:
            with self.subTest(codec=codec), self.assertRaises(ValueError):
                decompress(data, codec)

    def test_real_anvil_header_negative_coordinates_and_coordinate_check(self):
        with tempfile.TemporaryDirectory() as directory:
            file = pathlib.Path(directory) / "r.-1.1.mca"
            nbt = root(tag(3, "xPos", struct.pack(">i", -1)), tag(3, "zPos", struct.pack(">i", 49)))
            compressed = zlib.compress(nbt)
            location = ((-1 % 32) + (49 % 32) * 32) * 4
            data = bytearray(12288)
            data[location:location + 4] = b"\0\0\x02\x01"
            chunk = struct.pack(">iB", len(compressed) + 1, 2) + compressed
            data[8192:8192 + len(chunk)] = chunk
            file.write_bytes(data)
            self.assertEqual(-1, region_chunk(pathlib.Path(directory), -1, 49)["xPos"])
            data[location:location + 4] = b"\0\0\x01\x01"
            file.write_bytes(data)
            with self.assertRaises(ValueError):
                region_chunk(pathlib.Path(directory), -1, 49)

    def test_signed_long_and_five_bit_padding_do_not_cross_word_boundaries(self):
        palette = [{"Name": f"test:block_{n}"} for n in range(17)]
        indices = [n % 17 for n in range(4096)]
        words = []
        for start in range(0, 4096, 12):
            word = sum(index << (5 * n) for n, index in enumerate(indices[start:start + 12]))
            words.append(word)
        actual = block_states({"block_states": {"palette": palette, "data": words}})
        self.assertEqual([palette[index] for index in indices], actual)
        # Four-bit words can have the sign bit set, as Java NBT longs do.
        palette = [{"Name": f"test:block_{n}"} for n in range(16)]
        self.assertEqual([palette[15]] * 4096,
                         block_states({"block_states": {"palette": palette, "data": [-1] * 256}}))

    def test_missing_palette_out_of_range_index_and_bad_lengths_fail(self):
        palette = [{"Name": "minecraft:air"}, {"Name": "minecraft:stone"}]
        for section in [{}, {"block_states": {"palette": []}},
                        {"block_states": {"palette": palette, "data": [0]}},
                        {"block_states": {"palette": palette, "data": [2] * 256}}]:
            with self.assertRaises(ValueError):
                block_states(section)


class MapTests(unittest.TestCase):
    def synthetic_chunk(self, _, cx, cz):
        # This fixture contains full support and clear space, never real combat data.
        words = []
        for y in range(16):
            word = 0x1111111111111111 if y <= 4 else 0
            words.extend([word] * 16)
        return {"DataVersion": 4790, "Status": "minecraft:full", "xPos": cx, "zPos": cz,
                "block_entities": [], "sections": [{"Y": 6, "block_states": {
                    "palette": [{"Name": "minecraft:air"}, {"Name": "minecraft:polished_deepslate"}],
                    "data": words}}]}

    def fixture_world(self, world):
        (world / "data").mkdir()
        (world / "data/divzero-pvp-map.json").write_text('{"schema":1,"map":"divzero_pvp"}')
        data = tag(3, "DataVersion", struct.pack(">i", 4790)) + b"\0"
        (world / "level.dat").write_bytes(gzip.compress(root(tag(10, "Data", data))))
        region = world / "dimensions/minecraft/overworld/region"
        region.mkdir(parents=True)
        for x in (-1, 0):
            (region / f"r.{x}.1.mca").write_bytes(b"fixture-only")

    def test_full_volume_air_modern_names_and_floor_division_are_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            world = pathlib.Path(temporary)
            self.fixture_world(world)
            with patch.object(blueprint, "region_chunk", self.synthetic_chunk):
                result = blueprint.extract(world)
            self.assertFalse(result["playable"])
            self.assertEqual(33670, result["blocks"])
            self.assertEqual(37 * 65 * 4, result["blockCounts"]["minecraft:polished_deepslate"])
            self.assertEqual(37 * 65 * 10, result["blockCounts"]["minecraft:air"])
            self.assertFalse(result["spawns"]["human"]["nativeCollisionVerified"])
            blueprint.validate(json.loads(json.dumps(result)))
            result["runs"][0][1] += 1
            with self.assertRaises(ValueError):
                blueprint.validate(result)

    def test_block_entities_are_not_silently_discarded(self):
        with tempfile.TemporaryDirectory() as temporary:
            world = pathlib.Path(temporary)
            self.fixture_world(world)
            def chunk(*args):
                value = self.synthetic_chunk(*args)
                value["block_entities"] = [{"x": 0, "y": 100, "z": 800, "id": "minecraft:chest"}]
                return value
            with patch.object(blueprint, "region_chunk", chunk), self.assertRaisesRegex(ValueError, "block entity"):
                blueprint.extract(world)

    def test_new_version_is_not_guessed(self):
        with tempfile.TemporaryDirectory() as temporary:
            world = pathlib.Path(temporary)
            self.fixture_world(world)
            data = tag(3, "DataVersion", struct.pack(">i", 9999)) + b"\0"
            (world / "level.dat").write_bytes(gzip.compress(root(tag(10, "Data", data))))
            with self.assertRaisesRegex(ValueError, "DataVersion"):
                blueprint.extract(world)


class InventoryTests(unittest.TestCase):
    def test_variants_and_fixtures_remain_visible_private_data_is_not_read(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = pathlib.Path(temporary)
            for module in inventory.MODULES:
                (source / module / "src/main/java").mkdir(parents=True)
            (source / "core/src/main/variants/bundled/java").mkdir(parents=True)
            (source / "core/src/main/variants/bundled/java/Host.java").write_text('class Host { void x(){tool("python_execute");} }')
            (source / "neoforge/src/main/java/Fixture.java").write_text("import net.minecraft.server.MinecraftServer;\nclass Fixture {}")
            (source / "private.json").write_text("DO_NOT_READ")
            result = inventory.collect(source)
            self.assertEqual(2, result["javaSourceFiles"])
            self.assertIn("python_execute", result["staticToolDeclarationSites"])
            self.assertEqual(1, result["dependencySourceCounts"]["minecraft"])
            self.assertTrue(any(row["fixtureNameHint"] for row in result["sources"]))
            self.assertNotIn("DO_NOT_READ", json.dumps(result))
            self.assertNotIn(str(source), json.dumps(result))
            self.assertFalse(result["nativeParityVerified"])

    def test_kubejs_is_explicitly_excluded_but_divzero_ui_remains_in_scope(self):
        data = json.loads((pathlib.Path(__file__).resolve().parents[1] / "requirements.json").read_text())
        self.assertTrue(any("KubeJS" in exclusion for exclusion in data["explicitExclusions"]))
        ids = {row["id"] for row in data["requirements"]}
        self.assertIn("dynamic_ui_scripts", ids)
        self.assertNotIn("kubejs", ids)
        self.assertEqual(len(ids), len(data["requirements"]))


if __name__ == "__main__":
    unittest.main()
