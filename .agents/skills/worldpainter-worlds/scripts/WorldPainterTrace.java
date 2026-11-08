// SPDX-License-Identifier: GPL-3.0-or-later
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.pepsoft.util.PerlinNoise;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferUShort;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Converts an overhead terrain image into a WorldPainter recipe, elevation and surface labels. */
public class WorldPainterTrace {
    private static final int SEA = 62, LOW = -59, HIGH = 315;
    private static final int MAX_SOURCE = 2048;
    private static final JsonMapper JSON = new JsonMapper();

    /** Run with reference, output, blocks, name, seed, target, crop, water mask, water bias and relief. */
    public static void main(String[] args) {
        try {
            run(args);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        require(args.length == 10, "Usage: <image> <new-dir> <blocks> <name> <seed> <target>"
                + " <x,z,width,height or -> <water-mask or -> <water-bias> <relief-scale>");
        long start = System.nanoTime();
        int blocks = Integer.parseInt(args[2]), bias = Integer.parseInt(args[8]);
        long seed = Long.parseLong(args[4]);
        double relief = Double.parseDouble(args[9]);
        String name = args[3], target = args[5];
        require(blocks >= 128 && blocks <= 8192 && blocks % 128 == 0,
                "Blocks must be a multiple of 128 from 128 to 8192");
        require(name.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}") && !name.endsWith(" ")
                        && !name.matches("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])"),
                "World name must be a portable filename of 1–64 letters, digits, spaces, hyphens or underscores");
        require(target.equals("1.21.11") || target.equals("26.1"), "Target must be 1.21.11 or 26.1");
        require(bias >= -32 && bias <= 32 && relief >= 0.25 && relief <= 1.8,
                "Water bias must be in [-32, 32] and relief scale in [0.25, 1.8]");
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        require(!Files.exists(output), "Output already exists: " + output);

        BufferedImage original = read(Path.of(args[0]));
        Rectangle crop = args[6].equals("-") ? trim(original) : crop(args[6], original);
        BufferedImage mask = args[7].equals("-") ? null : read(Path.of(args[7]));
        require(mask == null || (mask.getWidth() == original.getWidth() && mask.getHeight() == original.getHeight()),
                "Water mask must match the uncropped reference dimensions");
        double analysisScale = Math.min(1.0, MAX_SOURCE / (double) Math.max(crop.width, crop.height));
        int sourceWidth = Math.max(1, (int) Math.round(crop.width * analysisScale));
        int sourceLength = Math.max(1, (int) Math.round(crop.height * analysisScale));
        require(Math.min(sourceWidth, sourceLength) >= 12, "Reference crop is too narrow for terrain analysis");
        BufferedImage reference = resize(original, crop, sourceWidth, sourceLength, false);
        BufferedImage waterMask = mask == null ? null : resize(mask, crop, sourceWidth, sourceLength, true);
        Trace trace = new Trace(reference, waterMask, bias, relief);

        int contentWidth = Math.max(1, (int) Math.round(blocks * crop.width / (double) Math.max(crop.width, crop.height)));
        int contentLength = Math.max(1, (int) Math.round(blocks * crop.height / (double) Math.max(crop.width, crop.height)));
        int width = roundTile(contentWidth), length = roundTile(contentLength);
        boolean ocean = trace.wetEdgeFraction >= 0.75;
        int left = (width - contentWidth) / 2, top = (length - contentLength) / 2;
        BufferedImage heightmap = new BufferedImage(width, length, BufferedImage.TYPE_USHORT_GRAY);
        BufferedImage surface = new BufferedImage(width, length, BufferedImage.TYPE_BYTE_GRAY);
        short[] heights = ((DataBufferUShort) heightmap.getRaster().getDataBuffer()).getData();
        byte[] covers = ((DataBufferByte) surface.getRaster().getDataBuffer()).getData();
        int[] counts = new int[Cover.CODES.length];
        int minY = HIGH, maxY = LOW;
        PerlinNoise noise = new PerlinNoise(seed);
        for (int z = 0; z < length; z++) {
            double sy = ((z - top + 0.5) * sourceLength / contentLength) - 0.5;
            for (int x = 0; x < width; x++) {
                double sx = ((x - left + 0.5) * sourceWidth / contentWidth) - 0.5;
                boolean padding = x < left || x >= left + contentWidth || z < top || z >= top + contentLength;
                Cover cover = padding && ocean ? Cover.WATER : trace.coverAt(sx, sy);
                double elevation = padding && ocean ? SEA - 22 : trace.elevationAt(sx, sy);
                if (cover.wet) {
                    elevation = Math.min(SEA - 2.0, elevation);
                } else {
                    double detail = noise.getPerlinNoise(x / 18.0, z / 18.0)
                            + 0.35 * noise.getPerlinNoise(x / 7.0, z / 7.0);
                    if (cover == Cover.DESERT || cover == Cover.BADLANDS) {
                        detail += 0.8 * noise.getPerlinNoise(x / 16.0 + z / 53.0, z / 31.0);
                    }
                    elevation = Math.max(SEA + 1.0, elevation + detail * clamp((elevation - SEA) / 8, 0, 9));
                }
                elevation = clamp(elevation, LOW, HIGH);
                int i = z * width + x;
                heights[i] = (short) Math.round((elevation - LOW) * 65535 / (HIGH - LOW));
                covers[i] = (byte) cover.ordinal();
                counts[cover.ordinal()]++;
                int y = (int) Math.round(elevation);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }

        Position spawn = spawn(trace, contentWidth, contentLength, left, top);
        Files.createDirectory(output);
        Path inputs = Files.createDirectory(output.resolve("inputs"));
        writePng(heightmap, inputs.resolve("elevation.png"));
        writePng(surface, inputs.resolve("surface.png"));
        Map<String, Object> recipe = new LinkedHashMap<>();
        recipe.put("version", 1);
        recipe.put("name", name);
        recipe.put("seed", seed);
        recipe.put("width", width);
        recipe.put("length", length);
        recipe.put("minecraftVersion", target);
        recipe.put("shape", ocean ? "island" : "mainland");
        recipe.put("seaLevel", SEA);
        recipe.put("heightmap", new HeightSpec("inputs/elevation.png", LOW, HIGH));
        recipe.put("surfaceMap", "inputs/surface.png");
        if (spawn != null && safeSpawn(spawn, heights, width, length)) {
            recipe.put("spawn", spawn);
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("recipe.json").toFile(), recipe);

        writeComparison(reference, trace, output.resolve("comparison.png"));
        Map<String, Integer> surfaceCounts = new LinkedHashMap<>();
        for (Cover cover : Cover.CODES) {
            if (counts[cover.ordinal()] > 0) {
                surfaceCounts.put(cover.name().toLowerCase(Locale.ROOT), counts[cover.ordinal()]);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("recipe", "recipe.json");
        report.put("comparison", "comparison.png");
        report.put("width", width);
        report.put("length", length);
        report.put("crop", Map.of("x", crop.x, "z", crop.y, "width", crop.width, "height", crop.height));
        report.put("analysisPixels", Map.of("width", sourceWidth, "height", sourceLength));
        report.put("content", Map.of("x", left, "z", top, "width", contentWidth, "length", contentLength));
        report.put("estimatedElevation", Map.of("minY", minY, "maxY", maxY));
        report.put("waterColumns", counts[Cover.WATER.ordinal()] + counts[Cover.RIVER.ordinal()]);
        report.put("surfaceColumns", surfaceCounts);
        report.put("waterSource", mask == null ? "colour segmentation" : "supplied mask");
        report.put("waterBias", bias);
        report.put("reliefScale", relief);
        report.put("elapsedSeconds", Math.round((System.nanoTime() - start) / 1.0e7) / 100.0);
        Path pending = output.resolve(".trace.json.tmp"), manifest = output.resolve("trace.json");
        JSON.writerWithDefaultPrettyPrinter().writeValue(pending.toFile(), report);
        Files.move(pending, manifest);
        System.out.println(manifest);
    }

    private static BufferedImage read(Path path) throws IOException {
        require(Files.isRegularFile(path), "Image does not exist: " + path);
        try (var stream = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            require(readers.hasNext(), "Cannot decode image: " + path);
            var reader = readers.next();
            try {
                reader.setInput(stream);
                require((long) reader.getWidth(0) * reader.getHeight(0) <= 24_000_000,
                        "Image exceeds 24 megapixels. Resize it first.");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Rectangle crop(String argument, BufferedImage source) {
        String[] values = argument.split(",", -1);
        require(values.length == 4, "Crop must be x,z,width,height");
        Rectangle crop = new Rectangle(Integer.parseInt(values[0]), Integer.parseInt(values[1]),
                Integer.parseInt(values[2]), Integer.parseInt(values[3]));
        require(crop.x >= 0 && crop.y >= 0 && crop.width >= 12 && crop.height >= 12
                        && (long) crop.x + crop.width <= source.getWidth()
                        && (long) crop.y + crop.height <= source.getHeight(), "Crop falls outside image");
        return crop;
    }

    private static Rectangle trim(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        int left = 0, right = w, top = 0, bottom = h;
        int borderLimit = Math.min(48, Math.min(w, h) / 20);
        while (top < borderLimit && darkLine(image, top, true)) {
            top++;
        }
        while (h - bottom < borderLimit && darkLine(image, bottom - 1, true)) {
            bottom--;
        }
        while (left < borderLimit && darkLine(image, left, false)) {
            left++;
        }
        while (w - right < borderLimit && darkLine(image, right - 1, false)) {
            right--;
        }
        return new Rectangle(left, top, right - left, bottom - top);
    }

    private static boolean darkLine(BufferedImage image, int fixed, boolean horizontal) {
        int count = horizontal ? image.getWidth() : image.getHeight(), dark = 0;
        for (int i = 0; i < count; i++) {
            int rgb = horizontal ? image.getRGB(i, fixed) : image.getRGB(fixed, i);
            if ((rgb >>> 24) > 8 && Math.max((rgb >>> 16) & 255, Math.max((rgb >>> 8) & 255, rgb & 255)) < 20) {
                dark++;
            }
        }
        return dark >= count * 0.98;
    }

    private static BufferedImage resize(BufferedImage source, Rectangle crop, int width, int length, boolean nearest) {
        BufferedImage image = new BufferedImage(width, length, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, nearest
                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, width, length, crop.x, crop.y, crop.x + crop.width, crop.y + crop.height, null);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static Position spawn(Trace trace, int width, int length, int left, int top) {
        double best = Double.NEGATIVE_INFINITY;
        Position spawn = null;
        for (int z = 4; z < trace.h - 4; z++) {
            for (int x = 4; x < trace.w - 4; x++) {
                int i = z * trace.w + x;
                Cover cover = Cover.CODES[trace.codes[i]];
                double y = trace.elevation[i];
                if (cover.wet || cover == Cover.SNOW || cover == Cover.MAGMA || cover == Cover.VOLCANIC
                        || y < SEA + 4 || y > 112 || trace.inland[i] < 3) {
                    continue;
                }
                double steepness = Math.abs(trace.elevation[i + 2] - trace.elevation[i - 2])
                        + Math.abs(trace.elevation[i + 2 * trace.w] - trace.elevation[i - 2 * trace.w]);
                if (steepness > 1.1) {
                    continue;
                }
                double centre = Math.hypot(x / (double) trace.w - 0.5, z / (double) trace.h - 0.5);
                double score = Math.min(trace.inland[i], 12) - steepness * 4 - centre * 7
                        + (cover == Cover.MEADOW ? 2 : 0);
                if (score > best) {
                    best = score;
                    spawn = new Position(left + (int) ((x + 0.5) * width / trace.w),
                            top + (int) ((z + 0.5) * length / trace.h));
                }
            }
        }
        return spawn;
    }

    private static boolean safeSpawn(Position spawn, short[] heights, int width, int length) {
        if (spawn.x < 4 || spawn.z < 4 || spawn.x >= width - 4 || spawn.z >= length - 4) {
            return false;
        }
        int center = decodeHeight(heights[spawn.z * width + spawn.x]);
        for (int z = spawn.z - 3; z <= spawn.z + 3; z++) {
            for (int x = spawn.x - 3; x <= spawn.x + 3; x++) {
                int height = decodeHeight(heights[z * width + x]);
                if (height <= SEA || Math.abs(height - center) > 2) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int decodeHeight(short value) {
        return (int) Math.round(LOW + Short.toUnsignedInt(value) * ((HIGH - LOW) / 65535.0));
    }

    private static void writeComparison(BufferedImage reference, Trace trace, Path file) throws IOException {
        int w = Math.min(420, trace.w), h = Math.max(1, (int) Math.round(trace.h * (w / (double) trace.w)));
        if (h > 420) {
            w = Math.max(1, (int) Math.round(w * (420.0 / h)));
            h = 420;
        }
        int gap = 14, label = 32;
        BufferedImage board = new BufferedImage(w * 3 + gap * 4, h + label + gap, BufferedImage.TYPE_INT_RGB);
        BufferedImage segments = new BufferedImage(trace.w, trace.h, BufferedImage.TYPE_INT_RGB);
        BufferedImage elevations = new BufferedImage(trace.w, trace.h, BufferedImage.TYPE_INT_RGB);
        for (int z = 0; z < trace.h; z++) {
            for (int x = 0; x < trace.w; x++) {
                int i = z * trace.w + x;
                Cover cover = Cover.CODES[trace.codes[i]];
                segments.setRGB(x, z, cover.colour);
                int shade = (int) clamp(65 + (trace.elevation[i] - SEA) * 0.82, 0, 255);
                elevations.setRGB(x, z, cover.wet ? 0x16416f + shade : shade * 0x010101);
            }
        }
        Graphics2D g = board.createGraphics();
        try {
            g.setColor(new Color(0x14202c));
            g.fillRect(0, 0, board.getWidth(), board.getHeight());
            g.setColor(new Color(0xe8f0f4));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            BufferedImage[] panels = {reference, segments, elevations};
            String[] titles = {"REFERENCE", "TRACED SURFACES", "ESTIMATED RELIEF"};
            for (int i = 0; i < panels.length; i++) {
                int x = gap + i * (w + gap);
                g.drawString(titles[i], x, 21);
                g.drawImage(panels[i], x, label, w, h, null);
            }
        } finally {
            g.dispose();
        }
        writePng(board, file);
    }

    private static void writePng(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "PNG", file.toFile())) {
            throw new IOException("PNG writer unavailable");
        }
    }

    private static int roundTile(int value) {
        return ((value + 127) / 128) * 128;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private record Position(int x, int z) {}
    private record HeightSpec(String file, int minY, int maxY) {}

    // Ordinals are shared with the runner's TracedSurface and are part of the recipe format.
    private enum Cover {
        WATER(0x286eb6, true), MEADOW(0x8db24f), FOREST(0x50844a), TAIGA(0x587864), JUNGLE(0x398645),
        SWAMP(0x77864e), DESERT(0xd6b674), BADLANDS(0xc37b4d), ROCK(0x848a88), SNOW(0xe5edf0),
        CHERRY(0xcb7f9d), VOLCANIC(0x4e4947), MAGMA(0xc15727), BEACH(0xe0c58f), RIVER(0x429fba, true);

        final int colour;
        final boolean wet;

        Cover(int colour) {
            this(colour, false);
        }

        Cover(int colour, boolean wet) {
            this.colour = colour;
            this.wet = wet;
        }

        private static final Cover[] CODES = values();
    }

    private static final class Trace {
        final int w, h;
        final byte[] codes;
        final int[] inland;
        final float[] elevation;
        final double wetEdgeFraction;

        Trace(BufferedImage image, BufferedImage mask, int bias, double relief) {
            w = image.getWidth();
            h = image.getHeight();
            int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
            int[] waterOverride = mask == null ? null : mask.getRGB(0, 0, w, h, null, 0, w);
            boolean[] wet = new boolean[rgb.length];
            int water = 0, border = 0, wetBorder = 0;
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int i = z * w + x;
                    wet[i] = waterOverride == null ? isWater(rgb[i], bias)
                            : (((waterOverride[i] >>> 16) & 255) + ((waterOverride[i] >>> 8) & 255)
                                    + (waterOverride[i] & 255)) >= 384;
                    if (wet[i]) {
                        water++;
                    }
                    if (x == 0 || x == w - 1 || z == 0 || z == h - 1) {
                        border++;
                        if (wet[i]) {
                            wetBorder++;
                        }
                    }
                }
            }
            require(water < rgb.length * 0.998, "Trace found almost no land. Check the crop or supply a water mask.");
            wetEdgeFraction = wetBorder / (double) border;
            inland = distances(wet, true);
            int[] offshore = distances(wet, false);
            codes = new byte[rgb.length];
            float[] mountain = new float[rgb.length], luminance = new float[rgb.length];
            float[] heat = new float[rgb.length];
            for (int i = 0; i < rgb.length; i++) {
                int c = rgb[i], r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
                luminance[i] = (r * 3 + g * 6 + b) / 10.0f;
                Cover cover = wet[i] ? Cover.WATER : classify(r, g, b, inland[i]);
                codes[i] = (byte) cover.ordinal();
                heat[i] = cover == Cover.MAGMA ? 1 : 0;
            }

            int scale = Math.max(3, Math.min(w, h) / 38);
            float[] hotNearby = blur(heat, Math.max(3, scale));
            regularise();
            for (int i = 0; i < codes.length; i++) {
                Cover cover = Cover.CODES[codes[i]];
                if (cover == Cover.BADLANDS && hotNearby[i] > 0.075 && luminance[i] < 125) {
                    cover = Cover.VOLCANIC;
                    codes[i] = (byte) cover.ordinal();
                }
                mountain[i] = switch (cover) {
                    case SNOW -> inland[i] <= 3 ? 0.32f : 1.0f;
                    case ROCK -> 0.84f;
                    case VOLCANIC, MAGMA -> 0.64f;
                    case BADLANDS -> 0.38f;
                    case DESERT -> 0.12f;
                    default -> 0;
                };
            }

            float[] broad = blur(mountain, scale), local = blur(mountain, Math.max(2, scale / 3));
            float[] light = blur(luminance, 2), lightLocal = blur(luminance, Math.max(3, scale / 2));
            elevation = new float[rgb.length];
            for (int i = 0; i < rgb.length; i++) {
                int x = i % w, z = i / w;
                Cover cover = Cover.CODES[codes[i]];
                if (cover.wet) {
                    boolean channel = offshore[i] <= 6 && narrow(wet, x, z);
                    if (channel) {
                        codes[i] = (byte) Cover.RIVER.ordinal();
                    }
                    elevation[i] = (float) (SEA - 2 - (channel ? Math.min(5, offshore[i] * 0.8)
                            : 30 * (1 - Math.exp(-offshore[i] / (double) (scale * 2)))));
                } else {
                    double edge = 1 - Math.exp(-inland[i] / Math.max(2.0, scale * 0.42));
                    double base = 2 + 15 * edge;
                    double height = 120 * broad[i] + 65 * local[i];
                    double texture = (light[i] - lightLocal[i]) * (0.10 + local[i] * 0.42);
                    elevation[i] = (float) (SEA + Math.max(1.2, base + edge * relief * (height + texture)));
                }
            }
        }

        private void regularise() {
            byte[] before = codes.clone();
            int[] votes = new int[Cover.CODES.length];
            for (int z = 1; z < h - 1; z++) {
                for (int x = 1; x < w - 1; x++) {
                    int i = z * w + x, center = before[i];
                    Cover cover = Cover.CODES[center];
                    if (cover.wet || cover == Cover.MAGMA || cover == Cover.CHERRY) {
                        continue;
                    }
                    Arrays.fill(votes, 0);
                    votes[center] = 2;
                    int best = center;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int candidate = before[i + dz * w + dx];
                            if (!Cover.CODES[candidate].wet && ++votes[candidate] > votes[best]) {
                                best = candidate;
                            }
                        }
                    }
                    codes[i] = (byte) best;
                }
            }
        }

        private static boolean isWater(int colour, int bias) {
            if ((colour >>> 24) < 96) {
                return true;
            }
            int r = (colour >>> 16) & 255, g = (colour >>> 8) & 255, b = colour & 255;
            int difference = 18 + bias;
            return b > 24 && b - r >= Math.max(9, difference)
                    && ((b - g >= 6 + bias / 3 && r <= g + 8)
                    || (b >= g - 8 - Math.max(0, -bias / 3) && g - r >= Math.max(20, difference + 9)));
        }

        private static Cover classify(int r, int g, int b, int inland) {
            int high = Math.max(r, Math.max(g, b)), low = Math.min(r, Math.min(g, b));
            int chroma = high - low;
            if (r >= 102 && r - g >= 13 && b - g >= 7 && r - b < 105) {
                return Cover.CHERRY;
            }
            if (chroma <= 37 && Math.min(r, g) >= 148 && b >= g - 22) {
                return Cover.SNOW;
            }
            if (r >= 185 && g <= 98 && b <= 53 && r - g >= 110) {
                return Cover.MAGMA;
            }
            if (g >= r + 7 && g >= b + 9) {
                if (g >= 108 && g > r * 1.47 && b < g * 0.38) {
                    return Cover.JUNGLE;
                }
                if (g >= 118 && r > 68) {
                    return Cover.MEADOW;
                }
                return g >= 48 ? Cover.FOREST : Cover.TAIGA;
            }
            if (r >= g + 9 && g >= b + 12) {
                if (r >= 147 && g >= r * 0.79) {
                    return inland <= 4 ? Cover.BEACH : Cover.DESERT;
                }
                return Cover.BADLANDS;
            }
            if (g >= r - 3 && g > b + 5 && chroma < 44) {
                return inland < 5 ? Cover.SWAMP : Cover.TAIGA;
            }
            if (high < 92 && chroma <= 28) {
                return Cover.VOLCANIC;
            }
            if (chroma < 37) {
                return Cover.ROCK;
            }
            return Cover.MEADOW;
        }

        private int[] distances(boolean[] wet, boolean toWater) {
            int[] dist = new int[wet.length];
            for (int i = 0; i < wet.length; i++) {
                dist[i] = (wet[i] == toWater) ? 0 : 1_000_000;
            }
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    int i = z * w + x;
                    if (x > 0) {
                        dist[i] = Math.min(dist[i], dist[i - 1] + 1);
                    }
                    if (z > 0) {
                        dist[i] = Math.min(dist[i], dist[i - w] + 1);
                    }
                }
            }
            for (int z = h - 1; z >= 0; z--) {
                for (int x = w - 1; x >= 0; x--) {
                    int i = z * w + x;
                    if (x < w - 1) {
                        dist[i] = Math.min(dist[i], dist[i + 1] + 1);
                    }
                    if (z < h - 1) {
                        dist[i] = Math.min(dist[i], dist[i + w] + 1);
                    }
                }
            }
            return dist;
        }

        private boolean narrow(boolean[] wet, int x, int z) {
            return (dryWithin(wet, x, z, -1, 0) && dryWithin(wet, x, z, 1, 0))
                    || (dryWithin(wet, x, z, 0, -1) && dryWithin(wet, x, z, 0, 1));
        }

        private boolean dryWithin(boolean[] wet, int x, int z, int dx, int dz) {
            for (int d = 1; d <= 6; d++) {
                int nx = x + d * dx, nz = z + d * dz;
                if (nx < 0 || nx >= w || nz < 0 || nz >= h) {
                    return false;
                }
                if (!wet[nz * w + nx]) {
                    return true;
                }
            }
            return false;
        }

        private float[] blur(float[] source, int radius) {
            float[] horizontal = new float[source.length], result = new float[source.length];
            for (int z = 0; z < h; z++) {
                float sum = 0;
                int offset = z * w;
                for (int x = 0; x < Math.min(w, radius + 1); x++) {
                    sum += source[offset + x];
                }
                for (int x = 0; x < w; x++) {
                    horizontal[offset + x] = sum / (Math.min(w - 1, x + radius) - Math.max(0, x - radius) + 1);
                    if (x - radius >= 0) {
                        sum -= source[offset + x - radius];
                    }
                    if (x + radius + 1 < w) {
                        sum += source[offset + x + radius + 1];
                    }
                }
            }
            for (int x = 0; x < w; x++) {
                float sum = 0;
                for (int z = 0; z < Math.min(h, radius + 1); z++) {
                    sum += horizontal[z * w + x];
                }
                for (int z = 0; z < h; z++) {
                    result[z * w + x] = sum / (Math.min(h - 1, z + radius) - Math.max(0, z - radius) + 1);
                    if (z - radius >= 0) {
                        sum -= horizontal[(z - radius) * w + x];
                    }
                    if (z + radius + 1 < h) {
                        sum += horizontal[(z + radius + 1) * w + x];
                    }
                }
            }
            return result;
        }

        Cover coverAt(double x, double z) {
            int nx = (int) clamp(Math.round(x), 0, w - 1), nz = (int) clamp(Math.round(z), 0, h - 1);
            return Cover.CODES[codes[nz * w + nx]];
        }

        double elevationAt(double x, double z) {
            x = clamp(x, 0, w - 1);
            z = clamp(z, 0, h - 1);
            int x0 = (int) x, z0 = (int) z, x1 = Math.min(x0 + 1, w - 1), z1 = Math.min(z0 + 1, h - 1);
            double tx = x - x0, tz = z - z0;
            double north = elevation[z0 * w + x0] * (1 - tx) + elevation[z0 * w + x1] * tx;
            double south = elevation[z1 * w + x0] * (1 - tx) + elevation[z1 * w + x1] * tx;
            return north * (1 - tz) + south * tz;
        }
    }
}
