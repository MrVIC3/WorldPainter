---
name: worldpainter-worlds
description: Generate a detailed Minecraft Java overworld from a brief using WorldPainter. Compose terrain, biome regions, waterways, and trails with a seed, inspect a shaded preview, and deliver an editable .world file and a Minecraft save. Use for WorldPainter world generation or export, including large maps.
---

# WorldPainter worlds

Read [the recipe reference](references/recipes.md) for the fields and run commands. Start from [Emberreach](assets/emberreach.json) for a single island or [Skerrychain](assets/skerrychain.json) for a designed archipelago with a custom terrain mask. The local runner paints the columns and delegates caves, resources, trees, custom layers, and Minecraft export to WorldPainter.

1. Lay out the destinations, travel routes, and terrain around the user's brief. Use `landmasses` and `biomeZones` to sketch custom outlines, and `ridges`, `waterways`, and `trails` to connect features. Keep crossings and coast approaches in mind. Add a 16-bit heightmap when precise elevation is important. Use masks for local materials, vegetation, or trusted native custom layers. For an open-ended brief, start at 1536 × 1536 targeting Minecraft Java 1.21.11.
2. Run `preview` at the intended dimensions. Open `overview.png` to check the silhouette, routes, landmark placement, and spawn. Revise the recipe here while iteration is cheap. Large previews can miss narrow paths and straits.
3. For a complex world, run `build` once, then `sample` around representative points such as spawn, a crossing, and a landmark. Open each `detail.png` and check the exported blocks and water at full block resolution. Open the small Creative saves in Minecraft when an in-game check is needed. Once those checks pass, run `export` on the same `.world`. For a simple world use `generate` to build and export in one run. Use a fresh directory for each run and check its `result.json`.
4. Deliver the preview, recipe with any input assets, editable project, and requested save. A zipped save should contain one world folder with `level.dat` and its version-specific region directory. Give the user the spawn and a few relevant waypoints.

The generator paints an Overworld. Waypoints are map labels and coordinates. Towns, bridges, dungeons, custom structures, and custom Nether or End dimensions require a separate build or editing pass when requested.
