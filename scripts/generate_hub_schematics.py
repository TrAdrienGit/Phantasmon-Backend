"""Generates the Global Hub schematics shipped with the backend (D-34).

- hub_schematics/hub_global/phantasmon_default_hub.schem: the default 21x21x21 arena, to be replaced by the
  real build (the folder must hold exactly one .schem or .litematic file).
- src/test/resources/hub_schematics/hub_global/test_hub.schem: the one the tests start with.
- src/test/resources/schematics/*: fixtures of each format (Sponge v2, v3, Litematica) and a wrong size.

Also copies the format fixtures to the client's test resources (schematic parsing tests).
Run from anywhere: python scripts/generate_hub_schematics.py
"""
import gzip
import io
import math
import os
import shutil
import struct

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CLIENT_TEST = os.path.join(os.path.dirname(ROOT), "Phantasmon-Client", "src", "test", "resources", "schematics")
DATA_VERSION = 3955  # Minecraft 1.21.1


# ---- NBT writing (big-endian, gzip) ----

class Byte(int): pass
class Short(int): pass
class Int(int): pass
class Long(int): pass
class ByteArray(bytes): pass
class IntArray(list): pass
class LongArray(list): pass


def _tag_id(value):
    if isinstance(value, Byte): return 1
    if isinstance(value, Short): return 2
    if isinstance(value, Int): return 3
    if isinstance(value, Long): return 4
    if isinstance(value, ByteArray): return 7
    if isinstance(value, str): return 8
    if isinstance(value, IntArray): return 11
    if isinstance(value, LongArray): return 12
    if isinstance(value, list): return 9
    if isinstance(value, dict): return 10
    if isinstance(value, int): return 3
    raise TypeError(type(value))


def _string(out, s):
    data = s.encode("utf-8")
    out.write(struct.pack(">H", len(data)))
    out.write(data)


def _payload(out, value):
    tag = _tag_id(value)
    if tag == 1: out.write(struct.pack(">b", value))
    elif tag == 2: out.write(struct.pack(">h", value))
    elif tag == 3: out.write(struct.pack(">i", value))
    elif tag == 4: out.write(struct.pack(">q", value))
    elif tag == 7:
        out.write(struct.pack(">i", len(value)))
        out.write(value)
    elif tag == 8: _string(out, value)
    elif tag == 9:
        element = _tag_id(value[0]) if value else 10
        out.write(struct.pack(">bi", element, len(value)))
        for item in value:
            _payload(out, item)
    elif tag == 10:
        for key, item in value.items():
            out.write(struct.pack(">b", _tag_id(item)))
            _string(out, key)
            _payload(out, item)
        out.write(b"\x00")
    elif tag == 11:
        out.write(struct.pack(">i", len(value)))
        for item in value:
            out.write(struct.pack(">i", item))
    elif tag == 12:
        out.write(struct.pack(">i", len(value)))
        for item in value:
            out.write(struct.pack(">q", item))


def write_nbt(path, name, root):
    out = io.BytesIO()
    out.write(b"\x0a")
    _string(out, name)
    _payload(out, root)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    # mtime=0: the same content always gives the same bytes (and SHA-256).
    with open(path, "wb") as f:
        f.write(gzip.compress(out.getvalue(), mtime=0))


# ---- Builds: dict (x, y, z) -> block state string, everything else air ----

def arena(size=21):
    blocks = {}
    c = size // 2
    for x in range(size):
        for z in range(size):
            d = math.hypot(x - c, z - c)
            if d <= 1.5:
                state = "minecraft:white_concrete"
            elif d <= 2.5:
                state = "minecraft:black_concrete"
            elif d <= 7:
                state = "minecraft:black_concrete" if z == c else "minecraft:smooth_quartz"
            elif d <= 8:
                state = "minecraft:red_concrete"
            else:
                state = "minecraft:polished_andesite"
            blocks[(x, 0, z)] = state
    edge = size - 1
    for x in range(size):
        for z in range(size):
            if x in (0, edge) or z in (0, edge):
                corner = x in (0, edge) and z in (0, edge)
                for y in range(1, 4):
                    blocks[(x, y, z)] = "minecraft:polished_deepslate" if corner else "minecraft:stone_bricks"
                if corner:
                    blocks[(x, 4, z)] = "minecraft:polished_deepslate"
                    blocks[(x, 5, z)] = "minecraft:lantern[hanging=false,waterlogged=false]"
                else:
                    blocks[(x, 4, z)] = "minecraft:stone_brick_slab[type=bottom,waterlogged=false]"
    # Entrance on the north wall (z = 0): an oak door in the middle.
    blocks[(c, 1, 0)] = "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]"
    blocks[(c, 2, 0)] = "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]"
    blocks[(c, 3, 0)] = "minecraft:chiseled_stone_bricks"
    # Lanterns along the east and west walls.
    for z in (5, 15):
        blocks[(1, 1, z)] = "minecraft:lantern[hanging=false,waterlogged=false]"
        blocks[(edge - 1, 1, z)] = "minecraft:lantern[hanging=false,waterlogged=false]"
    return blocks


