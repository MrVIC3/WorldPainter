// SPDX-License-Identifier: GPL-3.0-or-later
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jnbt.*;
import org.pepsoft.minecraft.Entity;
import org.pepsoft.minecraft.Material;
import org.pepsoft.minecraft.TileEntity;
import org.pepsoft.worldpainter.DefaultPlugin;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.objects.GenericObject;
import org.pepsoft.worldpainter.objects.WPObject;

import javax.vecmath.Point3i;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.pepsoft.worldpainter.objects.WPObject.ATTRIBUTE_OFFSET;

/** Reads vanilla structure templates from a matching, locally installed Minecraft client JAR. */
final class VanillaTemplates implements AutoCloseable {
    final String version;
    private final ZipFile jar;

    VanillaTemplates(Platform platform, String suppliedJar) throws IOException {
        version = platform == DefaultPlugin.JAVA_ANVIL_26_1 ? "26.1" : "1.21.11";
        Path path = suppliedJar == null ? findClient(version) : Path.of(suppliedJar);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Minecraft " + version + " client JAR not found. Pass -MinecraftJar.");
        }
        jar = new ZipFile(path.toFile());
        try {
            ZipEntry entry = jar.getEntry("version.json");
            if (entry == null) {
                throw new IllegalArgumentException("Expected the Minecraft client JAR with version.json");
            }
            try (var stream = jar.getInputStream(entry)) {
                var metadata = new JsonMapper().readTree(stream);
                if (metadata == null || !version.equals(metadata.path("id").asText())) {
                    throw new IllegalArgumentException("The vanilla client JAR must match Minecraft " + version);
                }
            }
        } catch (IOException | RuntimeException e) {
            jar.close();
            throw e;
        }
    }

    private static Path findClient(String version) {
        Path home = Path.of(System.getProperty("user.home"));
        String appData = System.getenv("APPDATA");
        List<Path> roots = new ArrayList<>();
        if (appData != null) {
            roots.add(Path.of(appData, ".minecraft"));
        }
        roots.add(home.resolve(".minecraft"));
        roots.add(home.resolve("Library/Application Support/minecraft"));
        for (Path root : roots) {
            Path file = root.resolve("versions").resolve(version).resolve(version + ".jar");
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return roots.get(0).resolve("versions").resolve(version).resolve(version + ".jar");
    }

    @SuppressWarnings("unchecked")
    WPObject load(String name, String template, boolean inhabitants, long seed) throws IOException {
        ZipEntry entry = jar.getEntry("data/minecraft/structure/" + template + ".nbt");
        if (entry == null) {
            entry = jar.getEntry("data/minecraft/structures/" + template + ".nbt");
        }
        if (entry == null) {
            throw new IllegalArgumentException("Vanilla template not found in Minecraft " + version + ": " + template);
        }
        CompoundTag root;
        try (var stream = new NBTInputStream(new GZIPInputStream(jar.getInputStream(entry)))) {
            root = (CompoundTag) stream.readTag();
        }
        List<IntTag> size = ((ListTag<IntTag>) root.getTag("size")).getValue();
        int width = size.get(0).getValue(), height = size.get(1).getValue(), length = size.get(2).getValue();
        if (width < 1 || height < 1 || length < 1 || width > 64 || height > 64 || length > 64) {
            throw new IllegalArgumentException("Vanilla template exceeds 64 blocks per axis: " + template);
        }
        ListTag<CompoundTag> palette = (ListTag<CompoundTag>) root.getTag("palette");
        if (palette == null) {
            ListTag<ListTag<CompoundTag>> variants = (ListTag<ListTag<CompoundTag>>) root.getTag("palettes");
            if (variants == null || variants.getValue().isEmpty()) {
                throw new IllegalArgumentException("Template has no palette: " + template);
            }
            palette = variants.getValue().get(new Random(seed).nextInt(variants.getValue().size()));
        }
        Material[] materials = palette.getValue().stream().map(VanillaTemplates::material).toArray(Material[]::new);
        Material[] blocks = new Material[width * length * height];
        List<Entity> mobs = new ArrayList<>();
        Map<Point3i, TileEntity> tileEntities = new LinkedHashMap<>();
        Map<Point3i, String> lootMarkers = new LinkedHashMap<>();

        for (CompoundTag block : ((ListTag<CompoundTag>) root.getTag("blocks")).getValue()) {
            List<IntTag> position = ((ListTag<IntTag>) block.getTag("pos")).getValue();
            int x = position.get(0).getValue(), y = position.get(1).getValue(), z = position.get(2).getValue();
            int index = x + z * width + y * width * length;
            Material material = materials[((IntTag) block.getTag("state")).getValue()];
            CompoundTag tags = (CompoundTag) block.getTag("nbt");
            if (material.isNamed("minecraft:jigsaw")) {
                if (tags != null) {
                    blocks[index] = finalState(value(tags, "final_state"));
                    String pool = value(tags, "pool");
                    if (inhabitants && pool.endsWith("/villagers")) {
                        mobs.add(ContentKits.mob("villager", x + 0.5, y + 1, z + 0.5, null));
                    }
                    if (inhabitants && pool.endsWith("/iron_golem")) {
                        mobs.add(ContentKits.mob("iron_golem", x + 0.5, y + 1, z + 0.5, null));
                    }
                }
            } else if (material.isNamed("minecraft:structure_block")) {
                String lootTable = tags == null ? null : lootMarker(value(tags, "metadata"));
                if (lootTable != null && template.startsWith("shipwreck/")) {
                    lootMarkers.put(new Point3i(x, y - 1, z), lootTable);
                }
            } else if (!material.empty && !material.isNamed("minecraft:structure_void")) {
                blocks[index] = material;
                if (tags != null) {
                    tileEntities.put(new Point3i(x, y, z), tileEntity(tags, x, y, z));
                }
            }
        }
        lootMarkers.forEach((p, table) -> {
            TileEntity existing = tileEntities.get(p);
            if (existing != null && existing.getId().equals("minecraft:chest")) {
                Map<String, Tag> tags = new LinkedHashMap<>(existing.toNBT().getValue());
                tags.remove("Items");
                tags.put("LootTable", new StringTag("LootTable", table));
                tileEntities.put(p, tileEntity(new CompoundTag("", tags), p.x, p.y, p.z));
            }
        });
        if (inhabitants && root.getTag("entities") instanceof ListTag<?> entities) {
            for (Tag rawEntity : entities.getValue()) {
                CompoundTag entity = (CompoundTag) rawEntity;
                List<DoubleTag> pos = ((ListTag<DoubleTag>) entity.getTag("pos")).getValue();
                double[] location = pos.stream().mapToDouble(DoubleTag::getValue).toArray();
                mobs.add(Entity.fromNBT((CompoundTag) entity.getTag("nbt"), location));
            }
        }
        if (inhabitants && template.startsWith("pillager_outpost/watchtower") && width >= 13 && length >= 13) {
            // The vanilla template has no pillager entities. Place a small guard group at the entrance.
            mobs.add(ContentKits.mob("pillager", width / 2.0 - 2, 1, length - 1.5, null));
            mobs.add(ContentKits.mob("pillager", width / 2.0 + 2, 1, length - 1.5, null));
        }
        GenericObject object = new GenericObject(name, width, length, height, blocks, mobs,
                new ArrayList<>(tileEntities.values()), null);
        object.setAttribute(ATTRIBUTE_OFFSET, new Point3i(-width / 2, -length / 2, 0));
        return object;
    }

    private static String lootMarker(String marker) {
        return switch (marker) {
            case "map_chest" -> "minecraft:chests/shipwreck_map";
            case "treasure_chest" -> "minecraft:chests/shipwreck_treasure";
            case "supply_chest" -> "minecraft:chests/shipwreck_supply";
            default -> null;
        };
    }

    private static TileEntity tileEntity(CompoundTag tags, int x, int y, int z) {
        TileEntity entity = TileEntity.fromNBT(tags);
        entity.setX(x);
        entity.setY(y);
        entity.setZ(z);
        return entity;
    }

    private static Material material(CompoundTag entry) {
        CompoundTag properties = (CompoundTag) entry.getTag("Properties");
        Map<String, String> states = new HashMap<>();
        if (properties != null) {
            properties.getValue().forEach((key, tag) -> states.put(key, ((StringTag) tag).getValue()));
        }
        return Material.get(value(entry, "Name"), states);
    }

    private static String value(CompoundTag tags, String key) {
        return tags.getTag(key) instanceof StringTag tag ? tag.getValue() : "";
    }

    private static Material finalState(String state) {
        if (state.isEmpty() || state.equals("minecraft:air") || state.equals("minecraft:structure_void")) {
            return null;
        }
        int bracket = state.indexOf('[');
        if (bracket < 0) {
            return Material.get(state);
        }
        Map<String, String> properties = new HashMap<>();
        for (String assignment : state.substring(bracket + 1, state.length() - 1).split(",")) {
            String[] parts = assignment.split("=", 2);
            properties.put(parts[0], parts[1]);
        }
        return Material.get(state.substring(0, bracket), properties);
    }

    @Override
    public void close() throws IOException {
        jar.close();
    }
}
