// SPDX-License-Identifier: GPL-3.0-or-later
import org.jnbt.CompoundTag;
import org.jnbt.IntTag;
import org.jnbt.StringTag;
import org.jnbt.Tag;
import org.pepsoft.minecraft.Chest;
import org.pepsoft.minecraft.Entity;
import org.pepsoft.minecraft.InventoryItem;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.TileEntity;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.objects.GenericObject;
import org.pepsoft.worldpainter.objects.WPObject;

import javax.vecmath.Point3i;
import java.util.*;

import static org.pepsoft.worldpainter.objects.WPObject.*;

final class ContentKits {
    static final int PIER_DEPTH = 24;
    private static final Material AIR = Material.AIR;
    private static final Material STONE = block("stone_bricks"), COBBLE = block("cobblestone");
    private static final Material MOSS = block("mossy_stone_bricks"), CRACKED = block("cracked_stone_bricks");
    private static final Material LANTERN = block("lantern", "hanging", "false");
    private static final Material LADDER = block("ladder", "facing", "east");

    enum Kind {
        CAMP(Loot.SUPPLIES), FARM(Loot.NONE), HAMLET(Loot.SUPPLIES), WATCHTOWER(Loot.SUPPLIES),
        RUIN(Loot.RELIC), DUNGEON(Loot.RELIC), BRIDGE(Loot.NONE, true), DOCK(Loot.NONE, true),
        VANILLA(Loot.NONE), CUSTOM(Loot.NONE);

        final Loot defaultLoot;
        final boolean crossing;

        Kind(Loot loot) {
            this(loot, false);
        }

        Kind(Loot loot, boolean crossing) {
            this.defaultLoot = loot;
            this.crossing = crossing;
        }
    }

    enum Style { OAK, SPRUCE, SANDSTONE }
    enum Loot { NONE, SUPPLIES, RELIC }
    enum Prop { BOULDER, FALLEN_LOG, LANTERN_POST }

    static WPObject make(String name, Kind kind, Style style, int span, Loot loot, boolean inhabitants,
                         Platform platform, long seed) {
        Materials kit = new Materials(style);
        Blocks object = switch (kind) {
            case CAMP -> camp(kit, loot, platform);
            case FARM -> farm(kit, loot, inhabitants, platform);
            case HAMLET -> hamlet(kit, loot, inhabitants, platform);
            case WATCHTOWER -> watchtower(kit, loot, platform);
            case RUIN -> ruin(loot, platform, seed);
            case DUNGEON -> dungeon(loot, inhabitants, platform);
            case BRIDGE, DOCK -> crossing(kit, span, kind == Kind.DOCK, loot, platform);
            case CUSTOM, VANILLA -> throw new IllegalArgumentException("Imported structures need a template");
        };
        return object.object(name);
    }

    static WPObject prop(Prop kind, Platform platform) {
        Blocks object = switch (kind) {
            case BOULDER -> {
                Blocks rock = new Blocks(3, 3, -1, 1, platform);
                rock.fill(0, -1, 1, 2, 0, 1, block("andesite"));
                rock.fill(1, -1, 0, 1, 0, 2, block("andesite"));
                rock.put(1, 1, 1, block("moss_block"));
                rock.put(0, 0, 2, block("andesite"));
                yield rock;
            }
            case FALLEN_LOG -> {
                Blocks log = new Blocks(5, 3, -1, 1, platform);
                log.fill(0, 0, 1, 4, 0, 1, block("oak_log", "axis", "x"));
                log.put(1, 1, 1, block("moss_carpet"));
                log.put(3, 0, 0, block("brown_mushroom"));
                yield log;
            }
            case LANTERN_POST -> {
                Blocks lamp = new Blocks(1, 1, 0, 3, platform);
                lamp.put(0, 0, 0, COBBLE);
                lamp.fill(0, 1, 0, 0, 2, 0, block("spruce_fence"));
                lamp.put(0, 3, 0, LANTERN);
                yield lamp;
            }
        };
        WPObject prop = object.object(kind.name());
        prop.setAttribute(ATTRIBUTE_RANDOM_ROTATION, false);
        prop.setAttribute(ATTRIBUTE_RANDOM_ROTATION_ONLY, kind != Prop.LANTERN_POST);
        prop.setAttribute(ATTRIBUTE_COLLISION_MODE, COLLISION_MODE_NONE);
        prop.setAttribute(ATTRIBUTE_CONNECT_BLOCKS, true);
        return prop;
    }

