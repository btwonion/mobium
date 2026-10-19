# Benchmarks

How Mobium is measured, and the results. Every gate in `PLAN.md` (A–C) is decided with the numbers on this page.

## Tools

- **`benchmark/mobium-bench`** — a datapack that builds the scenes. It works without Mobium; regenerate it with
  `python3 benchmark/generate.py` after changing the generator, never edit the `.mcfunction` files by hand.
- **`/mobium bench <scene> <seconds> [setup]`** — Mobium's client command. It hides the HUD, warms up for 5 s, records
  every frame for `<seconds>`, then prints a summary in chat and writes two files to `<game dir>/mobium-bench/`:
  - `<scene>-<setup>-<yyyyMMdd-HHmmss>.csv`: one row per frame (frame time, every timer below, entity and block
    entity counts);
  - `summary.csv`: one row per run, appended (averages, Minecraft and Mobium version, loader, backend, GPU).
  `setup` is detected when omitted: `sodium` or `vanilla`, `+iris` if a shader pack is active, `+emf` if Entity Model
  Features is installed, `+fa` if a resource pack with "fresh" in its id is enabled, `+chestcache` while the M3 chest
  prototype is on. `/mobium stop` aborts a run.
- **F3 entry `mobium:timings`** — the same timers averaged over the last 64 frames. Enable it in the debug options
  screen (the timers only run while it is shown or a benchmark records).

The game directory of the dev clients is `versions/26.3-fabric/run/` (Fabric) and `run/` (NeoForge).

### What the numbers mean

