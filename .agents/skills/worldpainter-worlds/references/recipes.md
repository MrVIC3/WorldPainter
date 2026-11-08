# WorldPainter recipe runner

Use WorldPainter 2.27.1 and a Java 21 JDK. The runner uses the release's own JARs. [Download WorldPainter](https://www.worldpainter.net/)

On Windows, run the included PowerShell wrapper. If the installed skill has a local runtime path, the wrapper picks it up automatically. On a fresh checkout, set `WORLDPAINTER_HOME` or pass `-Runtime` with the directory that contains `lib/WPCore.jar`. Output paths must have an existing parent and point to a new directory.

```powershell
$skill = '.agents/skills/worldpainter-worlds'
& "$skill/scripts/run.ps1" preview "$skill/assets/emberreach.json" './emberreach-preview'
& "$skill/scripts/run.ps1" generate "$skill/assets/emberreach.json" './emberreach-world' -Heap 3g
& "$skill/scripts/run.ps1" export './emberreach-world/Emberreach.world' './emberreach-save'
& "$skill/scripts/run.ps1" build "$skill/assets/skerrychain.json" './skerrychain'
& "$skill/scripts/run.ps1" sample './skerrychain/Skerrychain.world' './caldera-check' -AtX 1240 -AtZ 1060
```

The equivalent command on macOS or Linux with a WorldPainter distribution containing the same JARs is:

```bash
skill=.agents/skills/worldpainter-worlds
java -Xmx3g -Djava.awt.headless=true "-Dlogback.configurationFile=$skill/scripts/logback.xml" \
  -cp "$WORLDPAINTER_HOME/lib/*" "$skill/scripts/WorldPainterWorlds.java" \
  generate "$skill/assets/emberreach.json" ./emberreach-world
```

## Recipe fields

Only `version`, `name`, `seed`, `width` and `length` are required at the top level. Coordinates use actual Minecraft X and Z blocks from 0 through the corresponding dimension minus one. The top of the preview is north, or low Z. Higher Y is up.

| Field | Values and behavior |
| --- | --- |
| `version` | `1`. |
| `name` | 1–64 letters, digits, spaces, hyphens or underscores. Start with a letter or digit and avoid Windows device names. |
| `seed` | Signed 64-bit JSON integer. Preserve it as an integer when editing. |
| `width`, `length` | Multiples of 128 from 128 to 16384. Total area up to 67,108,864 columns. |
| `minecraftVersion` | `"1.21.11"` (default) or `"26.1"` for Minecraft Java Edition. |
| `shape` | `"island"` tapers to ocean and exports with WorldPainter's endless water border. `"archipelago"` uses custom outlines and the same border. `"mainland"` paints to the edge, where Minecraft continues with vanilla generation and may show seams. Providing `landmasses` defaults to archipelago. Otherwise the default is island. |
| `seaLevel` | 0–128, default 62. Land below this level floods. |
| `baseHeight` | Starting land elevation above sea level, at most 240. Default is sea level plus 22. |
| `relief` | 0–80 blocks of background relief, default 27. |
| `snowLine` | Height above sea level up to 320, default 174. Nearby snow patches vary with local noise. Snow is suppressed in arid palettes. |
| `caves` | 0–15, default 5. Intensity of WorldPainter's caves layer in eligible land. WorldPainter also adds its usual underground resources. |
| `defaultRegion` | `meadow` (default), `forest`, `taiga`, `jungle`, `swamp`, `desert`, `badlands`, or `alpine`. |
| `spawn` | Optional `{"x":780,"z":720}`. Needs a dry, gently sloped 7 × 7 area with snow clearance. Automatic placement searches near the centre. |
| `landforms` | Up to 32 objects with `kind`, `x`, `z`, `radiusX`, `radiusZ`, `height`, and optional `angle` in degrees. Kinds are `hill`, `ridge`, `mesa`, and `basin`. Height is 1–240 and adds relief, or subtracts it for a basin. Radii are at least 8 and at most the longer side. Overlapping landforms add together. |
| `landmasses` | Up to 48 polygons with `points` and optional `coastWidth` (8–256 blocks, default 48). Use one polygon per island. Smaller widths help tiny islets rise above the water. Coastlines are rounded and perturbed, so leave room between islands and verify narrow straits. |
| `ridges` | Up to 32 lines with `points`, `width` (16–768 blocks), and `height` (1–240 blocks added). Good for long ranges with branches. Overlapping relief is additive. |
| `regions` | Up to 32 objects with `palette`, `x`, `z`, and `radius`. Uses the same palettes and radius limits. Overlapping regions favor the nearest normalized centre, with ragged edges against the default palette. |
| `biomeZones` | Up to 32 polygons with `points` and `palette`. Zones override circular regions. Later overlapping zones take precedence. Coasts, rivers and snow still apply their terrain rules. |
| `waterways` | Up to 16 objects with `points`, `width` (4–256), `depth` (2–40), and optional `level` (sea level by default, up to 200). Each waterway has a flat water surface and tapered banks. Route a sea-level river through a low valley to the coast. Use an elevated level only for an enclosed body of water. |
| `trails` | Up to 16 objects with `points` and `width` (2–12). Paints dirt paths where dry and gentle. Trails can break across steep slopes, water, or snow. |
| `waypoints` | Up to 24 objects with a unique `name` of up to 40 characters, `x`, and `z`. The preview marks these points and the manifest reports coordinates. |