    private static Blocks camp(Materials kit, Loot loot, Platform platform) {
        Blocks b = new Blocks(13, 11, 0, 4, platform);
        b.fill(4, 0, 5, 8, 0, 8, block("gravel"));
        b.put(6, 1, 6, block("campfire", "facing", "south", "lit", "true"));
        b.fill(4, 1, 5, 4, 1, 7, kit.horizontalLog());
        b.fill(8, 1, 5, 8, 1, 7, kit.horizontalLog());
        tent(b, 1, 1, "white_wool", kit);
        tent(b, 8, 1, "orange_wool", kit);
        b.put(6, 1, 9, block("crafting_table"));
        b.chest(loot, 5, 1, 2);
        lamp(b, 2, 8, kit);
        lamp(b, 10, 8, kit);
        return b;
    }

    private static void tent(Blocks b, int x, int z, String cloth, Materials kit) {
        b.fill(x, 0, z, x + 3, 0, z + 2, kit.planks);
        for (int dx = 0; dx <= 3; dx++) {
            b.fill(x + dx, dx == 1 || dx == 2 ? 3 : 2, z, x + dx,
                    dx == 1 || dx == 2 ? 3 : 2, z + 2, block(cloth));
        }
        b.fill(x, 1, z, x + 3, 1, z, block(cloth));
        b.fill(x, 1, z, x, 1, z + 2, kit.fence);
        b.fill(x + 3, 1, z, x + 3, 1, z + 2, kit.fence);
    }

    private static Blocks farm(Materials kit, Loot loot, boolean inhabitants, Platform platform) {
        Blocks b = new Blocks(21, 17, 0, 8, platform);
        cottage(b, 1, 1, kit, loot);
        crops(b, 10, 2, kit);
        pen(b, 10, 10, kit, inhabitants);
        b.fill(6, 0, 10, 8, 0, 15, block("gravel"));
        b.put(8, 0, 9, block("composter", "level", "3"));
        lamp(b, 5, 14, kit);
        return b;
    }

    private static Blocks hamlet(Materials kit, Loot loot, boolean inhabitants, Platform platform) {
        Blocks b = new Blocks(27, 23, 0, 8, platform);
        cottage(b, 1, 2, kit, loot);
        cottage(b, 18, 2, kit, Loot.NONE);
        b.fill(12, 0, 0, 14, 0, 22, block("gravel"));
        b.fill(3, 0, 9, 23, 0, 11, block("gravel"));
        crops(b, 2, 14, kit);
        pen(b, 18, 15, kit, inhabitants);
        b.put(11, 0, 14, block("composter", "level", "3"));
        b.put(15, 0, 12, block("smithing_table"));
        lamp(b, 11, 8, kit);
        lamp(b, 15, 15, kit);
        if (inhabitants) {
            b.mob("villager", 13, 1, 12, "farmer");
            b.mob("villager", 14, 1, 17, "toolsmith");
        }
        return b;
    }