| Column | Meaning |
| --- | --- |
| `frame_ms` | Time between two game-loop iterations (`Minecraft.runTick`), i.e. the full frame including GPU waits |
| avg FPS | frames / total recorded time |
| 1% low | 1000 / mean frame time of the slowest 1% of frames (at least one frame) |
| p95 | 95th percentile frame time, nearest rank |
| `extract_entities` / `extract_block_entities` | `LevelExtractor.extractVisibleEntities` / `extractVisibleBlockEntities`: culling plus building render states (Sodium's block entity loop runs inside the latter) |
| `submit_entities` / `submit_block_entities` | `LevelRenderer.submitEntities` / `submitBlockEntities`: renderers turning states into submit records (includes the `setupAnim` that `LivingEntityRenderer` runs for its layers) |
| `pose_*` | `Model.setupAnim` inside `ModelFeatureRenderer.prepareModel` (EMF / Fresh Animations run here), split by who submitted the model |
| `tessellate_*` | `Model.renderToBuffer` inside `prepareModel`: walking the part tree and writing every vertex on the CPU |
| `prepare` | The level's whole prepare stage: pose + tessellate above, plus items, block models, text, sorting and the upload of all vertices |
| `draw` | CPU time issuing the level's feature draws (every phase, including the three OIT stages). GPU time is not measured; it shows up in `frame_ms` |

All timers are CPU wall-clock time on the render thread and only count the main level pass (not the hand, the GUI or
Iris's shadow pass). See `docs/vanilla-notes.md` §0 for the stages.

## Protocol

### World

1. Create a world: **Creative**, world type **Superflat** with the default *Classic* preset, seed **`mobium`**,
   commands on, structures on (the map wall needs villages to exist somewhere; the scene origin at x −3840, z −2816 is
   about 600 blocks from the nearest one).
2. Copy `benchmark/mobium-bench` into the world's `datapacks/` folder (or add it through *Data Packs* when creating
   the world) and run `/reload` if the world is already open.

### Settings (keep them identical for every run and write down anything that differs)

| Setting | Value |
| --- | --- |
| Window | Fullscreen, 1920×1080 |
| Render distance / simulation distance | 12 / 12 |
| FOV | 70 |
| VSync / max framerate | Off / unlimited |
| Entity distance | 100% |
| Improved transparency (OIT) | Off, unless a run says otherwise |
| Graphics preset | Note the preset in the results |
| F3 | Closed (the benchmark hides the HUD itself) |
| Inactivity FPS limit | Any; `/mobium bench` keeps vanilla's 60-second idle limit (30 FPS, later 10 FPS) from engaging while it records |
| Other programs | Closed; laptop on mains power |

### Setups

| Setup | Mods and resources |
| --- | --- |
| A · Sodium | Sodium, Mobium (Fabric API, Fabric Language Kotlin) |
| B · + Iris | A + Iris with the shader pack named in `PLAN.md` §11 (version noted in the results) |
| C · + EMF/ETF | A + Entity Model Features, Entity Texture Features and the Fresh Animations resource pack |
| D · All | A + B + C |

Use the mod versions from `versions/26.3-fabric/gradle.properties`. NeoForge is checked for parity, not benchmarked.

### Scenes

| Scene | Contents | Function |
| --- | --- | --- |
| `mob_pen_static` | 500 mixed mobs (cows, villagers, zombies with helmets) with NoAI | `/function mobium-bench:mob_pen_static` |
| `mob_pen_live` | The same mobs with AI in 1×1 barrier pens, so walk animations run | `/function mobium-bench:mob_pen_live` |
| `storage_room` | 1,000 closed chests and 300 signs with text, every fourth with glowing coloured text | `/function mobium-bench:storage_room` |
| `map_wall` | 400 item frames with real filled maps (exploration maps), 120 shelves with three items each | `/function mobium-bench:map_wall` |
| `display_build` | 400 block displays (every other one turned 45°) and 100 item displays | `/function mobium-bench:display_build` |
| `control` | Empty superflat, nothing to accelerate | `/function mobium-bench:control` |

Every function switches you to spectator mode, teleports you to the scene's camera position and rotation, and builds
the scene two seconds later in the same place (each scene replaces the previous one). Wait for "Scene … is ready".

### A run

1. `/function mobium-bench:<scene>`, wait for "Scene <scene> is ready", then wait another 10 seconds.
2. `/mobium bench <scene> 60`. Do not touch the mouse or keyboard until the summary appears (65 s).
3. Repeat step 2 two more times; the result for a scene and setup is the **median of the three runs**.
4. Do this for all six scenes in all four setups.
5. Copy `<game dir>/mobium-bench/` into `docs/benchmarks/results/<yyyy-mm-dd>-<machine>/` and fill in the tables below.

## Results

Machine: — (CPU, GPU, driver, OS, RAM) · Date: — · Mobium: — · Graphics preset: —

Median of three 60-second runs, Mobium modules off (M2 baseline).

| Scene | Setup | Avg FPS | 1% low | Avg ms | p95 ms | Extract | Submit | Pose | Tessellate | Prepare | Draw |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mob_pen_static | A | | | | | | | | | | |
| mob_pen_static | B | | | | | | | | | | |
| mob_pen_static | C | | | | | | | | | | |
| mob_pen_static | D | | | | | | | | | | |
| mob_pen_live | A | | | | | | | | | | |
| mob_pen_live | B | | | | | | | | | | |
| mob_pen_live | C | | | | | | | | | | |
| mob_pen_live | D | | | | | | | | | | |
| storage_room | A | | | | | | | | | | |
| storage_room | B | | | | | | | | | | |
| storage_room | C | | | | | | | | | | |
| storage_room | D | | | | | | | | | | |
| map_wall | A | | | | | | | | | | |
| map_wall | B | | | | | | | | | | |
| map_wall | C | | | | | | | | | | |
| map_wall | D | | | | | | | | | | |
| display_build | A | | | | | | | | | | |
| display_build | B | | | | | | | | | | |
| display_build | C | | | | | | | | | | |
| display_build | D | | | | | | | | | | |
| control | A | | | | | | | | | | |
| control | B | | | | | | | | | | |
| control | C | | | | | | | | | | |
| control | D | | | | | | | | | | |

### Cost split per scene

To be written from the baseline runs: for each scene, how the frame divides into pose versus tessellate + draw, and
what that means for Modules 1–3 (`PLAN.md` M2 is done when this section is filled in).

### First look (not a baseline)

From the Fabric client gametest on the development laptop (Intel Arc 130V/140V iGPU, Mesa 26.2.4, OpenGL, 854×480
gametest window, Sodium + EMF/ETF without Fresh Animations, no shader pack). Max framerate unlimited and VSync off:
with vanilla's default 120 FPS cap these scenes sit at the cap (8.33 ms) and differences disappear.

`storage_room`, 5-second runs, M3 chest prototype off and on (the gametest also checks that both pictures are
identical):

| Setup | Frame | Extract BE | Submit BE | Tessellate BE | Prepare | Draw |
| --- | --- | --- | --- | --- | --- | --- |
| Chest prototype off | 9.38 ms (107 FPS) | 0.45 ms | 0.79 ms | 0.81 ms | 4.81 ms | 0.56 ms |
| Chest prototype on | 5.55 ms (180 FPS) | 0.34 ms | 0.47 ms | 0 | 3.11 ms | 0.62 ms |

With the prototype on, what is left of `prepare` and `submit` is almost entirely the 300 signs (their text is
translucent, so Module 1 leaves it on the vanilla path).

`mob_pen_live`, 5-second run (measured before the frame-rate cap was lifted, at 110 FPS, just under it):

| Frame | Extract E | Submit E | Pose E | Tessellate E | Prepare | Draw |
| --- | --- | --- | --- | --- | --- | --- |
| 9.09 ms (110 FPS) | 1.98 ms | 0.67 ms | 0.16 ms | 2.28 ms | 3.82 ms | 0.04 ms |

Even without Fresh Animations, writing vertices for 500 vanilla mobs costs about 14 times as much CPU time as posing
them; treat this as a hint for what to look for in the real baselines, not as a result.
