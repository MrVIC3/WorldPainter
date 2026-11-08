---
name: worldpainter-worlds
description: Generate a detailed Minecraft Java overworld from a brief using WorldPainter. Compose terrain, biome regions, waterways, and trails with a seed, inspect a shaded preview, and deliver an editable .world file and a Minecraft save. Use for WorldPainter world generation or export, including large maps.
---

# WorldPainter worlds

Read [the recipe reference](references/recipes.md) for the fields and run commands. Use [Emberreach](assets/emberreach.json) as an example of a composed island. Write a small recipe tailored to the brief. The local runner handles all block columns and delegates caves, resources, trees, and Minecraft export to WorldPainter.

1. Translate the brief into geography that is distinct at map scale. Choose two to four major landforms, a memorable route from spawn, and palettes that correspond to the elevations and climate. Use named waypoints to explain what the player can visit. Place river endpoints, trail junctions, and waypoints in actual block coordinates. Choose the requested map size and Minecraft target. For an open-ended brief, start with a 1536 × 1536 island targeting Minecraft Java 1.21.11.
2. Run `preview` at the intended final dimensions into a new scratch directory. Open `overview.png` and check the coastline, crossings, landmark placement, and spawn. Use the reported waypoint coordinates and terrain coverage to revise the recipe. Large previews are subsampled and can miss narrow trails. Use a larger map only when the brief benefits from the extra travel distance.
3. Use `generate` for both the editable WorldPainter project and a Java save. Use `build` for a WorldPainter-only deliverable and `export` to export an existing trusted `.world` into a new folder. The runner prints the path to `result.json` on success. Check that manifest and its referenced artifacts. If export fails after saving the project, use `export` on the completed `.world` with a fresh output directory.
4. Deliver the preview, recipe, editable project, and requested save. A zipped save should contain one world folder with `level.dat` and `region/` inside it. Give the user the spawn and a few relevant waypoints.

The generator paints an Overworld. Waypoints are map labels and coordinates. Towns, bridges, dungeons, custom structures, and custom Nether or End dimensions require a separate build or editing pass when requested.