def small_room():
    """A 3x2x4 shape with an orientable block, to test parsing and rotation."""
    return {
        (0, 0, 0): "minecraft:stone",
        (2, 0, 3): "minecraft:oak_planks",
        (1, 1, 2): "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
        (0, 1, 3): "phantasmon:unknown_block",
    }


def palette_of(blocks):
    palette = {"minecraft:air": 0}
    for state in blocks.values():
        palette.setdefault(state, len(palette))
    return palette


def varints(values):
    out = bytearray()
    for value in values:
        while value & ~0x7F:
            out.append((value & 0x7F) | 0x80)
            value >>= 7
        out.append(value)
    return ByteArray(bytes(out))


def sponge(path, blocks, size, version):
    w, h, l = size
    palette = palette_of(blocks)
    data = [palette[blocks.get((x, y, z), "minecraft:air")]
            for y in range(h) for z in range(l) for x in range(w)]
    pal = {state: Int(i) for state, i in palette.items()}
    sizes = {"Width": Short(w), "Height": Short(h), "Length": Short(l)}
    if version == 3:
        write_nbt(path, "", {"Schematic": {"Version": Int(3), "DataVersion": Int(DATA_VERSION), **sizes,
                                           "Offset": IntArray([0, 0, 0]),
                                           "Blocks": {"Palette": pal, "Data": varints(data), "BlockEntities": []}}})
    else:
        write_nbt(path, "Schematic", {"Version": Int(2), "DataVersion": Int(DATA_VERSION), **sizes,
                                      "Offset": IntArray([0, 0, 0]), "PaletteMax": Int(len(palette)),
                                      "Palette": pal, "BlockData": varints(data), "BlockEntities": []})


def _state_compound(state):
    name, _, props = state.partition("[")
    compound = {"Name": name}
    if props:
        compound["Properties"] = dict(p.split("=") for p in props.rstrip("]").split(","))
    return compound


def litematic(path, blocks, size):
    w, h, l = size
    palette = palette_of(blocks)
    bits = max(2, (len(palette) - 1).bit_length())
    volume = w * h * l
    longs = [0] * ((volume * bits + 63) // 64)
    for y in range(h):
        for z in range(l):
            for x in range(w):
                value = palette[blocks.get((x, y, z), "minecraft:air")]
                index = (y * l + z) * w + x
                start = index * bits
                word, offset = start >> 6, start & 63
                longs[word] |= (value << offset) & 0xFFFFFFFFFFFFFFFF
                if offset + bits > 64:
                    longs[word + 1] |= value >> (64 - offset)
    signed = [Long(v - (1 << 64) if v >= (1 << 63) else v) for v in longs]
    states = sorted(palette.items(), key=lambda item: item[1])
    write_nbt(path, "", {
        "MinecraftDataVersion": Int(DATA_VERSION), "Version": Int(6),
        "Metadata": {"Name": "test", "Author": "Phantasmon", "RegionCount": Int(1),
                     "EnclosingSize": {"x": Int(w), "y": Int(h), "z": Int(l)}},
        # Negative sizes are legal in Litematica: the region spans from Position backwards.
        "Regions": {"main": {"Position": {"x": Int(w - 1), "y": Int(0), "z": Int(0)},
                             "Size": {"x": Int(-w), "y": Int(h), "z": Int(l)},
                             "BlockStatePalette": [_state_compound(s) for s, _ in states],
                             "BlockStates": LongArray(signed),
                             "TileEntities": [], "Entities": [], "PendingBlockTicks": [], "PendingFluidTicks": []}},
    })


def main():
    full = arena()
    sponge(os.path.join(ROOT, "hub_schematics", "hub_global", "phantasmon_default_hub.schem"), full, (21, 21, 21), 2)
    sponge(os.path.join(ROOT, "src", "test", "resources", "hub_schematics", "hub_global", "test_hub.schem"), full, (21, 21, 21), 2)
    fixtures = os.path.join(ROOT, "src", "test", "resources", "schematics")
    room = small_room()
    sponge(os.path.join(fixtures, "room_v2.schem"), room, (3, 2, 4), 2)
    sponge(os.path.join(fixtures, "room_v3.schem"), room, (3, 2, 4), 3)
    litematic(os.path.join(fixtures, "room.litematic"), room, (3, 2, 4))
    litematic(os.path.join(fixtures, "arena.litematic"), full, (21, 21, 21))
    litematic(os.path.join(fixtures, "cube.litematic"), {(1, 0, 1): "minecraft:stone"}, (3, 3, 3))
    sponge(os.path.join(fixtures, "arena_v3.schem"), full, (21, 21, 21), 3)
    if os.path.isdir(os.path.join(os.path.dirname(ROOT), "Phantasmon-Client", "src", "test")):
        os.makedirs(CLIENT_TEST, exist_ok=True)
        for name in ("room_v2.schem", "room_v3.schem", "room.litematic"):
            shutil.copyfile(os.path.join(fixtures, name), os.path.join(CLIENT_TEST, name))


if __name__ == "__main__":
    main()