Each route's `points` is an array of 2–32 `{"x": …, "z": …}` objects. Routes interpolate smoothly between distinct adjacent points. Heights are clamped to −59 through 315. Prefer broad ridges and passes to overlapping tall peaks that clip at the build limit.

Polygon `points` uses the same coordinate objects, with 3–96 distinct vertices in order around the perimeter. The runner closes the outline. Self-intersecting polygons are rejected.

## Optional raster inputs

Place every input in an `inputs/` folder beside the recipe. Paths use forward slashes and a single filename, such as `inputs/elevation.png`. PNG dimensions must match the map, with one pixel per block. Image X maps to Minecraft X. Image row maps to Minecraft Z, increasing south. The runner bundles the inputs with its copy of the recipe.

- `heightmap` accepts `{"file":"inputs/elevation.png","minY":30,"maxY":240}`. Use a single-channel 16-bit grayscale PNG. Black maps to `minY` and white to `maxY`. This replaces the procedural base and shoreline. Landforms, ridge lines, and waterways can still shape it. Choose either a heightmap or landmass polygons. For an island heightmap, put water around the image edges to meet the endless water border.
- `masks` accepts up to 12 single-channel 8-bit grayscale PNGs. Each mask requires `file` and one of `terrain`, `layer`, or `layerFile`. Black leaves the underlying paint in place. With multiple masks on the same target, the last nonzero value wins.
  - `terrain` takes a built-in WorldPainter terrain name such as `BLACKSTONE` or `RED_SAND`. Any nonzero pixel paints that terrain.
  - `layer` takes `forest`, `pine`, `jungle`, `swamp`, `frost`, or `caves`. Positive values map to intensities 1–15 for density layers or turn on a bit layer such as frost.
  - `layerFile` loads a native WorldPainter `.layer` from `inputs/`, such as a custom object or ground cover layer. It must be a per-block bit or nibble layer. Only load trusted files, which use Java object serialization. Inspect object placement in a sample export.

## Local export checks

Run `sample` on a trusted built `.world`, at the X and Z you want to inspect. The default exports the WorldPainter tile containing that point, covering 128 × 128 blocks. `-TileRadius 1` includes its neighbours and `-TileRadius 2` goes one tile further. Unpainted neighbouring tiles are omitted. The raw Java command takes `sample <input.world> <new-output-dir> <x> <z> <tile-radius>`.

The sample saves a Creative world at the requested coordinates. Its `detail.png` shows the highest actual exported block at each column at one pixel per block, including native vegetation and objects visible from above. `result.json` reports the image origin in Minecraft coordinates, tile and chunk counts, and common visible blocks. Check caves, canopy clearance, and sightlines in the sample save when those details matter. Keep the editable `.world` as the source for the final export.

## Runtime and outputs

Preview runs against the same terrain rules at up to 768 pixels on the long side. It skips the Minecraft exporter. Build adds a native project, and generate adds the full export. Coverage in the manifest counts sampled columns at the reported stride. Build and generate sample every column. Counts describe the painted layers before tree placement, cave carving, and other Minecraft export passes.

Minecraft export time and disk usage grow with area. The 8,192 × 8,192 square limit represents 262,144 chunks. For large builds, use a machine with spare RAM and disk and select a JVM heap with `-Heap`. If Minecraft export competes with other apps, use `-ExportThreads` to limit it. The default lets WorldPainter choose. Logs go to stderr and the success manifest path goes to stdout. A folder without `result.json` may be a partial run. Keep any complete `.world` for recovery and use a fresh folder for the retry.

Copy the exported world folder into Minecraft Java's saves directory to play it. Open the `.world` file in WorldPainter to edit it. A recipe re-run replaces procedural content by building a new project. To preserve manual edits, export the edited project directly. Only load trusted `.world` files, which use WorldPainter's Java object serialization.
