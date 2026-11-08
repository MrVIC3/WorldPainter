# WorldPainter

## Generate worlds with Codex

This fork adds a [WorldPainter world-generation skill](.agents/skills/worldpainter-worlds/SKILL.md) and a headless recipe runner. A JSON recipe can describe custom island outlines, biome zones, ridge lines, rivers, and trails. Heightmaps and layer masks provide precise local control. Preview the whole map and inspect small sample saves before exporting an editable project and Minecraft Java Edition save.

Open this repository in Codex and invoke `$worldpainter-worlds`. The [recipe reference](.agents/skills/worldpainter-worlds/references/recipes.md) includes setup and CLI commands. The runner uses the official WorldPainter 2.27.1 distribution and a Java 21 JDK.

WorldPainter is an interactive map generator for Minecraft. It allows you to "paint" landscapes using similar tools as a regular paint program. Sculpt and mould the terrain, paint materials, trees, snow and ice, etc. onto it, and much more.

## Distributing

WorldPainter is open source software, licensed under the GPL version 3. This means that if you distribute it, original or in modified form, you must also distribute it (including your changes) as open source under the GPL version 3 (or later). See the [LICENSE](LICENSE) file for details.

## Building

WorldPainter is a Maven project and easy to build from the command line with Maven, or using your favourite IDE. However there are some missing dependencies which are not in Maven Central which you will have to install locally first. See the [BUILDING](BUILDING.md) file for details.