    private static void cottage(Blocks b, int x, int z, Materials kit, Loot loot) {
        int w = 7, l = 7;
        b.fill(x, 0, z, x + w - 1, 0, z + l - 1, kit.planks);
        for (int y = 1; y <= 3; y++) {
            b.fill(x, y, z, x + w - 1, y, z, kit.wall);
            b.fill(x, y, z + l - 1, x + w - 1, y, z + l - 1, kit.wall);
            b.fill(x, y, z + 1, x, y, z + l - 2, kit.wall);
            b.fill(x + w - 1, y, z + 1, x + w - 1, y, z + l - 2, kit.wall);
        }
        for (int dx : new int[]{0, w - 1}) {
            for (int dz : new int[]{0, l - 1}) {
                b.fill(x + dx, 1, z + dz, x + dx, 3, z + dz, kit.log);
            }
        }
        for (int dx = 0; dx < w; dx++) {
            int y = 4 + Math.min(dx, w - 1 - dx);
            Material roof = dx == 3 ? kit.planks : kit.stairs(dx < 3 ? "east" : "west");
            b.fill(x + dx, y, z - 1, x + dx, y, z + l, roof);
            if (y > 4) {
                b.fill(x + dx, 4, z, x + dx, y - 1, z, kit.wall);
                b.fill(x + dx, 4, z + l - 1, x + dx, y - 1, z + l - 1, kit.wall);
            }
        }
        b.put(x + 3, 1, z + l - 1, kit.door("lower"));
        b.put(x + 3, 2, z + l - 1, kit.door("upper"));
        b.put(x, 2, z + 3, block("glass"));
        b.put(x + w - 1, 2, z + 3, block("glass"));
        b.put(x + 1, 1, z + 1, block("red_bed", "facing", "north", "part", "head"));
        b.put(x + 1, 1, z + 2, block("red_bed", "facing", "north", "part", "foot"));
        b.put(x + 5, 1, z + 2, block("crafting_table"));
        b.put(x + 4, 1, z + 1, LANTERN);
        b.chest(loot, x + 5, 1, z + 4);
    }

    private static void crops(Blocks b, int x, int z, Materials kit) {
        b.fill(x, 0, z, x + 7, 0, z + 5, kit.log);
        b.fill(x + 1, 0, z + 1, x + 6, 0, z + 4, block("farmland", "moisture", 7));
        b.fill(x + 3, 0, z + 1, x + 3, 0, z + 4, block("water", "level", 0));
        for (int dx = 1; dx <= 6; dx++) {
            if (dx == 3) {
                continue;
            }
            for (int dz = 1; dz <= 4; dz++) {
                b.put(x + dx, 1, z + dz, block(dx < 3 ? "carrots" : "wheat", "age", 5 + (dx + dz) % 3));
            }
        }
    }

    private static void pen(Blocks b, int x, int z, Materials kit, boolean inhabitants) {
        b.fill(x, 0, z, x + 6, 0, z + 5, block("grass_block"));
        b.fill(x, 1, z, x + 6, 1, z, kit.fence);
        b.fill(x, 1, z + 5, x + 6, 1, z + 5, kit.fence);
        b.fill(x, 1, z, x, 1, z + 5, kit.fence);
        b.fill(x + 6, 1, z, x + 6, 1, z + 5, kit.fence);
        b.put(x + 3, 1, z + 5, block(kit.wood + "_fence_gate", "facing", "south"));
        b.put(x + 1, 1, z + 1, block("hay_block"));
        if (inhabitants) {
            b.mob("sheep", x + 3, 1, z + 2, null);
            b.mob("sheep", x + 4, 1, z + 3, null);
            b.mob("cow", x + 2, 1, z + 3, null);
        }
    }

