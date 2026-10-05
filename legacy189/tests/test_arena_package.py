import gzip
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "tools"))
from nbt import Tag, encode, parse
from package_arena import clean_identity, clean_level, compound, read_chunk, write_region


class ArenaPackagingTest(unittest.TestCase):
    def test_typed_roundtrip_preserves_modified_utf8_and_arrays(self):
        original = compound(name=Tag(8, "地图😀\0"), byte=Tag(1, -7), short=Tag(2, 20), int=Tag(3, 100), long=Tag(4, 2**50),
                            float=Tag(5, 1.25), double=Tag(6, 1.5), bytes=Tag(7, b"\0\xff"), ints=Tag(11, [-7, 25]), longs=Tag(12, [-1, 2**50]),
                            children=Tag(9, (10, [compound(value=Tag(8, "owned"))])))
        self.assertEqual(original, parse(encode(original)))
        with self.assertRaises(ValueError): encode(compound(bad=Tag(9, (8, [Tag(3, 1)]))))

    def test_template_has_no_player_op_state_or_old_identity(self):
        level = compound(Data=compound(version=Tag(3, 19133), Player=compound(secret=Tag(8, "private-player")), GameRules=compound()),
                         FML=compound(ModList=Tag(9, (10, [compound(ModId=Tag(8, "mineagent_runtime")), compound(ModId=Tag(8, "client_only"))]))))
        cleaned = clean_level(level)
        self.assertNotIn("Player", cleaned.value["Data"].value)
        self.assertEqual(0, cleaned.value["Data"].value["allowCommands"].value)
        self.assertEqual(101, cleaned.value["Data"].value["SpawnY"].value)
        self.assertEqual(1, len(cleaned.value["FML"].value["ModList"].value[1]))
        owned = clean_identity(compound(data=compound(schema=Tag(3, 1), arenaHash=Tag(8, "a" * 64), identity=Tag(8, "old"), enabled=Tag(9, (8, [Tag(8, "player")])), agents=Tag(9, (10, [compound()])))))
        self.assertNotEqual("old", owned.value["data"].value["identity"].value)
        self.assertFalse(owned.value["data"].value["agents"].value[1])
        self.assertFalse(owned.value["data"].value["enabled"].value[1])

    def test_negative_chunk_coordinates_and_entity_removal(self):
        chunk = compound(Level=compound(xPos=Tag(3, -1), zPos=Tag(3, 49), Entities=Tag(9, (10, [compound(id=Tag(8, "Item"))])), TileEntities=Tag(9, (10, []))))
        raw = write_region({(-1, 49): chunk})
        cleaned = read_chunk(raw, -1, 49)
        self.assertFalse(cleaned.value["Level"].value["Entities"].value[1])
        with self.assertRaises(ValueError): read_chunk(raw, -2, 49)
        chunk.value["Level"].value["TileEntities"] = Tag(9, (10, [compound(id=Tag(8, "Chest"))]))
        with self.assertRaises(ValueError): read_chunk(write_region({(-1, 49): chunk}), -1, 49)


if __name__ == "__main__": unittest.main()
