#!/usr/bin/env python3
"""Generate the bundled plot presets (src/main/resources/royalgarden/presets/*.preset).

A preset is a whole plot, 96x96, stored the way PlotTemplate.save() writes it: gzip, then
magic "RGPT", version 1, size, height, base offset (-3), the palette as UTF strings, and one
big-endian short per block indexed (x * height + y) * size + z.

Layers: y0 bedrock floor, y1-y2 dirt, y3 the surface (GardenLayout.groundY()), y4 what grows.

Usage:  python tools/gen_presets.py            (writes into src/main/resources/royalgarden/presets)
"""
import gzip
import io
import os
import struct
import sys

SIZE = 96
HEIGHT = 5
BASE_OFFSET = -3
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "royalgarden", "presets")

AIR = "minecraft:air"
GRASS = "minecraft:grass_block[snowy=false]"
DIRT = "minecraft:dirt"
BEDROCK = "minecraft:bedrock"
PATH = "minecraft:dirt_path"
WATER = "minecraft:water[level=0]"
TRAPDOOR = "minecraft:oak_trapdoor[facing=north,half=bottom,open=false,powered=false,waterlogged=false]"
FARMLAND = "minecraft:farmland[moisture=7]"
SAND = "minecraft:sand"
SOUL_SAND = "minecraft:soul_sand"
MYCELIUM = "minecraft:mycelium[snowy=false]"

# The two rows across the middle that stay bare path, so a player can cross the stripes.
CROSS = (47, 48)


class Plot:
    def __init__(self):
        self.cells = {}

    def set(self, x, y, z, state):
        self.cells[(x, y, z)] = state

    def get(self, x, y, z):
        return self.cells.get((x, y, z), AIR)

    def base(self):
        """Grass over dirt over bedrock with a path around the rim, as GardenBuilder lays a plain plot."""
        for x in range(SIZE):
            for z in range(SIZE):
                rim = x in (0, SIZE - 1) or z in (0, SIZE - 1)
                self.set(x, 0, z, BEDROCK)
                self.set(x, 1, z, DIRT)
                self.set(x, 2, z, DIRT)
                self.set(x, 3, z, PATH if rim else GRASS)

    def save(self, path):
        palette = []
        index = {}
        blocks = []
        for x in range(SIZE):
            for y in range(HEIGHT):
                for z in range(SIZE):
                    state = self.get(x, y, z)
                    if state not in index:
                        index[state] = len(palette)
                        palette.append(state)
                    blocks.append(index[state])
        buf = io.BytesIO()
        buf.write(struct.pack(">iiiii", 0x52475054, 1, SIZE, HEIGHT, BASE_OFFSET))
        buf.write(struct.pack(">i", len(palette)))
        for state in palette:
            raw = state.encode("utf-8")
            buf.write(struct.pack(">H", len(raw)) + raw)
        buf.write(struct.pack(">%dh" % len(blocks), *blocks))
        # mtime=0 so regenerating gives byte-identical files and clean diffs.
        with open(path, "wb") as out:
            out.write(gzip.compress(buf.getvalue(), mtime=0))


def interior(x, z):
    return 1 <= x <= SIZE - 2 and 1 <= z <= SIZE - 2


def cross(z):
    return z in CROSS


def lane_plot(crop, spacing_lanes):
    """Farmland stripes watered by a covered channel: F F F F W F F F F, lane every 9 blocks.

    Water hydrates farmland within 4 blocks on its own level, so one source per column feeds both
    sides. A closed trapdoor on top makes the channel something you can walk along, and the reach
    of a scythe covers the four rows either side.
    """
    plot = Plot()
    plot.base()
    lanes = spacing_lanes
    for x in range(1, SIZE - 1):
        for z in range(1, SIZE - 1):
            if cross(z):
                plot.set(x, 3, z, PATH)
                continue
            if x in lanes:
                plot.set(x, 3, z, WATER)
                plot.set(x, 4, z, TRAPDOOR)
            elif any(abs(x - lane) <= 4 for lane in lanes):
                plot.set(x, 3, z, FARMLAND)
                plot.set(x, 4, z, crop)
    return plot


def wheat_like(crop):
    return lane_plot(crop, [7 + 9 * k for k in range(10)])        # lanes at 7, 16 ... 88; farmland 3..92


def sugar_cane():
    """Bands of W C C W C C W C C W: every cane touches water, two thirds of the band is cane."""
    plot = Plot()
    plot.base()
    pattern = "WCCWCCWCCW"
    x = 1
    bands = []
    for _ in range(7):
        bands.append(x)
        x += len(pattern) + 2                                      # a two-wide path between bands
    bands.append(x)                                                # the last band meets the rim path
    for start in bands:
        for i, kind in enumerate(pattern):
            bx = start + i
            if bx > SIZE - 2:
                continue
            for z in range(1, SIZE - 1):
                if cross(z):
                    plot.set(bx, 3, z, PATH)
                elif kind == "W":
                    plot.set(bx, 3, z, WATER)
                else:
                    plot.set(bx, 3, z, SAND)
                    plot.set(bx, 4, z, "minecraft:sugar_cane[age=0]")
    for start in bands[:-1]:                                       # the paths between bands
        for bx in (start + len(pattern), start + len(pattern) + 1):
            for z in range(1, SIZE - 1):
                plot.set(bx, 3, z, PATH)
    return plot


