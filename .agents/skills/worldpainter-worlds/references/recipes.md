# WorldPainter recipe runner

Use WorldPainter 2.27.1 and a Java 21 JDK. The runner uses the release's own JARs. [Download WorldPainter](https://www.worldpainter.net/)

On Windows, run the included PowerShell wrapper. If the installed skill has a local runtime path, the wrapper picks it up automatically. On a fresh checkout, set `WORLDPAINTER_HOME` or pass `-Runtime` with the directory that contains `lib/WPCore.jar`. Output paths must have an existing parent and point to a new directory.

```powershell
$skill = '.agents/skills/worldpainter-worlds'
& "$skill/scripts/run.ps1" preview "$skill/assets/emberreach.json" './emberreach-preview'
& "$skill/scripts/run.ps1" generate "$skill/assets/emberreach.json" './emberreach-world' -Heap 3g
& "$skill/scripts/run.ps1" export './emberreach-world/Emberreach.world' './emberreach-save'
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
| `shape` | `"island"` (default) tapers to ocean and exports with WorldPainter's endless water border. `"mainland"` paints to the edge, where Minecraft continues with vanilla generation and may show seams. |
| `seaLevel` | 0–128, default 62. Land below this level floods. |
| `baseHeight` | Starting land elevation above sea level, at most 240. Default is sea level plus 22. |
| `relief` | 0–80 blocks of background relief, default 27. |
| `snowLine` | Height above sea level up to 320, default 174. Nearby snow patches vary with local noise. Snow is suppressed in arid palettes. |
| `caves` | 0–15, default 5. Intensity of WorldPainter's caves layer in eligible land. WorldPainter also adds its usual underground resources. |
| `defaultRegion` | `meadow` (default), `forest`, `taiga`, `jungle`, `swamp`, `desert`, `badlands`, or `alpine`. |
| `spawn` | Optional `{"x":780,"z":720}`. Needs a dry, gently sloped 7 × 7 area with snow clearance. Automatic placement searches near the centre. |
| `landforms` | Up to 32 objects with `kind`, `x`, `z`, `radiusX`, `radiusZ`, `height`, and optional `angle` in degrees. Kinds are `hill`, `ridge`, `mesa`, and `basin`. Height is 1–240 and adds relief, or subtracts it for a basin. Radii are at least 8 and at most the longer side. Overlapping landforms add together. |
| `regions` | Up to 32 objects with `palette`, `x`, `z`, and `radius`. Uses the same palettes and radius limits. Overlapping regions favor the nearest normalized centre, with ragged edges against the default palette. |
| `waterways` | Up to 16 objects with `points`, `width` (4–256), `depth` (2–40), and optional `level` (sea level by default, up to 200). Each waterway has a flat water surface and tapered banks. Route a sea-level river through a low valley to the coast. Use an elevated level only for an enclosed body of water. |
| `trails` | Up to 16 objects with `points` and `width` (2–12). Paints dirt paths where dry and gentle. Trails can break across steep slopes, water, or snow. |
| `waypoints` | Up to 24 objects with a unique `name` of up to 40 characters, `x`, and `z`. The preview marks these points and the manifest reports coordinates. |

Each route's `points` is an array of 2–32 `{"x": …, "z": …}` objects. Routes interpolate smoothly between distinct adjacent points. Heights are clamped to −59 through 315. Prefer broad ridges and passes to overlapping tall peaks that clip at the build limit.

## Runtime and outputs

Preview runs against the same terrain rules at up to 768 pixels on the long side. It skips the Minecraft exporter. Build adds a native project, and generate adds the full export. Coverage in the manifest counts sampled columns at the reported stride. Build and generate sample every column. Counts describe the painted layers before tree placement, cave carving, and other Minecraft export passes.

Minecraft export time and disk usage grow with area. The 8,192 × 8,192 square limit represents 262,144 chunks. For large builds, use a machine with spare RAM and disk and select a JVM heap with `-Heap`. If Minecraft export competes with other apps, use `-ExportThreads` to limit it. The default lets WorldPainter choose. Logs go to stderr and the success manifest path goes to stdout. A folder without `result.json` may be a partial run. Keep any complete `.world` for recovery and use a fresh folder for the retry.

Copy the exported world folder into Minecraft Java's saves directory to play it. Open the `.world` file in WorldPainter to edit it. A recipe re-run replaces procedural content by building a new project. To preserve manual edits, export the edited project directly. Only load trusted `.world` files, which use WorldPainter's Java object serialization.