    private static Blocks watchtower(Materials kit, Loot loot, Platform platform) {
        Blocks b = new Blocks(9, 9, 0, 15, platform);
        b.fill(2, 0, 2, 6, 0, 6, COBBLE);
        for (int dx : new int[]{2, 6}) {
            for (int dz : new int[]{2, 6}) {
                b.fill(dx, 0, dz, dx, 10, dz, kit.log);
            }
        }
        b.fill(2, 1, 2, 2, 9, 5, kit.wall);
        b.fill(3, 1, 2, 3, 11, 2, kit.log);
        b.fill(4, 1, 2, 4, 11, 2, LADDER);
        // The ladder attaches to the log on its west side. Leave its opening through each platform.
        b.fill(2, 6, 2, 6, 6, 6, kit.planks);
        b.put(3, 6, 2, kit.log);
        b.put(4, 6, 2, LADDER);
        b.fill(1, 11, 1, 7, 11, 7, kit.planks);
        b.put(3, 11, 2, kit.log);
        b.put(4, 11, 2, LADDER);
        b.fill(1, 12, 1, 7, 12, 1, kit.fence);
        b.fill(1, 12, 7, 7, 12, 7, kit.fence);
        b.fill(1, 12, 2, 1, 12, 6, kit.fence);
        b.fill(7, 12, 2, 7, 12, 6, kit.fence);
        b.chest(loot, 5, 12, 6);
        b.put(1, 13, 1, LANTERN);
        b.put(7, 13, 7, LANTERN);
        return b;
    }

    private static Blocks ruin(Loot loot, Platform platform, long seed) {
        Blocks b = new Blocks(13, 13, 0, 7, platform);
        Random random = new Random(seed);
        Material[] stones = {COBBLE, STONE, MOSS, CRACKED};
        for (int z = 1; z <= 11; z++) {
            for (int x = 1; x <= 11; x++) {
                if (random.nextInt(4) != 0) {
                    b.put(x, 0, z, stones[random.nextInt(stones.length)]);
                }
                if ((x == 1 || x == 11 || z == 1) && random.nextInt(5) != 0) {
                    int height = random.nextInt(1, 5);
                    for (int y = 1; y <= height; y++) {
                        b.put(x, y, z, stones[random.nextInt(stones.length)]);
                    }
                }
            }
        }
        b.fill(3, 1, 10, 3, 4, 10, MOSS);
        b.fill(9, 1, 10, 9, 4, 10, STONE);
        b.fill(3, 5, 10, 9, 5, 10, CRACKED);
        b.fill(4, 4, 10, 4, 4, 10, STONE);
        b.put(8, 4, 10, MOSS);
        b.chest(loot, 8, 1, 3);
        b.put(7, 1, 3, LANTERN);
        return b;
    }

    private static Blocks dungeon(Loot loot, boolean inhabitants, Platform platform) {
        Blocks b = new Blocks(11, 13, -7, 4, platform);
        b.fill(0, -7, 0, 10, -1, 12, STONE);
        b.fill(1, -6, 1, 9, -2, 11, AIR);
        b.fill(4, -6, 1, 6, 1, 3, AIR);
        b.fill(4, -6, 2, 4, 2, 2, MOSS);
        b.fill(5, -6, 2, 5, 2, 2, LADDER);
        b.fill(3, 0, 1, 3, 2, 3, CRACKED);
        b.fill(7, 0, 1, 7, 2, 3, MOSS);
        b.put(4, 3, 2, LANTERN);
        b.put(2, -6, 4, LANTERN);
        b.put(2, -6, 10, LANTERN);
        b.chest(loot, 8, -6, 10);
        if (inhabitants) {
            b.mob("skeleton", 7, -6, 9, null);
        }
        return b;
    }

    private static Blocks crossing(Materials kit, int span, boolean dock, Loot loot, Platform platform) {
        Blocks b = new Blocks(5, span, -PIER_DEPTH, 4, platform);
        b.fill(0, 0, 0, 4, 0, span - 1, kit.planks);
        b.fill(0, 1, 1, 0, 1, span - 2, kit.fence);
        b.fill(4, 1, 1, 4, 1, span - 2, kit.fence);
        for (int z = 2; z < span - 1; z += 6) {
            b.fill(0, -PIER_DEPTH, z, 0, 1, z, kit.log);
            b.fill(4, -PIER_DEPTH, z, 4, 1, z, kit.log);
        }
        b.put(0, 2, 2, LANTERN);
        b.put(4, 2, span - 3, LANTERN);
        if (dock) {
            b.fill(0, 1, 0, 4, 1, 0, kit.fence);
        }
        b.chest(loot, 1, 1, 2);
        return b;
    }