def rows_of(ground, crop_at):
    """Eight-wide rows with a one-wide path after each, for crops that need no water."""
    plot = Plot()
    plot.base()
    for x in range(1, SIZE - 1):
        if (x - 1) % 9 == 8:
            for z in range(1, SIZE - 1):
                plot.set(x, 3, z, PATH)
            continue
        for z in range(1, SIZE - 1):
            if cross(z):
                plot.set(x, 3, z, PATH)
            else:
                plot.set(x, 3, z, ground)
                plot.set(x, 4, z, crop_at(x, z))
    return plot


def nether_wart():
    return rows_of(SOUL_SAND, lambda x, z: "minecraft:nether_wart[age=0]")


def mushroom():
    return rows_of(MYCELIUM, lambda x, z: "minecraft:red_mushroom" if (x + z) % 2 else "minecraft:brown_mushroom")


def fence(east, west):
    return "minecraft:oak_fence[east=%s,north=false,south=false,waterlogged=false,west=%s]" % (
        "true" if east else "false", "true" if west else "false")


def snake_stems(stem):
    """The Hypixel Garden melon/pumpkin farm: a snake of two-wide walkways.

    Across the plot, repeating every five rows:   P P S W S
        P  walkway, two wide, grass underfoot
        S  a row of stems on farmland; every stem has a stem either side of it and the wall behind it,
           so the only free side is the walkway, and that is where its fruit has to grow
        W  the wall: a fence over a water source, which hydrates the stem rows either side of it

    A stem only grows fruit onto an adjacent air block over dirt, grass or farmland, so the walkway is
    the whole harvest: walk along it chopping as you go. Each divider (S W S) stops short of the plot
    edge at alternating ends, with the gap laid as path so no fruit grows in it, which joins one walkway
    to the next and makes the whole plot one long snake.
    """
    plot = Plot()
    plot.base()
    walk_rows = [1 + 5 * k for k in range(19)]                     # first row of each walkway, 19 walkways
    for first in walk_rows:
        for z in (first, first + 1):
            for x in range(1, SIZE - 1):
                plot.set(x, 3, z, GRASS)
    for j in range(len(walk_rows) - 1):
        z0 = walk_rows[j] + 2                                      # S, then W, then S
        if j % 2 == 0:
            lo, hi = 1, 90                                         # open at the east end
            gap = range(91, SIZE - 1)
        else:
            lo, hi = 5, SIZE - 2                                   # open at the west end
            gap = range(1, 5)
        for x in range(lo, hi + 1):
            plot.set(x, 3, z0, FARMLAND)
            plot.set(x, 4, z0, stem)
            plot.set(x, 3, z0 + 1, WATER)
            plot.set(x, 4, z0 + 1, fence(x < hi, x > lo))
            plot.set(x, 3, z0 + 2, FARMLAND)
            plot.set(x, 4, z0 + 2, stem)
        for x in gap:
            for z in (z0, z0 + 1, z0 + 2):
                plot.set(x, 3, z, PATH)
    return plot


def check_snake(plot, fruit_soil=("minecraft:grass_block", "minecraft:dirt", "minecraft:farmland")):
    """Prove the layout does what it claims: every stem has exactly one place to put fruit, and the
    walkable tiles form one connected snake with no branches."""
    def soil(x, z):
        return plot.get(x, 3, z).split("[")[0] in fruit_soil and plot.get(x, 4, z) == AIR

    stems = [(x, z) for x in range(SIZE) for z in range(SIZE) if plot.get(x, 4, z).endswith("_stem[age=0]")]
    assert stems, "no stems"
    for x, z in stems:
        free = [(x + dx, z + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) if soil(x + dx, z + dz)]
        assert len(free) == 1, "stem at %s has %d places for fruit" % ((x, z), len(free))
        fx, fz = free[0]
        assert plot.get(fx, 3, fz).startswith("minecraft:grass_block"), "fruit would land at %s" % ((fx, fz),)
    walk = {(x, z) for x in range(1, SIZE - 1) for z in range(1, SIZE - 1)
            if plot.get(x, 4, z) == AIR and plot.get(x, 3, z).split("[")[0] in ("minecraft:grass_block", PATH.split("[")[0])}
    start = (1, 1)
    seen, todo = {start}, [start]
    while todo:
        x, z = todo.pop()
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (x + dx, z + dz)
            if n in walk and n not in seen:
                seen.add(n)
                todo.append(n)
    grass_walk = {p for p in walk if plot.get(p[0], 3, p[1]).startswith("minecraft:grass_block")}
    unreachable = {p for p in grass_walk if p not in seen and p[1] <= 92}
    assert not unreachable, "%d walkway tiles cannot be reached from the entrance" % len(unreachable)
    return len(stems)


def stems(stem):
    plot = snake_stems(stem)
    count = check_snake(plot)
    print("  %s: %d stems, each with exactly one place for fruit, one connected snake" % (stem.split("[")[0], count))
    return plot


PRESETS = {
    "wheat": lambda: wheat_like("minecraft:wheat[age=0]"),
    "carrot": lambda: wheat_like("minecraft:carrots[age=0]"),
    "potato": lambda: wheat_like("minecraft:potatoes[age=0]"),
    "sugar-cane": sugar_cane,
    "nether-wart": nether_wart,
    "mushroom": mushroom,
    "melon": lambda: stems("minecraft:melon_stem[age=0]"),
    "pumpkin": lambda: stems("minecraft:pumpkin_stem[age=0]"),
}


def main():
    out = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else OUT)
    os.makedirs(out, exist_ok=True)
    for name, build in PRESETS.items():
        path = os.path.join(out, name + ".preset")
        build().save(path)
        print("wrote", path, os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()
