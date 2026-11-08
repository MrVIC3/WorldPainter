// SPDX-License-Identifier: GPL-3.0-or-later
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.pepsoft.minecraft.DataType;
import org.pepsoft.util.FileUtils;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.biomeschemes.Minecraft1_21Biomes;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.platforms.JavaPlatformProvider;
import org.pepsoft.worldpainter.plugins.PlatformManager;
import org.pepsoft.worldpainter.plugins.WPPluginManager;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

import static org.pepsoft.worldpainter.Constants.TILE_SIZE;
import static org.pepsoft.worldpainter.Constants.DIM_NORMAL;
import static org.pepsoft.worldpainter.DefaultPlugin.JAVA_ANVIL_1_21_11;
import static org.pepsoft.worldpainter.DefaultPlugin.JAVA_ANVIL_26_1;
import static org.pepsoft.worldpainter.Dimension.Anchor.NORMAL_DETAIL;
import static org.pepsoft.worldpainter.biomeschemes.Minecraft1_21Biomes.*;
import static org.pepsoft.worldpainter.exporting.WorldExportSettings.EXPORT_EVERYTHING;

/** Headless recipe runner for the WorldPainter Java Edition exporter. */
public class WorldPainterWorlds {
    private static final int MIN_Y = -64, MAX_Y = 320;
    private static final int MAX_SIDE = 16_384, MAX_COLUMNS = 8192 * 8192;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    /** Run preview, build, generate or export into a new directory. */
    public static void main(String[] args) throws Exception {
        // The core loads layer icons through GUIUtils, which accesses preferences even in headless mode.
        Thread.currentThread().setContextClassLoader(WorldPainterWorlds.class.getClassLoader());
        System.setProperty("java.util.prefs.PreferencesFactory", SessionPreferences.class.getName());
        try {
            run(args);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        require(args.length == 3 && Set.of("preview", "build", "generate", "export").contains(args[0]),
                "Usage: WorldPainterWorlds.java <preview|build|generate|export> <recipe.json|input.world> <new-output-dir>");
        String command = args[0];
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        require(Files.isRegularFile(input), "Input file does not exist: " + input);
        require(!Files.exists(output), "Output already exists: " + output);
        long start = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", command);
        result.put("worldPainter", Version.VERSION);

        if (command.equals("export")) {
            initialise();
            WorldIO reader = new WorldIO();
            try (var stream = Files.newInputStream(input)) {
                reader.load(stream);
            }
            World2 world = reader.getWorld();
            Files.createDirectory(output);
            result.put("source", input.toString());
            result.put("minecraft", export(world, output));
        } else {
            require(Files.size(input) <= 256 * 1024, "Recipe exceeds 256 KiB");
            byte[] recipeBytes = Files.readAllBytes(input);
            Recipe recipe = JSON.readValue(recipeBytes, Recipe.class);
            require(recipe != null, "Recipe must be a JSON object");
            recipe.validate();
            Model model = new Model(recipe);
            Files.createDirectory(output);
            Files.write(output.resolve("recipe.json"), recipeBytes);
            result.put("name", recipe.name);
            result.put("target", recipe.target());
            result.put("width", recipe.width);
            result.put("length", recipe.length);
            result.put("seed", recipe.seed.toString());
            result.put("seaLevel", recipe.sea());
            result.put("spawn", coordinates(model.spawn, model.land(model.spawn.x, model.spawn.z).height + 1));
            var destinations = new LinkedHashMap<String, Map<String, Integer>>();
            for (Waypoint waypoint : list(recipe.waypoints)) {
                Land land = model.land(waypoint.x, waypoint.z);
                destinations.put(waypoint.name, coordinates(new Position(waypoint.x, waypoint.z),
                        Math.max(land.height, land.water) + 1));
            }
            result.put("waypoints", destinations);
            result.put("preview", "overview.png");
            render(model, output.resolve("overview.png"), result);
            if (!command.equals("preview")) {
                initialise();
                World2 world = build(model, result);
                String worldFile = recipe.name + ".world";
                try (var stream = Files.newOutputStream(output.resolve(worldFile), StandardOpenOption.CREATE_NEW)) {
                    new WorldIO(world).save(stream);
                }
                result.put("world", worldFile);
                System.err.printf(Locale.ROOT, "Saved %s in %.1f s%n", worldFile, elapsed(start));
                if (command.equals("generate")) {
                    result.put("minecraft", export(world, output));
                }
            }
        }
        result.put("elapsedSeconds", Math.round(elapsed(start) * 100.0) / 100.0);
        Path summary = output.resolve("result.json");
        Path pending = output.resolve(".result.json.tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(pending.toFile(), result);
        Files.move(pending, summary);
        System.out.println(summary);
    }

    private static void initialise() {
        Locale.setDefault(Locale.US);
        Configuration config = new Configuration();
        Configuration.setInstance(config);
        WPPluginManager.initialise(config.getUuid(), WPContext.INSTANCE);
    }

    private static World2 build(Model model, Map<String, Object> result) {
        Recipe recipe = model.recipe;
        Platform platform = recipe.target().equals("26.1") ? JAVA_ANVIL_26_1 : JAVA_ANVIL_1_21_11;
        HeightMapTileFactory factory = TileFactoryFactory.createFlatTileFactory(
                recipe.seed, Terrain.GRASS, MIN_Y, MAX_Y, model.base, model.sea, false, false);
        World2 world = new World2(platform, recipe.seed, factory);
        world.setName(recipe.name);
        world.setSpawnPoint(new Point(model.spawn.x, model.spawn.z));
        var dimension = world.getDimension(NORMAL_DETAIL);
        if (recipe.layout() == Shape.ISLAND) {
            dimension.setBorder(org.pepsoft.worldpainter.Dimension.Border.ENDLESS_WATER);
            dimension.setBorderLevel(model.sea);
            dimension.setBorderSize(0);
        }

        // A halo keeps slopes continuous across tiles and avoids recomputing expensive landforms for each neighbour.
        int stride = TILE_SIZE + 2;
        Land[] halo = new Land[stride * stride];
        Coverage coverage = new Coverage();
        for (int tz = 0; tz < recipe.length / TILE_SIZE; tz++) {
            for (int tx = 0; tx < recipe.width / TILE_SIZE; tx++) {
                Tile tile = new Tile(tx, tz, MIN_Y, MAX_Y);
                tile.inhibitEvents();
                int startX = tx * TILE_SIZE, startZ = tz * TILE_SIZE;
                for (int z = -1; z <= TILE_SIZE; z++) {
                    for (int x = -1; x <= TILE_SIZE; x++) {
                        halo[(z + 1) * stride + x + 1] = model.land(startX + x, startZ + z);
                    }
                }
                for (int z = 0; z < TILE_SIZE; z++) {
                    for (int x = 0; x < TILE_SIZE; x++) {
                        int i = (z + 1) * stride + x + 1;
                        Land land = halo[i];
                        double slope = slope(halo[i - 1], halo[i + 1], halo[i - stride], halo[i + stride]);
                        Surface surface = model.surface(startX + x, startZ + z, land, slope);
                        tile.setHeight(x, z, land.elevation);
                        tile.setWaterLevel(x, z, land.water);
                        tile.setTerrain(x, z, surface.terrain);
                        tile.setLayerValue(Biome.INSTANCE, x, z, surface.biome);
                        if (surface.trees > 0) {
                            tile.setLayerValue(surface.woodland, x, z, surface.trees);
                        }
                        if (surface.snow) {
                            tile.setBitLayerValue(Frost.INSTANCE, x, z, true);
                        }
                        if (surface.caves > 0) {
                            tile.setLayerValue(Caves.INSTANCE, x, z, surface.caves);
                        }
                        coverage.add(land, surface);
                    }
                }
                tile.releaseEvents();
                dimension.addTile(tile);
            }
            if ((tz + 1) % 8 == 0) {
                System.err.printf(Locale.ROOT, "Painted %d/%d tile rows%n", tz + 1, recipe.length / TILE_SIZE);
            }
        }
        result.put("terrain", coverage.report(1));
        return world;
    }

    private static String export(World2 world, Path output) throws Exception {
        var manager = PlatformManager.getInstance();
        var platform = world.getPlatform();
        if (!(manager.getPlatformProvider(platform) instanceof JavaPlatformProvider provider)) {
            throw new IllegalArgumentException("Export requires a built-in Minecraft Java platform");
        }
        String folderName = FileUtils.sanitiseName(world.getName());
        require(folderName != null && !folderName.isBlank(), "World needs a name for the Minecraft save folder");
        Path saves = Files.createDirectory(output.resolve("minecraft"));
        Path save = saves.resolve(folderName).normalize();
        require(saves.equals(save.getParent()), "World name does not resolve to a single save folder");
        for (int i = 0; i < Terrain.CUSTOM_TERRAIN_COUNT; i++) {
            Terrain.setCustomMaterial(i, world.getMixedMaterial(i));
        }
        var exporter = manager.getExporter(world, EXPORT_EVERYTHING);
        System.err.println("Exporting Minecraft save...");
        exporter.export(saves.toFile(), world.getName(), null, null);
        require(Files.isRegularFile(save.resolve("level.dat")), "Exporter did not produce level.dat");
        File dimension = provider.getDimensionDir(platform, save.toFile(), DIM_NORMAL);
        File[] regions = provider.getRegionFiles(platform, new File(dimension, "region"), DataType.REGION);
        require(regions != null && Arrays.stream(regions).anyMatch(file -> file.isFile() && file.length() > 0),
                "Exporter did not produce Overworld region files");
        return "minecraft/" + folderName;
    }

    private static void render(Model model, Path target, Map<String, Object> result) throws IOException {
        Recipe recipe = model.recipe;
        int step = Math.max(1, (Math.max(recipe.width, recipe.length) + 767) / 768);
        int width = (recipe.width + step - 1) / step, height = (recipe.length + step - 1) / step;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Coverage coverage = new Coverage();
        for (int py = 0; py < height; py++) {
            int z = Math.min(recipe.length - 1, py * step + step / 2);
            for (int px = 0; px < width; px++) {
                int x = Math.min(recipe.width - 1, px * step + step / 2);
                Land land = model.land(x, z);
                Land west = model.land(x - 1, z), east = model.land(x + 1, z);
                Land north = model.land(x, z - 1), south = model.land(x, z + 1);
                Surface surface = model.surface(x, z, land, slope(west, east, north, south));
                coverage.add(land, surface);
                double shade = clamp(0.99 + 0.045 * (east.elevation - west.elevation
                        + south.elevation - north.elevation),
                        0.62, 1.29);
                if (land.height < land.water) {
                    shade = clamp(1.20 - (land.water - land.height) * 0.014, 0.64, 1.16);
                }
                image.setRGB(px, py, shade(surface.colour, shade));
            }
        }
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            for (Waypoint waypoint : list(recipe.waypoints)) {
                marker(graphics, waypoint.x / step, waypoint.z / step, waypoint.name, new Color(0xffc573), width, height);
            }
            marker(graphics, model.spawn.x / step, model.spawn.z / step, "Spawn", Color.WHITE, width, height);
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "PNG", target.toFile())) {
            throw new IOException("PNG writer is unavailable");
        }
        result.put("terrain", coverage.report(step));
    }