    private static void lamp(Blocks b, int x, int z, Materials kit) {
        b.put(x, 0, z, COBBLE);
        b.fill(x, 1, z, x, 2, z, kit.fence);
        b.put(x, 3, z, LANTERN);
    }

    private static Material block(String name, Object... properties) {
        return Material.get("minecraft:" + name, properties);
    }

    static Entity mob(String kind, double x, double y, double z, String profession) {
        Entity entity = new Entity("minecraft:" + kind);
        Map<String, Tag> tags = new HashMap<>(entity.toNBT().getValue());
        tags.put("PersistenceRequired", new org.jnbt.ByteTag("PersistenceRequired", (byte) 1));
        if (profession != null) {
            tags.put("VillagerData", new CompoundTag("VillagerData", Map.of(
                    "type", new StringTag("type", "minecraft:plains"),
                    "profession", new StringTag("profession", "minecraft:" + profession),
                    "level", new IntTag("level", 1))));
        }
        return Entity.fromNBT(new CompoundTag("", tags), new double[]{x, y, z});
    }

    private static final class Materials {
        final String wood;
        final Material log, planks, wall, fence;

        Materials(Style style) {
            wood = style == Style.OAK ? "oak" : "spruce";
            log = block(wood + "_log", "axis", "y");
            planks = block(wood + "_planks");
            wall = style == Style.SANDSTONE ? block("cut_sandstone")
                    : block("stripped_" + wood + "_wood", "axis", "y");
            fence = block(wood + "_fence");
        }

        Material horizontalLog() {
            return block(wood + "_log", "axis", "z");
        }

        Material stairs(String direction) {
            return block(wood + "_stairs", "facing", direction, "half", "bottom");
        }

        Material door(String half) {
            return block(wood + "_door", "facing", "south", "half", half, "hinge", "left", "open", "false");
        }
    }

    private static final class Blocks {
        final int width, length, minY, height;
        final Material[] blocks;
        final Platform platform;
        final List<TileEntity> containers = new ArrayList<>();
        final List<Entity> mobs = new ArrayList<>();

        Blocks(int width, int length, int minY, int maxY, Platform platform) {
            this.width = width;
            this.length = length;
            this.minY = minY;
            this.height = maxY - minY + 1;
            this.platform = platform;
            blocks = new Material[width * length * height];
        }

        void put(int x, int y, int z, Material block) {
            if (x < 0 || x >= width || z < 0 || z >= length || y < minY || y >= minY + height) {
                throw new IllegalStateException("Kit block outside bounds: " + x + ", " + y + ", " + z);
            }
            blocks[x + z * width + (y - minY) * width * length] = block;
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, Material block) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        put(x, y, z, block);
                    }
                }
            }
        }

        void chest(Loot loot, int x, int y, int z) {
            if (loot == Loot.NONE) {
                return;
            }
            put(x, y, z, block("chest", "facing", "south", "type", "single"));
            Chest chest = new Chest(platform);
            chest.setX(x);
            chest.setY(y - minY);
            chest.setZ(z);
            chest.setItems(loot == Loot.RELIC
                    ? List.of(item("emerald", 5, 3), item("gold_ingot", 3, 12), item("iron_pickaxe", 1, 16))
                    : List.of(item("bread", 6, 4), item("torch", 12, 12), item("cooked_salmon", 4, 22)));
            containers.add(chest);
        }

        private static InventoryItem item(String id, int count, int slot) {
            return new InventoryItem("minecraft:" + id, count, slot);
        }

        void mob(String kind, int x, int y, int z, String profession) {
            mobs.add(ContentKits.mob(kind, x + 0.5, y - minY, z + 0.5, profession));
        }

        WPObject object(String name) {
            GenericObject object = new GenericObject(name, width, length, height, blocks, mobs, containers, null);
            object.setAttribute(ATTRIBUTE_OFFSET, new Point3i(-width / 2, -length / 2, minY));
            return object;
        }
    }

    private ContentKits() {}
}
