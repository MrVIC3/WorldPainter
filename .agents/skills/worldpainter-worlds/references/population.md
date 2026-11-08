# Populate an existing world

Use this pass to add things players can find, use, or visit. Start with a few connected destinations. Place a village or farm near safe ground, a recognizable landmark on a travel route, and loot or a threat farther out. Put vanilla structures where they fit the terrain and use small custom bridges or camps when helpful. Vanilla mobs, loot, and blocks work in Minecraft Java with the normal client.

```powershell
& "$skill/scripts/run.ps1" populate './terrain/My World.world' './settled' -Content './content.json'
& "$skill/scripts/run.ps1" sample './settled/My World Settled.world' './village-check' -AtX 170 -AtZ 240
& "$skill/scripts/run.ps1" export './settled/My World Settled.world' './minecraft-export'
```

Use PowerShell 7 and the Java 21 JDK from the recipe workflow. The command saves a new editable project with native object layers, the content plan, a site map, and `result.json`. Keep the terrain project as the source for revisions. Use a fresh output directory and a trusted input project. The pass supports Minecraft Java 1.21.11 and 26.1.

## Vanilla content

A `vanilla` site loads the actual structure template from the matching Minecraft client JAR. The runner looks in the standard launcher directory. Pass `-MinecraftJar` for another install. Use a client JAR whose `version.json` matches the target. The tool reads data locally and packages the placed objects into the project.

Use the template path after `data/minecraft/structure/` without the `.nbt` suffix. For discovery, list the JAR entries and filter to the relevant family.

```powershell
jar tf $minecraftJar | rg '^data/minecraft/structures?/(village/plains|shipwreck|ruined_portal|pillager_outpost)/'
```

| What to place | Example template |
| --- | --- |
| Village gathering point with bell | `village/plains/town_centers/plains_meeting_point_1` |
| Vanilla home, shop, or farm | `village/plains/houses/plains_small_house_1`, `village/plains/houses/plains_cartographer_1`, `village/plains/houses/plains_large_farm_1` |
| Pillager outpost tower | `pillager_outpost/watchtower` |
| Ruined portal | `ruined_portal/portal_1` |
| Shipwreck with loot | `shipwreck/rightsideup_backhalf` with `placement: "seafloor"` |
| Igloo surface shelter | `igloo/top` |

Village templates with villager or iron golem connectors get those inhabitants. Outpost towers get a small pillager guard group. Set `inhabitants: false` for an unoccupied version. Loot tables in the templates are retained, including shipwreck chest markers. Vanilla structure families assembled from multiple pieces can be laid out as multiple sites. Minecraft's `/locate`, naturally renewed outpost patrols, and treasure map targets depend on the game's world-generation metadata. Use the site manifest for placed coordinates.

## Content plan

```json
{
  "version": 1,
  "name": "My World Settled",
  "sites": [
    {"name":"Harbor Bell","kind":"vanilla","template":"village/plains/town_centers/plains_meeting_point_1","x":170,"z":240},
    {"name":"Harbor Cartographer","kind":"vanilla","template":"village/plains/houses/plains_cartographer_1","x":202,"z":244},
    {"name":"Reef Wreck","kind":"vanilla","template":"shipwreck/rightsideup_backhalf","placement":"seafloor","x":230,"z":330},
    {"name":"Trail Camp","kind":"camp","style":"spruce","x":125,"z":280}
  ],
  "paths": [{"width":3,"points":[{"x":170,"z":240},{"x":190,"z":244},{"x":202,"z":244}]}],
  "scenery": [{"kind":"boulder","x":165,"z":270,"radius":45,"count":4,"spacing":10}]
}
```

- `sites` takes up to 48 placements. Give each a unique name, kind, and actual Minecraft X/Z anchor. Optional `facing` takes `south` (default), `west`, `north`, or `east`, rotating the template from its stored orientation. Check entrances in a sample. Give each site room for its footprint and three-block margin. A dry pad is flattened and blended into its surroundings. `maxRelief` limits the initial elevation range across the footprint, defaults to 8 blocks, and accepts 0–16. Use a submerged footprint for a seafloor site.
- Small custom kits include `camp`, `farm`, `hamlet`, `watchtower`, `ruin`, `dungeon`, `bridge`, and `dock`. They use vanilla blocks and entities. Options include `style` (`oak`, `spruce`, `sandstone`), `loot` (`none`, `supplies`, `relic`), and `inhabitants`. The dungeon includes a ladder entrance and can contain a skeleton. Crossings face from the water toward the dry approach for a dock. Use an odd `span` from 9 to 65, keep the banks close in height, and test the crossing with adjacent tiles.
- `custom` places a trusted Minecraft structure-block `.nbt` file under `inputs/` beside the content plan. Set `file` to its relative path. The file is bundled with the plan. Imported templates can be at most 64 blocks along each axis.
- `paths` accepts up to 32 lines with `width` 3 or 5 and 2–32 X/Z points each. They paint the dry approach between sites and leave structures intact. Check river gaps and steep slopes in the exported sample.
- `scenery` accepts up to 12 rules for `boulder`, `fallen_log`, or `lantern_post`. Set X/Z, `radius` (8–1024), `count` (1–100 per rule, 400 per plan), and optional `spacing` (5–128, default 9). The seeded pass uses suitable dry locations and reports how many fit. Use a few where they make paths or clearing edges more interesting.

Sample the actual Minecraft export around important sites. Increase `-TileRadius` when a site crosses a tile edge. Check access, loot, inhabitants, and whether the terrain meets each entrance before delivering the full save.