    private static void marker(Graphics2D graphics, int x, int y, String label, Color colour, int width, int height) {
        int textX = Math.max(1, Math.min(x + 7, width - graphics.getFontMetrics().stringWidth(label) - 3));
        int textY = Math.min(height - 3, Math.max(12, y + 4));
        graphics.setColor(new Color(0x17212b));
        graphics.fillOval(x - 5, y - 5, 11, 11);
        graphics.drawString(label, textX + 1, textY + 1);
        graphics.setColor(colour);
        graphics.fillOval(x - 3, y - 3, 7, 7);
        graphics.drawString(label, textX, textY);
    }

    private static int shade(int rgb, double amount) {
        int r = Math.min(255, (int) ((rgb >> 16) * amount));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * amount));
        int b = Math.min(255, (int) ((rgb & 255) * amount));
        return (r << 16) | (g << 8) | b;
    }

    private static double slope(Land west, Land east, Land north, Land south) {
        return Math.hypot(east.elevation - west.elevation, south.elevation - north.elevation) * 0.5;
    }

    private static Map<String, Integer> coordinates(Position position, int y) {
        return Map.of("x", position.x, "y", y, "z", position.z);
    }

    private static double elapsed(long start) {
        return (System.nanoTime() - start) / 1.0e9;
    }

    private static <T> List<T> list(List<T> entries) {
        return entries == null ? List.of() : entries;
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static double smooth(double value) {
        double t = clamp(value, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void checkName(String name) {
        require(name != null && name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}") && !name.endsWith(" ")
                        && !name.matches("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"),
                "World name must be a portable filename of 1–64 letters, digits, spaces, hyphens or underscores");
    }

    private static final class Coverage {
        private final long[] biomeCounts = new long[HIGHEST_BIOME_ID + 1];
        private long samples, wet, wooded, trails, rivers, caves;
        private int low = MAX_Y, high = MIN_Y;

        void add(Land land, Surface surface) {
            samples++;
            biomeCounts[surface.biome]++;
            low = Math.min(low, land.height);
            high = Math.max(high, land.height);
            if (surface.trees > 0) {
                wooded++;
            }
            if (surface.caves > 0) {
                caves++;
            }
            if (surface.terrain == Terrain.GRASS_PATH) {
                trails++;
            }
            if (land.height < land.water) {
                wet++;
                if (land.river) {
                    rivers++;
                }
            }
        }

        Map<String, Object> report(int stride) {
            var biomes = new TreeMap<String, Long>();
            for (int i = 0; i < biomeCounts.length; i++) {
                if (biomeCounts[i] > 0) {
                    biomes.put(Minecraft1_21Biomes.BIOME_NAMES[i], biomeCounts[i]);
                }
            }
            return Map.of("sampleStride", stride, "sampleColumns", samples, "minY", low, "maxY", high,
                    "waterColumns", wet, "forestColumns", wooded, "trailColumns", trails, "riverColumns", rivers,
                    "caveLayerColumns", caves, "biomeColumns", biomes);
        }
    }

    /** Keeps preferences isolated from the desktop application's settings and Windows registry. */
    public static final class SessionPreferences implements PreferencesFactory {
        private final Preferences user = new PreferenceNode(null, "");
        private final Preferences system = new PreferenceNode(null, "");

        @Override
        public Preferences userRoot() {
            return user;
        }

        @Override
        public Preferences systemRoot() {
            return system;
        }
    }

    private static final class PreferenceNode extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, PreferenceNode> children = new HashMap<>();

        PreferenceNode(PreferenceNode parent, String name) {
            super(parent, name);
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            ((PreferenceNode) parent()).children.remove(name());
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, key -> new PreferenceNode(this, key));
        }

        @Override
        protected void syncSpi() {}

        @Override
        protected void flushSpi() {}
    }

    record Recipe(int version, String name, Long seed, int width, int length, String minecraftVersion,
                  Shape shape, Integer seaLevel, Integer baseHeight, Integer relief, Integer snowLine,
                  Integer caves, Palette defaultRegion, Position spawn, List<Landform> landforms,
                  List<Region> regions, List<Waterway> waterways, List<Trail> trails, List<Waypoint> waypoints) {
        String target() {
            return minecraftVersion == null ? "1.21.11" : minecraftVersion;
        }

        Shape layout() {
            return shape == null ? Shape.ISLAND : shape;
        }

        int sea() {
            return seaLevel == null ? 62 : seaLevel;
        }

        void validate() {
            require(version == 1, "Recipe version must be 1");
            checkName(name);
            require(seed != null, "Provide an integer seed");
            require(width >= TILE_SIZE && length >= TILE_SIZE && width <= MAX_SIDE && length <= MAX_SIDE
                            && width % TILE_SIZE == 0 && length % TILE_SIZE == 0 && (long) width * length <= MAX_COLUMNS,
                    "Width and length must be multiples of 128 from 128 to 16384, with at most 67108864 columns");
            require(Set.of("1.21.11", "26.1").contains(target()), "minecraftVersion must be 1.21.11 or 26.1");
            require(sea() >= 0 && sea() <= 128, "seaLevel must be in [0, 128]");
            require(baseHeight == null || (baseHeight > sea() && baseHeight <= 240),
                    "baseHeight must be above sea level and at most 240");
            require(relief == null || (relief >= 0 && relief <= 80), "relief must be in [0, 80]");
            require(snowLine == null || (snowLine > sea() && snowLine <= MAX_Y),
                    "snowLine must be above sea level and at most 320");
            require(caves == null || (caves >= 0 && caves <= 15), "caves must be in [0, 15]");
            require(list(landforms).size() <= 32 && list(regions).size() <= 32
                            && list(waterways).size() <= 16 && list(trails).size() <= 16 && list(waypoints).size() <= 24,
                    "Too many landforms, regions, waterways, trails or waypoints");
            if (spawn != null) {
                checkPosition(spawn.x, spawn.z);
            }
            for (Landform landform : list(landforms)) {
                require(landform != null && landform.kind != null, "Each landform needs a kind");
                checkPosition(landform.x, landform.z);
                require(validRadius(landform.radiusX) && validRadius(landform.radiusZ)
                                && landform.height >= 1 && landform.height <= 240
                                && Double.isFinite(landform.rotation()),
                        "Landforms require valid radii, a height in [1, 240], and a finite angle");
            }
            for (Region region : list(regions)) {
                require(region != null && region.palette != null && validRadius(region.radius), "Invalid region");
                checkPosition(region.x, region.z);
            }
            for (Waterway water : list(waterways)) {
                require(water != null && water.width >= 4 && water.width <= 256 && water.depth >= 2 && water.depth <= 40,
                        "Waterways need a width in [4, 256] and depth in [2, 40]");
                require(water.level == null || (water.level >= sea() && water.level <= 200),
                        "Waterway level must be between sea level and 200");
                checkRoute(water.points);
            }
            for (Trail trail : list(trails)) {
                require(trail != null && trail.width >= 2 && trail.width <= 12, "Trails need a width in [2, 12]");
                checkRoute(trail.points);
            }
            Set<String> names = new HashSet<>();
            for (Waypoint waypoint : list(waypoints)) {
                require(waypoint != null && waypoint.name != null && !waypoint.name.isBlank()
                                && waypoint.name.length() <= 40 && names.add(waypoint.name),
                        "Waypoints need unique names of 1–40 characters");
                checkPosition(waypoint.x, waypoint.z);
            }
        }

        private boolean validRadius(int radius) {
            return radius >= 8 && radius <= Math.max(width, length);
        }

        private void checkPosition(int x, int z) {
            require(x >= 0 && x < width && z >= 0 && z < length, "Point outside map: " + x + ", " + z);
        }

        private void checkRoute(List<Position> points) {
            require(points != null && points.size() >= 2 && points.size() <= 32, "Routes require 2–32 points");
            Position previous = null;
            for (Position point : points) {
                require(point != null && !point.equals(previous), "Adjacent route points must be distinct");
                checkPosition(point.x, point.z);
                previous = point;
            }
        }
    }

    enum Shape { ISLAND, MAINLAND }
    enum Form { HILL, RIDGE, MESA, BASIN }
    enum Palette {
        MEADOW(BIOME_MEADOW, Terrain.GRASS, null, 0, 0x88a859),
        FOREST(BIOME_FOREST, Terrain.GRASS, DeciduousForest.INSTANCE, 9, 0x4e8446),
        TAIGA(BIOME_TAIGA, Terrain.PODZOL, PineForest.INSTANCE, 9, 0x54776a),
        JUNGLE(BIOME_JUNGLE, Terrain.GRASS, Jungle.INSTANCE, 12, 0x388646),
        SWAMP(BIOME_SWAMPLAND, Terrain.GRASS, SwampLand.INSTANCE, 6, 0x788852),
        DESERT(BIOME_DESERT, Terrain.DESERT, null, 0, 0xd6b674),
        BADLANDS(BIOME_MESA, Terrain.MESA, null, 0, 0xc37b4d),
        ALPINE(BIOME_WINDSWEPT_HILLS, Terrain.GRASS, PineForest.INSTANCE, 3, 0x91a07a);

        final int biome, density, colour;
        final Terrain terrain;
        final Layer woodland;

        Palette(int biome, Terrain terrain, Layer woodland, int density, int colour) {
            this.biome = biome;
            this.terrain = terrain;
            this.woodland = woodland;
            this.density = density;
            this.colour = colour;
        }
    }

    record Position(int x, int z) {}
    record Landform(Form kind, int x, int z, int radiusX, int radiusZ, int height, Double angle) {
        double rotation() {
            return angle == null ? 0 : angle;
        }
    }
    record Region(Palette palette, int x, int z, int radius) {}
    record Waterway(int width, int depth, Integer level, List<Position> points) {}
    record Trail(int width, List<Position> points) {}
    record Waypoint(String name, int x, int z) {}
    record Land(float elevation, int height, int water, boolean river) {}
    record Surface(Terrain terrain, int biome, Layer woodland, int trees, boolean snow, int caves, int colour) {}

    private record Massif(Landform form, double sin, double cos, int bound) {
        Massif(Landform form) {
            this(form, Math.sin(Math.toRadians(form.rotation())), Math.cos(Math.toRadians(form.rotation())),
                    Math.max(form.radiusX, form.radiusZ));
        }

        double elevation(int x, int z, double detail) {
            double dx = x - form.x, dz = z - form.z;
            if (Math.abs(dx) > bound || Math.abs(dz) > bound) {
                return 0;
            }
            double u = (cos * dx + sin * dz) / form.radiusX;
            double v = (cos * dz - sin * dx) / form.radiusZ;
            double d = u * u + v * v;
            if (d >= 1) {
                return 0;
            }
            return form.height * switch (form.kind) {
                case HILL -> smooth(1 - d) * (1 + detail * 0.35);
                case RIDGE -> smooth(1 - d) * (1 + detail * 0.85);
                case MESA -> smooth((1 - d) * 2.3) * (1 + detail * 0.13);
                case BASIN -> -smooth(1 - d);
            };
        }
    }

    private record Segment(double x, double z, double dx, double dz, double inverseLengthSquared,
                           double minX, double maxX, double minZ, double maxZ) {
        Segment(double x, double z, double endX, double endZ, double padding) {
            this(x, z, endX - x, endZ - z,
                    1.0 / (Math.pow(endX - x, 2) + Math.pow(endZ - z, 2)),
                    Math.min(x, endX) - padding, Math.max(x, endX) + padding,
                    Math.min(z, endZ) - padding, Math.max(z, endZ) + padding);
        }

        double distanceSquared(double px, double pz) {
            if (px < minX || px > maxX || pz < minZ || pz > maxZ) {
                return Double.POSITIVE_INFINITY;
            }
            double rx = px - x, rz = pz - z;
            double t = Double.isFinite(inverseLengthSquared)
                    ? clamp((rx * dx + rz * dz) * inverseLengthSquared, 0, 1) : 0;
            double u = rx - t * dx, v = rz - t * dz;
            return u * u + v * v;
        }
    }

    private record Route(Segment[][] buckets, int tilesX, int tilesZ, double radius, int level, int depth) {
        Route(Recipe recipe, List<Position> points, double radius, int level, int depth) {
            this(buckets(points, radius * 2.5, recipe.width / TILE_SIZE, recipe.length / TILE_SIZE),
                    recipe.width / TILE_SIZE, recipe.length / TILE_SIZE, radius, level, depth);
        }

        private static Segment[][] buckets(List<Position> points, double padding, int tilesX, int tilesZ) {
            Map<Integer, List<Segment>> index = new HashMap<>();
            for (int i = 0; i < points.size() - 1; i++) {
                Position a = points.get(Math.max(0, i - 1)), b = points.get(i);
                Position c = points.get(i + 1), d = points.get(Math.min(i + 2, points.size() - 1));
                int steps = (int) clamp(Math.ceil(Math.hypot(c.x - b.x, c.z - b.z) / 18), 4, 32);
                double x = b.x, z = b.z;
                for (int j = 1; j <= steps; j++) {
                    double t = j / (double) steps;
                    double nextX = cubic(a.x, b.x, c.x, d.x, t), nextZ = cubic(a.z, b.z, c.z, d.z, t);
                    Segment segment = new Segment(x, z, nextX, nextZ, padding);
                    for (int tz = bucket(segment.minZ, tilesZ); tz <= bucket(segment.maxZ, tilesZ); tz++) {
                        for (int tx = bucket(segment.minX, tilesX); tx <= bucket(segment.maxX, tilesX); tx++) {
                            index.computeIfAbsent(tz * tilesX + tx, key -> new ArrayList<>()).add(segment);
                        }
                    }
                    x = nextX;
                    z = nextZ;
                }
            }
            Segment[][] buckets = new Segment[tilesX * tilesZ][];
            index.forEach((key, segments) -> buckets[key] = segments.toArray(Segment[]::new));
            return buckets;
        }

        private static double cubic(int a, int b, int c, int d, double t) {
            return b + 0.5 * t * ((c - a) + t * ((2 * a - 5 * b + 4 * c - d) + t * (-a + 3 * b - 3 * c + d)));
        }

        private static int bucket(double coordinate, int count) {
            return Math.max(0, Math.min((int) (coordinate / TILE_SIZE), count - 1));
        }

        double distanceSquared(double x, double z) {
            double nearest = Double.POSITIVE_INFINITY;
            Segment[] segments = buckets[bucket(z, tilesZ) * tilesX + bucket(x, tilesX)];
            if (segments == null) {
                return nearest;
            }
            for (Segment segment : segments) {
                nearest = Math.min(nearest, segment.distanceSquared(x, z));
            }
            return nearest;
        }
    }

    private static final class Model {
        final Recipe recipe;
        final int sea, base, relief, snowLine, caveStrength;
        final double coastScale, edgeWidth;
        final Massif[] massifs;
        final Region[] regions;
        final Route[] rivers, trails;
        final PerlinNoise macro, details, cover;
        final Palette defaultPalette;
        final Position spawn;

        Model(Recipe recipe) {
            this.recipe = recipe;
            sea = recipe.sea();
            base = recipe.baseHeight == null ? sea + 22 : recipe.baseHeight;
            relief = recipe.relief == null ? 27 : recipe.relief;
            snowLine = recipe.snowLine == null ? 174 : recipe.snowLine;
            caveStrength = recipe.caves == null ? 5 : recipe.caves;
            int shorter = Math.min(recipe.width, recipe.length);
            coastScale = Math.max(48, shorter / 5.0);
            edgeWidth = clamp(shorter / 12.0, 10, 192);
            defaultPalette = recipe.defaultRegion == null ? Palette.MEADOW : recipe.defaultRegion;
            macro = new PerlinNoise(recipe.seed);
            details = new PerlinNoise(recipe.seed ^ 0x2f112a9034417e75L);
            cover = new PerlinNoise(recipe.seed ^ 0x44ac321c0d983c9bL);
            massifs = list(recipe.landforms).stream().map(Massif::new).toArray(Massif[]::new);
            regions = list(recipe.regions).toArray(Region[]::new);
            rivers = list(recipe.waterways).stream()
                    .map(water -> new Route(recipe, water.points, water.width * 0.5,
                            water.level == null ? sea : water.level, water.depth)).toArray(Route[]::new);
            trails = list(recipe.trails).stream()
                    .map(trail -> new Route(recipe, trail.points, trail.width * 0.5, 0, 0)).toArray(Route[]::new);
            spawn = findSpawn();
        }

        Land land(int x, int z) {
            double broad = macro.getPerlinNoise(x / coastScale, z / coastScale);
            double folds = details.getPerlinNoise(x / 82.0, z / 82.0);
            double texture = details.getPerlinNoise(x / 25.0, z / 25.0);
            double height = base + relief * (1.65 * broad + 0.62 * folds + 0.18 * texture);
            for (Massif massif : massifs) {
                height += massif.elevation(x, z, folds + texture * 0.35);
            }
            if (recipe.layout() == Shape.ISLAND) {
                double u = (2.0 * x / (recipe.width - 1) - 1);
                double v = (2.0 * z / (recipe.length - 1) - 1);
                double bays = macro.getPerlinNoise(x / (coastScale * 0.53) + 5, z / (coastScale * 0.53) - 3);
                double shore = u * u + v * v + broad * 0.84 + bays * 0.30 + folds * 0.09;
                double edge = Math.min(Math.min(x, z), Math.min(recipe.width - 1 - x, recipe.length - 1 - z));
                double land = Math.min(smooth((1.14 - shore) / 0.68), smooth(edge / edgeWidth));
                double seabed = sea - 29 + 4 * folds;
                height = seabed + (height - seabed) * land;
            }
            double naturalHeight = height;
            int water = sea;
            boolean river = false;
            for (Route route : rivers) {
                double warpedX = x + texture * Math.min(9, route.radius * 0.6);
                double warpedZ = z + folds * Math.min(9, route.radius * 0.6);
                double d2 = route.distanceSquared(warpedX, warpedZ);
                if (d2 < route.radius * route.radius * 6.25) {
                    double ratio = Math.sqrt(d2) / route.radius;
                    double bed = route.level - route.depth * (1 - 0.75 * smooth(ratio));
                    height = Math.min(height, bed + (height - bed) * smooth((ratio - 0.72) / 1.78));
                    if (ratio <= 1) {
                        water = Math.max(water, route.level);
                        river |= naturalHeight >= sea - 2 || route.level > sea;
                    }
                }
            }
            // WorldPainter stores elevation at 1/256 block precision.
            float elevation = Math.round((float) clamp(height, MIN_Y + 5, MAX_Y - 5) * 256) / 256f;
            return new Land(elevation, Math.round(elevation), water, river);
        }

        Surface surface(int x, int z, Land land, double slope) {
            int h = land.height;
            double patch = cover.getPerlinNoise(x / 46.0, z / 46.0);
            if (h < land.water) {
                int depth = land.water - h;
                Terrain bottom = depth < 5 && patch > -0.13 ? Terrain.SAND : Terrain.GRAVEL;
                return new Surface(bottom, land.river ? BIOME_RIVER : depth > 18 ? BIOME_DEEP_OCEAN : BIOME_OCEAN,
                        null, 0, false, 0, land.river ? 0x418f9d : 0x367a9c);
            }
            boolean nearSpawn = spawn != null && Math.abs(x - spawn.x) <= 12 && Math.abs(z - spawn.z) <= 12;
            Palette palette = palette(x, z, patch);
            int caveValue = nearSpawn || h <= sea + 8 ? 0
                    : (int) Math.round(caveStrength * smooth((patch + 0.28) * 2.8));
            boolean snow = h >= snowLine + patch * 26 && palette != Palette.BADLANDS && palette != Palette.DESERT;
            if (h <= sea + 3 && slope <= 0.85 && palette != Palette.SWAMP) {
                return new Surface(Terrain.SAND, BIOME_BEACH, null, 0, false, caveValue, 0xddc68d);
            }
            if (!snow && slope <= 1 && onTrail(x, z)) {
                return new Surface(Terrain.GRASS_PATH, palette.biome, null, 0, false, caveValue, 0xc4a46b);
            }
            if (snow || (slope >= 1.15 && palette != Palette.BADLANDS && palette != Palette.DESERT)) {
                return new Surface(Terrain.STONE_MIX, snow ? BIOME_FROZEN_PEAKS : palette.biome,
                        null, 0, snow, caveValue, snow ? 0xe5edf0 : 0x879082);
            }
            int trees = nearSpawn || palette.woodland == null ? 0
                    : (int) Math.round(palette.density * smooth((patch + 0.21) * 3.25));
            int colour = trees > 0 ? shade(palette.colour, 0.91 - trees * 0.014) : palette.colour;
            return new Surface(palette.terrain, palette.biome, palette.woodland, trees, false, caveValue, colour);
        }

        private Palette palette(int x, int z, double patch) {
            Palette chosen = defaultPalette;
            double best = 1.0 + patch * 0.38;
            for (Region region : regions) {
                double dx = (x - region.x) / (double) region.radius;
                double dz = (z - region.z) / (double) region.radius;
                double d = dx * dx + dz * dz;
                if (d < best) {
                    best = d;
                    chosen = region.palette;
                }
            }
            return chosen;
        }

        private boolean onTrail(int x, int z) {
            for (Route trail : trails) {
                if (trail.distanceSquared(x, z) <= trail.radius * trail.radius) {
                    return true;
                }
            }
            return false;
        }

        private Position findSpawn() {
            if (recipe.spawn != null) {
                require(safe(recipe.spawn.x, recipe.spawn.z), "Spawn needs a dry, gentle area at least 4 blocks from the edge");
                return recipe.spawn;
            }
            int cx = recipe.width / 2, cz = recipe.length / 2;
            int stride = Math.max(4, Math.min(recipe.width, recipe.length) / 128);
            Position best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (int z = 4; z < recipe.length - 4; z += stride) {
                for (int x = 4; x < recipe.width - 4; x += stride) {
                    double dx = (x - cx) / (double) cx, dz = (z - cz) / (double) cz;
                    double distance = dx * dx + dz * dz;
                    if (distance < bestScore && safe(x, z)) {
                        best = new Position(x, z);
                        bestScore = distance;
                    }
                }
            }
            require(best != null, "No dry, gentle spawn found. Adjust the terrain or provide a spawn");
            return best;
        }

        private boolean safe(int x, int z) {
            if (x < 4 || z < 4 || x >= recipe.width - 4 || z >= recipe.length - 4) {
                return false;
            }
            Land center = land(x, z);
            if (center.height < center.water + 2 || center.height >= snowLine - 8) {
                return false;
            }
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    Land nearby = land(x + dx, z + dz);
                    if (nearby.height <= nearby.water || Math.abs(center.height - nearby.height) > 2) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
