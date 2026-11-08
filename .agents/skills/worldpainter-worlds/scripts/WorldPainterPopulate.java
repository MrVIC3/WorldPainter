// SPDX-License-Identifier: GPL-3.0-or-later
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.Dimension;
import org.pepsoft.worldpainter.colourschemes.HardcodedColourScheme;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.layers.bo2.Bo2ObjectTube;
import org.pepsoft.worldpainter.layers.bo2.Structure;
import org.pepsoft.worldpainter.layers.plants.PlantLayer;
import org.pepsoft.worldpainter.objects.RotatedObject;
import org.pepsoft.worldpainter.objects.WPObject;

import javax.imageio.ImageIO;
import javax.vecmath.Point3i;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;
import static org.pepsoft.worldpainter.objects.WPObject.*;

/** Adds editable structures and scenery to an existing WorldPainter project. */
public class WorldPainterPopulate {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();
    private static final String SITE_LAYER = "Site - ", SCENERY_LAYER = "Scenery - ";
    private static final int PAD_BLEND = 3;
    private static final ColourScheme COLOURS = new HardcodedColourScheme();

    /** Run with a trusted input world, a content plan and a fresh output directory. */
    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(WorldPainterPopulate.class.getClassLoader());
        System.setProperty("java.util.prefs.PreferencesFactory", WorldPainterWorlds.SessionPreferences.class.getName());
        try {
            run(args);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException, UnloadableWorldException {
        require(args.length == 3 || args.length == 4,
                "Usage: WorldPainterPopulate <input.world> <content.json> <new-output-dir> [minecraft-client.jar]");
        long start = System.nanoTime();
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path contentFile = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        require(Files.isRegularFile(source), "World file not found: " + source);
        require(Files.isRegularFile(contentFile) && Files.size(contentFile) <= 256 * 1024,
                "Content plan must be a regular JSON file up to 256 KiB");
        require(!Files.exists(output), "Output already exists: " + output);
        byte[] content = Files.readAllBytes(contentFile);
        Plan plan = JSON.readValue(content, Plan.class);
        require(plan != null, "Content plan must be a JSON object");
        plan.validate();

        WorldPainterWorlds.initialise();
        WorldIO reader = new WorldIO();
        try (var stream = Files.newInputStream(source)) {
            reader.load(stream);
        }
        World2 world = reader.getWorld();
        require(Set.of(DefaultPlugin.JAVA_ANVIL_1_21_11, DefaultPlugin.JAVA_ANVIL_26_1).contains(world.getPlatform()),
                "Population requires a Minecraft Java 1.21.11 or 26.1 project");
        Dimension dimension = world.getDimension(NORMAL_DETAIL);
        require(dimension != null && !dimension.getTileCoords().isEmpty(), "World has no painted Overworld");
        List<CustomLayer> layers = new ArrayList<>(dimension.getCustomLayers());
        require(dimension.getAllLayers(false).stream().noneMatch(WorldPainterPopulate::isPopulationLayer),
                "This project already has population layers. Apply the revised plan to the original terrain project.");

        Path root = contentFile.getParent().toRealPath();
        Set<Path> assets = new LinkedHashSet<>();
        List<Placed> sites = new ArrayList<>();
        Point spawn = world.getSpawnPoint();
        boolean vanilla = list(plan.sites).stream().anyMatch(site -> site.kind == ContentKits.Kind.VANILLA);
        try (VanillaTemplates templates = vanilla ? new VanillaTemplates(world.getPlatform(),
                args.length == 4 ? args[3] : null) : null) {
            for (Site site : list(plan.sites)) {
                try {
                    sites.add(place(dimension, world, site, sites, spawn, root, assets, templates));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(site.name + " at " + site.x + ", " + site.z + ": "
                            + e.getMessage(), e);
                }
            }
        }

        for (Placed site : sites) {
            paintSite(dimension, site, layers);
        }
        int pathColumns = paintPaths(dimension, list(plan.paths), sites, spawn);
        List<Scattered> scenery = scatter(dimension, world.getPlatform(), plan, sites, spawn);
        require(!sites.isEmpty() || scenery.stream().anyMatch(group -> !group.anchors.isEmpty()),
                "No suitable dry scenery locations found");
        for (Scattered group : scenery) {
            if (!group.anchors.isEmpty()) {
                layers.add(group.layer);
            }
            for (Point anchor : group.anchors) {
                dimension.setLayerValueAt(group.layer, anchor.x, anchor.y, 15);
            }
        }
        dimension.setCustomLayers(layers);
        world.setName(plan.name);
        world.setCreateGoodiesChest(false);
        Files.createDirectory(output);
        Files.write(output.resolve("content.json"), content);
        for (Path asset : assets) {
            Path target = output.resolve(asset);
            Files.createDirectories(target.getParent());
            Files.copy(root.resolve(asset), target);
        }
        String filename = plan.name + ".world";
        try (var stream = Files.newOutputStream(output.resolve(filename), StandardOpenOption.CREATE_NEW)) {
            new WorldIO(world).save(stream);
        }

        render(world, dimension, sites, scenery, output.resolve("sites.png"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", "populate");
        result.put("world", filename);
        result.put("map", "sites.png");
        result.put("sites", sites.stream().map(Placed::report).toList());
        result.put("pathColumns", pathColumns);
        result.put("scenery", scenery.stream().map(group -> Map.of("kind", group.kind,
                "requested", group.requested, "placed", group.anchors.size())).toList());
        result.put("elapsedSeconds", Math.round((System.nanoTime() - start) / 1.0e7) / 100.0);
        Path temp = output.resolve(".result.json.tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), result);
        Files.move(temp, output.resolve("result.json"));
        System.out.println(output.resolve("result.json"));
    }

    private static Placed place(Dimension dim, World2 world, Site site, List<Placed> other, Point spawn,
                                Path root, Set<Path> assets, VanillaTemplates templates) throws IOException {
        ContentKits.Kind kind = site.kind;
        WPObject object;
        long seed = dim.getSeed() + site.x * 65537L + site.z * 4099L;
        if (kind == ContentKits.Kind.VANILLA) {
            object = templates.load(site.name, site.template, site.people(), seed);
        } else if (kind == ContentKits.Kind.CUSTOM) {
            Path asset = Path.of(site.file);
            Path file = root.resolve(asset);
            require(Files.isRegularFile(file) && file.toRealPath().startsWith(root)
                    && Files.size(file) <= 8L * 1024 * 1024, "Custom structure must be a trusted .nbt file up to 8 MiB");
            Structure structure;
            try (var stream = Files.newInputStream(file)) {
                structure = Structure.load(site.name, stream);
            }
            Point3i size = structure.getDimensions();
            require(size.x > 0 && size.y > 0 && size.z > 0 && size.x <= 64 && size.y <= 64 && size.z <= 64,
                    "Custom structure dimensions must be in [1, 64]");
            structure.setAttribute(ATTRIBUTE_OFFSET, new Point3i(-size.x / 2, -size.y / 2, 0));
            object = structure;
            assets.add(asset);
        } else {
            object = ContentKits.make(site.name, kind, site.style(), site.span(), site.loot(), site.people(),
                    world.getPlatform(), seed);
        }

        Point3i size = object.getDimensions();
        Point3i offset = object.getOffset();
        Rectangle original = new Rectangle(offset.x, offset.y, size.x, size.y);
        Rectangle footprint = orient(original, site.facing().ordinal());
        footprint.translate(site.x, site.z);
        Rectangle reserve = new Rectangle(footprint);
        reserve.grow(PAD_BLEND, PAD_BLEND);
        require(spawn == null || !reserve.intersects(new Rectangle(spawn.x - 5, spawn.y - 5, 11, 11)),
                "Move the structure away from spawn clearance");
        require(other.stream().noneMatch(entry -> entry.reserved.intersects(reserve)),
                "Footprint and approach overlap another site");
        int baseY = kind.crossing ? crossingHeight(dim, site, original)
                : foundationHeight(dim, site, footprint);
        require(baseY + offset.z > world.getMinHeight() && baseY + offset.z + size.z <= world.getMaxHeight(),
                "Structure would exceed build height or bedrock");
        object.setAttribute(ATTRIBUTE_RANDOM_ROTATION, false);
        object.setAttribute(ATTRIBUTE_HEIGHT_MODE, HEIGHT_MODE_FIXED);
        object.setAttribute(ATTRIBUTE_VERTICAL_OFFSET, baseY);
        object.setAttribute(ATTRIBUTE_NEEDS_FOUNDATION, false);
        // A neighbour region's export halo can queue the same object as a fixup.
        // Let the native collision check reject that second placement and its entities.
        object.setAttribute(ATTRIBUTE_COLLISION_MODE, COLLISION_MODE_SOLID);
        object.setAttribute(ATTRIBUTE_CONNECT_BLOCKS, true);
        object.setAttribute(ATTRIBUTE_SPAWN_IN_WATER, site.seafloor());
        if (site.facing() != Facing.SOUTH) {
            object = new RotatedObject(object, site.facing().ordinal(), world.getPlatform());
        }
        return new Placed(site, object, original, footprint, reserve, baseY);
    }

    private static int foundationHeight(Dimension dim, Site site, Rectangle footprint) {
        int center = terrain(dim, site.x, site.z);
        int low = center, high = center;
        for (int z = footprint.y; z < footprint.y + footprint.height; z++) {
            for (int x = footprint.x; x < footprint.x + footprint.width; x++) {
                int height = terrain(dim, x, z), water = dim.getWaterLevelAt(x, z);
                require(site.seafloor() ? height < water && center < water : height >= water,
                        site.seafloor() ? "Seafloor footprint needs water" : "Dry foundation crosses water");
                low = Math.min(low, height);
                high = Math.max(high, height);
            }
        }
        require(high - low <= site.levelling(), "Footprint rises " + (high - low)
                + " blocks. Move the site or adjust maxRelief.");
        return center + 1;
    }

    private static int crossingHeight(Dimension dim, Site site, Rectangle original) {
        int near = original.y + original.height - 1, far = original.y;
        boolean dock = site.kind == ContentKits.Kind.DOCK;
        int minLanding = Integer.MAX_VALUE, maxLanding = Integer.MIN_VALUE;
        int highestWater = Integer.MIN_VALUE, lowestGround = Integer.MAX_VALUE;
        int flooded = 0, floodedFarEnd = 0;
        for (int z = far; z <= near; z++) {
            for (int x = original.x; x < original.x + original.width; x++) {
                Point pos = at(site, x, z);
                int height = terrain(dim, pos.x, pos.y);
                int water = dim.getWaterLevelAt(pos.x, pos.y);
                lowestGround = Math.min(lowestGround, height);
                boolean wet = height < water;
                if (wet) {
                    flooded++;
                    highestWater = Math.max(highestWater, water);
                    if (z == far) {
                        floodedFarEnd++;
                    }
                }
                if (z >= near - 1 || (!dock && z <= far + 1)) {
                    require(!wet, dock ? "Dock needs a dry shore at its facing end"
                            : "Bridge requires dry land at both ends");
                    minLanding = Math.min(minLanding, height);
                    maxLanding = Math.max(maxLanding, height);
                }
            }
        }
        require(flooded >= original.width && (!dock || floodedFarEnd >= (original.width + 1) / 2),
                dock ? "Dock tip must extend over water" : "Bridge must cross water");
        int deck = Math.max(highestWater + 2, maxLanding + 1);
        require(deck - 1 - minLanding <= Math.min(4, site.levelling()), "Deck is too far above the landing");
        require(deck - lowestGround <= ContentKits.PIER_DEPTH, "Water crossing is too deep for the kit piers");
        for (int z = far; z <= near; z++) {
            for (int x = original.x; x < original.x + original.width; x++) {
                Point pos = at(site, x, z);
                require(terrain(dim, pos.x, pos.y) < deck, "Bank would obstruct the deck");
            }
        }
        return deck;
    }

    private static void paintSite(Dimension dim, Placed site, List<CustomLayer> layers) {
        boolean crossing = site.site.kind.crossing;
        Rectangle bounds = site.footprint;
        Rectangle paint = crossing ? bounds : site.reserved;
        for (int z = paint.y; z < paint.y + paint.height; z++) {
            for (int x = paint.x; x < paint.x + paint.width; x++) {
                int height = dim.getIntHeightAt(x, z);
                if (height == Integer.MIN_VALUE) {
                    continue;
                }
                if (bounds.contains(x, z)) {
                    clearFeatures(dim, x, z);
                    if (!crossing) {
                        dim.setHeightAt(x, z, site.baseY - 1);
                        if (site.site.seafloor()) {
                            dim.setTerrainAt(x, z, Terrain.GRAVEL);
                        } else if (dim.getTerrainAt(x, z) == Terrain.GRASS) {
                            dim.setTerrainAt(x, z, Terrain.BARE_GRASS);
                        }
                    }
                } else if (!site.site.seafloor() && height >= dim.getWaterLevelAt(x, z)) {
                    clearFeatures(dim, x, z);
                    int dx = Math.max(bounds.x - x, x - (bounds.x + bounds.width - 1));
                    int dz = Math.max(bounds.y - z, z - (bounds.y + bounds.height - 1));
                    double weight = 1.0 - Math.max(dx, dz) / (double) (PAD_BLEND + 1);
                    double original = dim.getHeightAt(x, z);
                    dim.setHeightAt(x, z, (float) Math.max(dim.getWaterLevelAt(x, z),
                            original + weight * (site.baseY - 1 - original)));
                }
            }
        }
        if (crossing) {
            for (int side : site.site.kind == ContentKits.Kind.DOCK ? new int[]{1} : new int[]{-1, 1}) {
                int last = side == 1 ? site.original.y + site.original.height - 1 : site.original.y;
                for (int i = 0; i < 2; i++) {
                    for (int x = -1; x <= 1; x++) {
                        Point pos = at(site.site, x, last - side * i);
                        dim.setHeightAt(pos.x, pos.y, site.baseY - 1);
                        dim.setTerrainAt(pos.x, pos.y, Terrain.GRASS_PATH);
                    }
                }
            }
        }
        Bo2Layer layer = objectLayer(SITE_LAYER + site.site.name, site.object, 0xe5b66a);
        dim.setLayerValueAt(layer, site.site.x, site.site.z, 15);
        layers.add(layer);
    }

    private static int paintPaths(Dimension dim, List<Walk> paths, List<Placed> sites, Point spawn) {
        Set<Long> painted = new HashSet<>();
        for (Walk walk : paths) {
            int radius = walk.width / 2;
            for (int i = 1; i < walk.points.size(); i++) {
                Position a = walk.points.get(i - 1), b = walk.points.get(i);
                int steps = Math.max(Math.abs(b.x - a.x), Math.abs(b.z - a.z));
                for (int k = 0; k <= steps; k++) {
                    int x = a.x + (int) Math.round((b.x - a.x) * k / (double) steps);
                    int z = a.z + (int) Math.round((b.z - a.z) * k / (double) steps);
                    for (int dz = -radius; dz <= radius; dz++) {
                        for (int dx = -radius; dx <= radius; dx++) {
                            int tx = x + dx, tz = z + dz;
                            if (dx * dx + dz * dz > radius * radius + 1
                                    || (spawn != null && spawn.distanceSq(tx, tz) <= 4)
                                    || sites.stream().anyMatch(site -> site.footprint.contains(tx, tz))) {
                                continue;
                            }
                            int h = dim.getIntHeightAt(tx, tz);
                            if (h == Integer.MIN_VALUE || h < dim.getWaterLevelAt(tx, tz)
                                    || !painted.add(((long) tx << 32) | (tz & 0xffffffffL))) {
                                continue;
                            }
                            clearFeatures(dim, tx, tz);
                            dim.setTerrainAt(tx, tz, Terrain.GRASS_PATH);
                        }
                    }
                }
            }
        }
        return painted.size();
    }

    private static void clearFeatures(Dimension dim, int x, int z) {
        Tile tile = dim.getTile(Math.floorDiv(x, TILE_SIZE), Math.floorDiv(z, TILE_SIZE));
        int lx = Math.floorMod(x, TILE_SIZE), lz = Math.floorMod(z, TILE_SIZE);
        for (Layer layer : List.copyOf(tile.getLayers())) {
            if (layer instanceof Bo2Layer || layer instanceof TreeLayer || layer instanceof PlantLayer
                    || layer == Caves.INSTANCE || layer == Caverns.INSTANCE || layer == Chasms.INSTANCE
                    || layer == Frost.INSTANCE) {
                if (layer.getDataSize() == Layer.DataSize.BIT) {
                    tile.setBitLayerValue(layer, lx, lz, false);
                } else {
                    tile.setLayerValue(layer, lx, lz, 0);
                }
            }
        }
    }

    private static List<Scattered> scatter(Dimension dim, Platform platform, Plan plan,
                                           List<Placed> sites, Point spawn) {
        List<Scattered> result = new ArrayList<>();
        Set<Point> used = new HashSet<>();
        int groupIndex = 0;
        for (Scatter rule : list(plan.scenery)) {
            groupIndex++;
            WPObject prop = ContentKits.prop(rule.kind, platform);
            int margin = Math.max(prop.getDimensions().x, prop.getDimensions().y) / 2;
            Bo2Layer layer = objectLayer(SCENERY_LAYER + rule.kind + " " + groupIndex, prop, 0x8dbd91);
            Random random = new Random(dim.getSeed() ^ (rule.x * 65537L + rule.z * 4099L + groupIndex));
            List<Point> anchors = new ArrayList<>();
            for (int attempt = 0; attempt < rule.count * 35 && anchors.size() < rule.count; attempt++) {
                int dx = random.nextInt(rule.radius * 2 + 1) - rule.radius;
                int dz = random.nextInt(rule.radius * 2 + 1) - rule.radius;
                if (dx * dx + dz * dz > rule.radius * rule.radius) {
                    continue;
                }
                Point point = new Point(rule.x + dx, rule.z + dz);
                Rectangle reservation = new Rectangle(point.x - margin - 2, point.y - margin - 2,
                        2 * margin + 5, 2 * margin + 5);
                if ((spawn != null && spawn.distanceSq(point) < 64)
                        || sites.stream().anyMatch(site -> site.reserved.intersects(reservation))
                        || used.stream().anyMatch(other -> other.distanceSq(point) < rule.spacing() * rule.spacing())
                        || !propFits(dim, point, margin, prop.getDimensions().z)) {
                    continue;
                }
                anchors.add(point);
                used.add(point);
            }
            if (anchors.size() < rule.count) {
                System.err.printf("Placed %d/%d %s near %d, %d%n",
                        anchors.size(), rule.count, rule.kind, rule.x, rule.z);
            }
            result.add(new Scattered(rule.kind, rule.count, layer, anchors));
        }
        return result;
    }

    private static boolean propFits(Dimension dim, Point point, int margin, int objectHeight) {
        int center = dim.getIntHeightAt(point.x, point.y);
        if (center == Integer.MIN_VALUE || center + objectHeight >= dim.getMaxHeight()) {
            return false;
        }
        for (int z = point.y - margin; z <= point.y + margin; z++) {
            for (int x = point.x - margin; x <= point.x + margin; x++) {
                int h = dim.getIntHeightAt(x, z);
                if (h == Integer.MIN_VALUE || h < dim.getWaterLevelAt(x, z) || Math.abs(h - center) > 1
                        || dim.getTerrainAt(x, z) == Terrain.GRASS_PATH
                        || dim.getLayerValueAt(DeciduousForest.INSTANCE, x, z) > 0
                        || dim.getLayerValueAt(PineForest.INSTANCE, x, z) > 0
                        || dim.getLayerValueAt(Jungle.INSTANCE, x, z) > 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Bo2Layer objectLayer(String name, WPObject object, int colour) {
        Bo2Layer layer = new Bo2Layer(new Bo2ObjectTube(name, List.of(object)), object.getName(), new Color(colour));
        // Strength 15 exceeds every roll at native density 1, so every anchor gets its object.
        layer.setDensity(1);
        return layer;
    }

    private static boolean isPopulationLayer(Layer layer) {
        return layer.getName().startsWith(SITE_LAYER) || layer.getName().startsWith(SCENERY_LAYER);
    }

    private static int terrain(Dimension dim, int x, int z) {
        int h = dim.getIntHeightAt(x, z);
        require(h != Integer.MIN_VALUE, "Footprint extends outside painted tiles");
        return h;
    }

    private static Point rotate(int x, int z, int steps) {
        return switch (steps) {
            case 1 -> new Point(-z, x);
            case 2 -> new Point(-x, -z);
            case 3 -> new Point(z, -x);
            default -> new Point(x, z);
        };
    }

    private static Point at(Site site, int x, int z) {
        Point point = rotate(x, z, site.facing().ordinal());
        point.translate(site.x, site.z);
        return point;
    }

    private static Rectangle orient(Rectangle box, int steps) {
        Point a = rotate(box.x, box.y, steps);
        Point b = rotate(box.x + box.width - 1, box.y + box.height - 1, steps);
        return new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y),
                Math.abs(a.x - b.x) + 1, Math.abs(a.y - b.y) + 1);
    }

    private static void render(World2 world, Dimension dim, List<Placed> sites, List<Scattered> scenery, Path output)
            throws IOException {
        for (int i = 0; i < Terrain.CUSTOM_TERRAIN_COUNT; i++) {
            Terrain.setCustomMaterial(i, world.getMixedMaterial(i));
        }
        int left = dim.getLowestX() * TILE_SIZE, top = dim.getLowestY() * TILE_SIZE;
        int width = (dim.getHighestX() - dim.getLowestX() + 1) * TILE_SIZE;
        int length = (dim.getHighestY() - dim.getLowestY() + 1) * TILE_SIZE;
        int stride = Math.max(1, (Math.max(width, length) + 895) / 896);
        BufferedImage image = new BufferedImage((width + stride - 1) / stride, (length + stride - 1) / stride,
                BufferedImage.TYPE_INT_RGB);
        for (int iz = 0; iz < image.getHeight(); iz++) {
            int z = top + iz * stride;
            for (int ix = 0; ix < image.getWidth(); ix++) {
                int x = left + ix * stride, h = dim.getIntHeightAt(x, z);
                int colour = 0x17232f;
                if (h != Integer.MIN_VALUE) {
                    if (h < dim.getWaterLevelAt(x, z)) {
                        colour = 0x326e92;
                    } else {
                        Terrain terrain = dim.getTerrainAt(x, z);
                        colour = terrain.getColour(dim.getSeed(), x, z, h, h, world.getPlatform(), COLOURS);
                        int east = dim.getIntHeightAt(x + stride, z), south = dim.getIntHeightAt(x, z + stride);
                        if (east != Integer.MIN_VALUE && south != Integer.MIN_VALUE) {
                            colour = shade(colour, Math.max(0.65, Math.min(1.32, 0.94 + 0.04 * (east + south - 2 * h))));
                        }
                    }
                }
                image.setRGB(ix, iz, colour);
            }
        }
        Graphics2D g = image.createGraphics();
        try {
            List<Rectangle> labels = new ArrayList<>();
            for (Placed site : sites) {
                labels.add(dotBounds((site.site.x - left) / stride, (site.site.z - top) / stride));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0xa1cea0));
            for (Scattered group : scenery) {
                for (Point point : group.anchors) {
                    g.fillOval((point.x - left) / stride - 1, (point.y - top) / stride - 1, 3, 3);
                }
            }
            Point spawn = world.getSpawnPoint();
            if (spawn != null) {
                labels.add(dotBounds((spawn.x - left) / stride, (spawn.y - top) / stride));
                marker(g, image, labels, (spawn.x - left) / stride, (spawn.y - top) / stride, "Spawn", Color.WHITE);
            }
            for (Placed site : sites) {
                marker(g, image, labels, (site.site.x - left) / stride, (site.site.z - top) / stride,
                        site.site.name, new Color(0xffd08a));
            }
        } finally {
            g.dispose();
        }
        require(ImageIO.write(image, "PNG", output.toFile()), "PNG writer unavailable");
    }

    private static int shade(int rgb, double light) {
        int r = Math.min(255, (int) (((rgb >>> 16) & 255) * light));
        int g = Math.min(255, (int) (((rgb >>> 8) & 255) * light));
        int b = Math.min(255, (int) ((rgb & 255) * light));
        return (r << 16) | (g << 8) | b;
    }

    private static void marker(Graphics2D g, BufferedImage image, List<Rectangle> used,
                               int x, int y, String label, Color colour) {
        int width = g.getFontMetrics().stringWidth(label) + 7;
        Rectangle box = null;
        int[][] offsets = {{8, -7}, {-width - 8, -7}, {8, 9}, {-width - 8, 9},
                {8, -23}, {-width - 8, -23}, {8, 25}, {-width - 8, 25}, {8, -39}, {-width - 8, -39}};
        for (int[] offset : offsets) {
            Rectangle candidate = new Rectangle(Math.max(1, Math.min(x + offset[0], image.getWidth() - width - 1)),
                    Math.max(1, Math.min(y + offset[1], image.getHeight() - 17)), width, 16);
            if (used.stream().noneMatch(candidate::intersects)) {
                box = candidate;
                break;
            }
        }
        if (box != null) {
            used.add(box);
            g.setColor(new Color(0x192530));
            g.drawLine(x, y, Math.max(box.x, Math.min(x, box.x + box.width)), box.y + 8);
            g.fillRoundRect(box.x, box.y, box.width, box.height, 5, 5);
            g.setColor(colour);
            g.drawString(label, box.x + 3, box.y + 12);
        }
        g.setColor(new Color(0x192530));
        g.fillOval(x - 5, y - 5, 11, 11);
        g.setColor(colour);
        g.fillOval(x - 3, y - 3, 7, 7);
    }

    private static Rectangle dotBounds(int x, int y) {
        return new Rectangle(x - 5, y - 5, 11, 11);
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void require(boolean test, String message) {
        if (!test) {
            throw new IllegalArgumentException(message);
        }
    }

    enum Facing { SOUTH, WEST, NORTH, EAST }
    enum Placement { GROUND, SEAFLOOR }

    record Plan(int version, String name, List<Site> sites, List<Scatter> scenery, List<Walk> paths) {
        void validate() {
            require(version == 1, "Content version must be 1");
            require(name != null && name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}") && !name.endsWith(" ")
                    && !name.matches("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"), "Invalid output world name");
            require(list(sites).size() <= 48 && list(scenery).size() <= 12 && list(paths).size() <= 32
                    && !(list(sites).isEmpty() && list(scenery).isEmpty()), "Use up to 48 sites and 12 scenery rules");
            Set<String> names = new HashSet<>();
            for (Site site : list(sites)) {
                require(site != null && site.kind != null && site.name != null
                        && !site.name.isBlank() && site.name.length() <= 44 && names.add(site.name),
                        "Sites need a kind and unique name of up to 44 characters");
                checkCoordinates(site.x, site.z);
                require(site.levelling() >= 0 && site.levelling() <= 16, "maxRelief must be in [0, 16]");
                require(site.span == null || (site.kind.crossing && site.span >= 9 && site.span <= 65
                        && site.span % 2 == 1), "Crossing spans must be odd and in [9, 65]");
                require(site.kind == ContentKits.Kind.CUSTOM
                        ? site.file != null && site.file.matches("inputs/[A-Za-z0-9][A-Za-z0-9_. -]*\\.nbt")
                        : site.file == null, "Custom sites need an inputs/<filename>.nbt structure");
                require(site.kind == ContentKits.Kind.VANILLA
                        ? site.template != null && site.template.matches("[a-z0-9_]+(/[a-z0-9_-]+)+")
                        : site.template == null, "Vanilla sites need a template ID such as ruined_portal/portal_1");
                require(!site.seafloor() || site.kind == ContentKits.Kind.VANILLA
                        || site.kind == ContentKits.Kind.CUSTOM, "Seafloor placement needs an imported template");
                require(site.kind != ContentKits.Kind.VANILLA || site.loot == null,
                        "Vanilla templates keep their own loot tables");
            }
            int props = 0;
            for (Scatter rule : list(scenery)) {
                require(rule != null && rule.kind != null && rule.count >= 1 && rule.count <= 100
                        && rule.radius >= 8 && rule.radius <= 1024 && rule.spacing() >= 5 && rule.spacing() <= 128,
                        "Scenery needs a kind, 1–100 placements, radius 8–1024, and spacing 5–128");
                checkCoordinates(rule.x, rule.z);
                props += rule.count;
            }
            require(props <= 400, "A plan can request up to 400 scenery objects");
            int pathLength = 0;
            for (Walk walk : list(paths)) {
                require(walk != null && (walk.width == 3 || walk.width == 5)
                        && walk.points != null && walk.points.size() >= 2 && walk.points.size() <= 32,
                        "Paths need width 3 or 5 and 2–32 points");
                Position previous = null;
                for (Position point : walk.points) {
                    require(point != null && !point.equals(previous), "Adjacent path points must be distinct");
                    checkCoordinates(point.x, point.z);
                    if (previous != null) {
                        pathLength += Math.max(Math.abs(point.x - previous.x), Math.abs(point.z - previous.z));
                        require(pathLength <= 32768, "Combined path length exceeds 32768 blocks");
                    }
                    previous = point;
                }
            }
        }

        private static void checkCoordinates(int x, int z) {
            require(Math.abs((long) x) < 30_000_000 && Math.abs((long) z) < 30_000_000,
                    "Coordinates exceed Minecraft world limits");
        }
    }

    record Site(String name, ContentKits.Kind kind, int x, int z, Facing facing, ContentKits.Style style,
                Integer span, Integer maxRelief, ContentKits.Loot loot, Boolean inhabitants, String file,
                String template, Placement placement) {
        public Facing facing() { return facing == null ? Facing.SOUTH : facing; }
        public ContentKits.Style style() { return style == null ? ContentKits.Style.OAK : style; }
        public ContentKits.Loot loot() { return loot == null ? kind.defaultLoot : loot; }
        public Integer span() { return span == null ? 17 : span; }
        int levelling() { return maxRelief == null ? 8 : maxRelief; }
        boolean people() { return inhabitants == null || inhabitants; }
        boolean seafloor() { return placement == Placement.SEAFLOOR; }
    }

    record Scatter(ContentKits.Prop kind, int x, int z, int radius, int count, Integer spacing) {
        public Integer spacing() { return spacing == null ? 9 : spacing; }
    }
    record Position(int x, int z) {}
    record Walk(int width, List<Position> points) {}

    private record Placed(Site site, WPObject object, Rectangle original, Rectangle footprint,
                          Rectangle reserved, int baseY) {
        Map<String, Object> report() {
            Map<String, Object> report = new LinkedHashMap<>(Map.of("name", site.name, "kind", site.kind,
                    "x", site.x, "y", baseY, "z", site.z,
                    "bounds", Map.of("x", footprint.x, "z", footprint.y, "width", footprint.width,
                            "length", footprint.height),
                    "blockEntities", object.getTileEntities() == null ? 0 : object.getTileEntities().size(),
                    "inhabitants", object.getEntities() == null ? 0 : object.getEntities().size()));
            if (site.template != null) {
                report.put("template", site.template);
            }
            return report;
        }
    }

    private record Scattered(ContentKits.Prop kind, int requested, Bo2Layer layer, List<Point> anchors) {}
}
