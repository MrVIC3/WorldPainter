# Trace an overhead reference

`trace` turns a local overhead map image into a WorldPainter recipe and editable rasters. It analyses the image locally using colour and nearby pixels, with no extra AI or image-generation calls. Use it for coastlines, straits, river footprints, and broad surface patterns in top-down maps. For perspective pictures, take inspiration from the visible features and design the layout using the recipe workflow. A supplied elevation map is the source for exact heights.

## Run and review

Open the user's actual image first. Use the attached file path. Crop UI chrome, borders, legends, or annotations out of the terrain area. Use a fresh output folder.

```powershell
& "$skill/scripts/run.ps1" trace 'reference.png' './trace' -Name 'Island Chain' -Blocks 1536
& "$skill/scripts/run.ps1" preview './trace/recipe.json' './trace-preview'
& "$skill/scripts/run.ps1" build './trace/recipe.json' './trace-world'
```

Open `comparison.png` beside `trace.json`. Compare the traced surfaces with the original. Check island gaps, river mouths, and snow, desert, and grove placement before building. The relief panel shows the height estimate. For a large world, check the whole composition with `preview`, then use `sample` around a representative crossing or landmark before the full Minecraft export.

| Control | Use |
| --- | --- |
| `-Blocks` | Longest map side. Use a multiple of 128 from 128 to 8192. The image keeps its proportions and the shorter side is padded to a full WorldPainter tile. |
| `-Crop 'x,z,width,height'` | Crop in source-image pixels. The default trims only narrow near-black borders. |
| `-WaterBias` | Adjust colour-based water detection from −32 to 32. Negative values accept more blue and cyan shades as water. Positive values require stronger blue contrast. |
| `-WaterMask` | Supply a PNG the same size as the original image. White is water, black is land. It takes precedence over the colour classifier. The crop applies to both images. This handles unusual water colours or targeted corrections without redrawing the whole map. |
| `-ReliefScale` | Scale inferred relief from 0.25 to 1.8. The traced coastline stays in place. |
| `-Name`, `-Seed`, `-MinecraftVersion` | Set the recipe name, detail seed, and Minecraft Java target. |

The tool analyses at up to 2048 pixels on the longest image side. Thin features below the source or target resolution can disappear. The runner uses an estimated elevation and sea-level water. Labels, buildings, and exact tree locations require explicit objects or editing. Strong colours influence surface classes, so unusual palettes benefit from a water mask and review.

## Edit the trace

The generated `inputs/elevation.png` is a 16-bit heightmap. Its recipe lists the numeric elevation range. `inputs/surface.png` is an 8-bit grayscale label map with these raw values:

| Value | Surface | Value | Surface | Value | Surface |
| --- | --- | --- | --- | --- | --- |
| 0 | Water | 5 | Swamp | 10 | Cherry grove |
| 1 | Meadow | 6 | Desert | 11 | Volcanic rock |
| 2 | Forest | 7 | Badlands | 12 | Magma blocks |
| 3 | Taiga | 8 | Rock | 13 | Beach |
| 4 | Jungle | 9 | Snow | 14 | River |

Edit label pixels to tune vegetation and materials. The heightmap controls whether a column floods. For a changed shoreline, rerun with a water mask to keep elevation and labels aligned. Add waypoints, trails, material masks, or trusted custom layers to the generated recipe. Keep the raster files in `inputs/` when handing off the recipe.

With the same WorldPainter distribution on macOS or Linux, the equivalent trace command is:

```bash
java -Xmx1g -Djava.awt.headless=true -cp "$WORLDPAINTER_HOME/lib/*" \
  .agents/skills/worldpainter-worlds/scripts/WorldPainterTrace.java \
  reference.png ./trace 1536 "Island Chain" 314159 1.21.11 - - 0 1.0
```
