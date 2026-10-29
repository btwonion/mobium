# Benchmarks

How Mobium is measured, and what it measured for 0.2.0, on the development laptop and on players' machines (*Players'
results*). Every gate in `PLAN.md` is decided with the numbers on this page. Raw data (every run's CSV, the suite and
gate medians) is in [btwonion/mobium-benchmarks](https://github.com/btwonion/mobium-benchmarks), one folder per round
and players' files in `players/`; *Earlier rounds* at the end lists the rounds, and the round-by-round write-ups up to
0.2.0 are in `git show dd7253f:docs/benchmarks.md`.
Build ids from before 27 Oct 2026 predate a rewrite of this repository's history: `grep ^<id>
../mobium-benchmarks/commit-map.txt` gives the current commit.

## Tools

- **`benchmark/mobium-bench`** — a datapack that builds the scenes. It works without Mobium; regenerate it with
  `python3 benchmark/generate.py` after changing the generator, never edit the `.mcfunction` files by hand.
- **`/mobium bench <scene> <seconds> [setup]`** — Mobium's client command. It hides the HUD, warms up for 5 s, records
  every frame for `<seconds>`, then prints a summary in chat and writes two files to `<game dir>/mobium-bench/`:
  - `<scene>-<setup>-<yyyyMMdd-HHmmss>.csv`: one row per frame (frame time, every timer below, entity and block
    entity counts);
  - `summary.csv`: one row per run, appended (averages, Minecraft and Mobium version, `build`, the commit the jar was
    built from (`git describe`, `-dirty` with local changes), loader, backend, GPU, the monitor and video mode, the
    window size, `ac` or `battery`, the power profile, and `gpu_mhz`, the GPU's actual clock sampled once per second,
    which three come from Linux sysfs and stay empty elsewhere; since 29 Oct 2026 also `os` and `driver`, as in
    `/mobium benchmark` below). A `summary.csv` with older columns is renamed to `summary-before-<timestamp>.csv`. The
    suite and gate files end with a `# build,<commit>` line.
  `setup` is detected when omitted: `sodium` or `vanilla`, `+iris` if a shader pack is active, `+emf` if Entity Model
  Features is installed, `+fa` if a resource pack with "fresh" in its id is enabled. Before it starts, the run warns in chat about anything that breaks the protocol: a window other than
  1920×1080, battery power, a low-power profile, or a setup other than the instance's (below). `/mobium stop` aborts a
  run.
- **`/mobium suite [seconds] [runs] [scenes…]`** — runs a whole setup unattended (defaults: 60 s, 3 runs, all six
  scenes). For each scene it builds the scene through the integrated server (singleplayer only; no cheats needed),
  waits at least 10 s and until section rebuilds, client light updates and server ticks have calmed down, then
  records `runs` runs. It rewrites `mobium-bench/suite-<timestamp>.csv` with the medians after every run (quitting
  halfway keeps what was measured) and prints them at the end. It refuses to start when the instance was made for
  another setup than the active one (see *Prism instances*).
- **`/mobium gatea [seconds] [runs]`** — Gate A: builds `storage_room` and records it with the static cache off and
  on, alternating (defaults: 60 s, 3 runs each), then prints the speed-up against the 1.5× target and writes
  `mobium-bench/gatea-<timestamp>.csv`. Gate A was decided with the chest prototype of the time; the command now
  compares the whole static cache and stays useful as a before/after number.
- **`/mobium gateb [seconds] [runs]`** — Gate B: records `storage_room` with the static cache, the same with the
  cache's draw calls skipped (setup `…+cache+nodraw`; the chests and signs vanish for that run), `control` with the
  cache and `control` without it, in turn (defaults: 60 s, 3 rounds; about 16 minutes). Then it prints Gate B's
  numbers (`bench/GateB.kt`): what the cached objects cost, i.e. `storage_room` without their draws plus the draw
  calls' CPU time, against `control` (target: at most 1.2×); `control` with the cache against `control` without it
  (must not be slower; up to 1.03× counts as noise); and, without a target, the whole `storage_room` frame against
  `control` and what drawing the cached objects costs per frame. It writes `mobium-bench/gateb-<timestamp>.csv` with
  the medians and those numbers, plus the run without draw calls over `control` before their CPU time is added back
  (`gate_b_nodraw_over_control`). Both gate commands switch the cache themselves for the plan, even when it is off
  in the settings (which they do not save).
- **`/mobium gatec [seconds] [runs]`** — Gate C: records `mob_pen_live` with Module 2 off and on, alternating
  (defaults: 60 s, 3 runs each), then prints the entity CPU work (`prepare` + `shadow_prepare`) without over with
  Module 2 (target at least 1.5× in setup D) and the frame time with over without (must not be slower), and writes
  `mobium-bench/gatec-<timestamp>.csv`.
- **`/mobium stages [seconds] [runs] [scene]`** — Module 2's expand pass as a compute dispatch and in the vertex stage
  (`parts/ExpandPass.kt`), alternating, in one scene (defaults: 60 s, 3 runs each, `mob_pen_live`; about 7 minutes),
  then prints the frame time with the vertex stage over compute's and writes `mobium-bench/stages-<timestamp>.csv`.
  Refused where vertex shaders cannot write storage buffers. Like the gates, it switches Module 2 on for the plan.
- **`/mobium benchmark`** — the players' benchmark, with the release jar (README, *Benchmarks wanted*). The jar carries
  `benchmark/mobium-bench` as the built-in data pack "Mobium Benchmark" (`bench/BenchPack.kt`, packaged by the build),
  which is listed under Create New World → Data Packs and never switched on by itself, in new or existing worlds
  (`docs/vanilla-notes.md` §17). In a classic superflat world with that pack (seed `mobium` as in *World* below; another
  seed is warned about and recorded) the command builds `storage_room` and `mob_pen_live` and records each with all of
  Mobium off, on, on and off (the A/B state, so the modules run as the player's settings allow), 30 s per run, about six
  minutes. For the runs it sets render distance 12, FOV 70, entity distance 100 %, no frame cap and VSync off without
  turning the graphics preset to Custom (`bench/BenchOptions.kt`), and puts them and the A/B state back at the end, on
  `/mobium stop` and when the player leaves the world. It prints each scene's FPS without and with Mobium and the gain
  in chat and writes `mobium-bench/benchmark-<timestamp>.csv`: the medians as in the suite files, then
  `<scene>_off_ms`, `_on_ms` and `_speedup` (off over on), the OS, CPU, GPU, driver, backend, display, window size,
  power, mods, shader pack, resource packs, graphics preset, whether the seed is `mobium`, each module's state and the
  build. `# os` is Java's `os.name`, `os.version` and `os.arch`, on Linux after the distribution's `PRETTY_NAME` (the
  host's under Flatpak; `bench/OperatingSystem.kt`); `# driver` is Renderpearl's driver string, `GL_VERSION` on OpenGL
  (`docs/vanilla-notes.md` §19). Both are in files from Mobium after 0.2.0. It
  warns about battery power and low-power profiles but not about the window size. It refuses outside a classic
  superflat overworld, without the pack, and when neither module can run. In dev environments
  `/mobium benchmark <seconds>` shortens the runs (`self_check.py --mode benchmark` uses 5 s).
- **Setup names** get `+cache` while the static cache is active and `+parts` while Module 2 is (`+parts-vertex` when
  its expand pass runs in the vertex stage: without a shader pack where the GPU has it). Both modules are on by
  default, so a plain `/mobium suite` measures with them; switch Mobium off in the settings (or with the A/B key, the
  key right of P: `]` on US, `+` on German keyboards) for vanilla numbers.
- **F3 entry `mobium:timings`** — the same timers averaged over the last 64 frames. Enable it in the debug options
  screen (the timers only run while it is shown or a benchmark records).
- **Prism instances** — `./gradlew :26.3-fabric:benchKit` collects the mods of the four setups and the datapack,
  `./gradlew :26.3-fabric:runBenchWorld` saves a ready benchmark world (both under `versions/26.3-fabric/build/`), and
  `python3 benchmark/prism_instances.py` creates or updates four Prism Launcher instances "Mobium Bench A–D" with the
  mods, the world and the settings below. It also refreshes the datapack inside the instances' worlds, downloads the
  shader pack and Fresh Animations from Modrinth once (pinned versions, checked against Modrinth's SHA-512; skip with
  `--no-download`), installs and selects them in the setups that need them, and adds
  `-Dmobium.bench.expect=<setup>` to each instance's Java arguments, so `/mobium suite` and `/mobium gatea` refuse to
  run with the wrong packs. Re-run the first and last step after changing Mobium; for a new world, run the second
  step and delete `saves/Mobium Bench` in the instances before the last. The script refuses a kit whose Mobium jar
  was not built from the checkout's current commit, and a checkout that is behind its upstream branch (`git pull`
  first; `--allow-behind` measures an older build on purpose), and prints the build it installed. It finds the
  instances by folder (`mobium-bench-a` …) or, after Prism renamed a folder to the instance's name, by name.

The game directory of the dev clients is `versions/26.3-fabric/run/` (Fabric) and `run/` (NeoForge); in a Prism
instance it is the instance's `minecraft/` folder.

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
| `shadow` | Iris's whole shadow pass (`ShadowRenderer.renderShadows`): shadow terrain, entities, block entities and their draws, issued from inside the level render. 0 without a shader pack |
| `shadow_extract` / `shadow_submit` | Iris's own entity and block entity extraction and submit loops (`ShadowRenderer.extractVisibleEntities` / `extractVisibleBlockEntities`, `renderEntities` / `renderBlockEntities`) |
| `shadow_pose` / `shadow_tessellate_entities` / `shadow_tessellate_block_entities` | `setupAnim` and `renderToBuffer` as above, inside the shadow pass's prepare |
| `shadow_prepare` / `shadow_draw` | The shadow pass's prepare stage (pose and tessellate included) and its feature draws |

All timers are CPU wall-clock time on the render thread. The level timers count only the main level pass (not the hand,
the GUI or Iris's shadow pass); the `shadow_*` timers count Iris's shadow pass, and their stages are part of `shadow`.
Rounds before 23 Oct 2026 had no `shadow_*` timers, and their `draw` included the shadow pass's feature draws. See
`docs/vanilla-notes.md` §0 for the stages.

What the shadow pass draws is up to the shader pack. Complementary Reimagined r5.9.3 at its defaults, as B and D run it
(`ENTITY_SHADOW` 1, also in its HIGH to ULTRA profiles), sets `shadowBlockEntities = false` in `shaders.properties`:
entities cast shadows, block entities do not, so `shadow_tessellate_block_entities` reads 0 there (0.000 ms in the
self-check's gallery on 23 Oct, with the whole shadow pass at 2.0–2.8 ms). Its POTATO to MEDIUM profiles
(`ENTITY_SHADOW` -1) leave entities out as well; `ENTITY_SHADOW` 2 shadows both.

## Protocol

### World

The Prism instances already contain it as "Mobium Bench". To make it by hand:

1. Create a world: **Creative**, world type **Superflat** with the default *Classic* preset, seed **`mobium`**,
   commands on, structures on (the map wall needs villages to exist somewhere; the scene origin at x −3840, z −2816 is
   about 600 blocks from the nearest one).
2. Copy `benchmark/mobium-bench` into the world's `datapacks/` folder (or add it through *Data Packs* when creating
   the world) and run `/reload` if the world is already open.

### Settings (keep them identical for every run and write down anything that differs)

The Prism instances start with these settings (`options.txt` is only written when it does not exist yet).

| Setting | Value |
| --- | --- |
| Window | Fullscreen, 1920×1080 (on the development laptop: the external AOC monitor; the built-in panel is 1728×1080) |
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

| Setup | Prism instance | Mods and resources | Detected as |
| --- | --- | --- | --- |
| A · Sodium | Mobium Bench A (Sodium) | Sodium, Mobium (Fabric API, Fabric Language Kotlin) | `sodium` |
| B · + Iris | Mobium Bench B (Sodium + Iris) | A + Iris with Complementary Reimagined r5.9.3 selected | `sodium+iris` |
| C · + EMF/ETF | Mobium Bench C (Sodium + EMF/ETF) | A + Entity Model Features, Entity Texture Features, Fresh Animations 1.11.1 enabled | `sodium+emf+fa` |
| D · All | Mobium Bench D (all) | A + B + C | `sodium+iris+emf+fa` |

`benchmark/prism_instances.py` installs and selects both packs. Complementary Reimagined is the heavy pack of the test
set; the light one is still open (`PLAN.md`, *Open questions*).

The mod versions are the ones in `versions/26.3-fabric/gradle.properties`. NeoForge is checked for parity, not
benchmarked.

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

### Running a round unattended

`python3 benchmark/bench_round.py` runs setups A–D one after another without anyone at the keyboard (about three
hours; `A D` for some setups, `--dry-run` to see the plan, `--quick` for a five-minute smoke test). Before it starts
it checks mains power, an external monitor, that every instance has the bench kit's Mobium jar and that none of them
is running. For each setup it writes the plan (`PLANS` in the script: `/mobium suite` everywhere, `/mobium stages` in
A–D, `/mobium gatec` in A, C and D, and in A also `/mobium stages 60 3 display_build`) to the instance's
`mobium-bench/autorun` and starts the instance with Prism's command line straight into the world. Mobium
(`bench/BenchAutorun.kt`) puts the fullscreen window on the monitor whose name contains `--display` (default `AOC`) at
1920×1080, runs the commands in turn, writes `mobium-bench/autorun-done` (what it did, the window and display it
measured on) and closes the game. Afterwards the script copies the round's files into
`<yyyy-mm-dd>-<machine>/<setup>/` of the data repository (`-<build>` appended when an earlier round of the day is
there), prints the medians, and commits and pushes the round. Nobody may use the computer while it
runs: the game owns the monitor, and anything else running costs frame time.

The data repository is [btwonion/mobium-benchmarks](https://github.com/btwonion/mobium-benchmarks); the raw-data
links below point into it. Its checkout sits next to this one (`../mobium-benchmarks`, or `--data`); the script
clones it when missing and pulls it before a round. If the commit fails at the end (for example
because the signing key is locked), the script prints the commands to finish by hand.

### Running a setup by hand

1. Start the setup's Prism instance on mains power, put the fullscreen window on a 1920×1080 monitor and open the
   "Mobium Bench" world.
2. `/mobium suite`. Check chat for warnings before you step away, then leave the computer alone until chat shows the
   medians (about 22 minutes). The HUD stays hidden until the very end: the last scene, `control`, is an empty field
   and looks finished, but it records for another three minutes. The result for a scene and setup is the median of
   the three runs.
3. Setup A only: `/mobium gateb` for Gate B (about 16 minutes); `/mobium gatea` gives the cache's speed-up in
   `storage_room` (about 7 minutes).
4. Do this for all four setups, then copy each instance's `minecraft/mobium-bench/` folder into
   `<yyyy-mm-dd>-<machine>/<setup>/` of the data repository (per-run CSVs gzipped), commit and push it there, and
   add the results to this page, or hand the folders to Claude Code.

By hand instead: `/function mobium-bench:<scene>`, wait for "Scene <scene> is ready" plus 10 seconds, then
`/mobium bench <scene> 60` three times without touching anything.

## Results for 0.2.0

The latest round: 27 Oct 2026, build **8d26a26** (0.2.0-beta2), unattended (`bench_round.py`), fullscreen 1920×1080
on the AOC monitor, mains power, uncapped, VSync off; the static cache and Module 2 on, Module 2's expand pass in the
vertex stage in A and C and as a compute dispatch in B and D (shader pack), as `ExpandPass.stage` chooses. 0.2.0
draws the same vertices: 0.2.0-beta3 added the GPU check (comparisons only in the first frames of each shader path)
and 0.2.0 `/mobium benchmark`, so no new round was run. Machine: Lenovo ThinkPad X9-14 Gen 1, Intel Core Ultra 7
258V, Intel Arc 140V iGPU (`xe`, Mesa 26.2, OpenGL 4.6), 32 GB, Fedora 44. Raw data:
[`2026-10-27-thinkpad-x9-8d26a26/`](https://github.com/btwonion/mobium-benchmarks/tree/main/2026-10-27-thinkpad-x9-8d26a26/).

### Module 2 off and on, same session

`/mobium gatec` (Module 2 off and on in turn, medians of three 60-second runs; *CPU work* is `prepare` +
`shadow_prepare`):

| `mob_pen_live` | without Module 2: frame / CPU work | with: frame / CPU work | frame | CPU work |
| --- | --- | --- | --- | --- |
| A (vertex stage) | 3.55 / 2.12 ms (282 FPS) | 2.98 / 1.12 ms (335 FPS) | 1.19× faster | 1.89× lower |
| C (vertex stage) | 9.94 / 6.02 ms (101 FPS) | 7.31 / 3.85 ms (137 FPS) | 1.36× faster | 1.57× lower |
| D (compute) | 67.44 / 14.91 ms (14.8 FPS) | 56.37 / 6.84 ms (17.7 FPS) | 1.20× faster | 2.18× lower |

`/mobium stages` (compute and the vertex stage in turn, three 60-second runs each; ms per frame per run, the GPU clock
in MHz in brackets, and the ratio of the medians):

| Scene, setup | compute | vertex stage | vertex / compute |
| --- | --- | --- | --- |
| `mob_pen_live`, A | 3.07 (750), 3.10 (745), 3.26 (701) | 2.96 (749), 3.04 (722), 3.10 (702) | 0.980× |
| `display_build`, A | 2.02 (700), 2.04 (700), 2.03 (700) | 1.90 (700), 1.90 (700), 1.85 (742) | 0.934× |
| `mob_pen_live`, C | 7.57 (918), 7.55 (936), 7.53 (823) | 7.37 (880), 7.33 (928), 7.38 (873) | 0.977× |
| `mob_pen_live`, B (shader pack) | 30.95 (1391), 31.15 (1325), 31.77 (1263) | 32.70 (1334), 30.20 (1466), 30.79 (1420) | 0.988× |
| `mob_pen_live`, D (shader pack) | 58.98 (677), 56.27 (803), 52.02 (840) | 52.59 (868), 53.59 (822), 58.54 (681) | 0.952× |

- Without a shader pack the vertex stage is faster in every pair at the same GPU clock (A's mob pens by 0.06–0.16 ms,
  `display_build` by 0.12–0.18 ms, C's mob pens by 0.15–0.20 ms): the 0.2 ms of GPU time per frame of *Module 2's
  fixed cost* below.
- With a shader pack the two are equal: the pairs disagree in sign and each run follows the GPU clock (D: compute
  52.0 ms at 840 MHz, the vertex stage 52.6 and 53.6 ms at 868 and 822 MHz). So `ExpandPass.stage` keeps compute there.

### Suites

Median of three 60-second runs per scene and setup, both modules on, ms per frame; extract, submit, pose and
tessellate add entities and block entities, *Shadow* is Iris's whole shadow pass.

| Scene | Setup | Avg FPS | 1% low | Avg ms | p95 ms | Extract | Submit | Pose | Tessellate | Prepare | Draw | Shadow | Shadow prepare | GPU MHz |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mob_pen_static | A | 321 | 173 | 3.12 | 4.64 | 0.57 | 0.25 | 0.09 | 0.53 | 1.13 | 0.02 | 0.00 | 0.00 | 689–700 |
| mob_pen_live | A | 319 | 170 | 3.14 | 4.62 | 0.63 | 0.27 | 0.10 | 0.57 | 1.15 | 0.03 | 0.00 | 0.00 | 700 |
| storage_room | A | 568 | 263 | 1.76 | 2.33 | 0.15 | 0.00 | 0.00 | 0.00 | 0.01 | 0.06 | 0.00 | 0.00 | 874–902 |
| map_wall | A | 222 | 99 | 4.50 | 5.11 | 0.91 | 0.47 | 0.00 | 0.00 | 2.11 | 0.51 | 0.00 | 0.00 | 1192–1256 |
| display_build | A | 555 | 245 | 1.80 | 2.38 | 0.61 | 0.12 | 0.00 | 0.01 | 0.44 | 0.03 | 0.00 | 0.00 | 750 |
| control | A | 778 | 325 | 1.28 | 1.70 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 920–954 |
| mob_pen_static | B | 33 | 22 | 29.87 | 40.78 | 1.05 | 0.42 | 0.11 | 0.61 | 1.02 | 0.05 | 1.68 | 0.37 | 1490–1563 |
| mob_pen_live | B | 32 | 22 | 31.67 | 41.10 | 0.97 | 0.46 | 0.12 | 0.71 | 1.17 | 0.05 | 1.75 | 0.39 | 1253–1278 |
| storage_room | B | 33 | 28 | 30.10 | 33.98 | 0.27 | 0.00 | 0.00 | 0.00 | 0.02 | 0.22 | 0.44 | 0.01 | 1063–1140 |
| map_wall | B | 19 | 14 | 53.39 | 57.46 | 2.45 | 1.07 | 0.00 | 0.00 | 4.80 | 0.83 | 2.86 | 1.03 | 600 |
| display_build | B | 36 | 22 | 28.11 | 41.87 | 0.98 | 0.31 | 0.00 | 0.01 | 0.28 | 0.09 | 3.32 | 1.52 | 1310–1372 |
| control | B | 36 | 31 | 27.76 | 30.60 | 0.01 | 0.00 | 0.00 | 0.00 | 0.01 | 0.00 | 0.42 | 0.01 | 980–1015 |
| mob_pen_static | C | 145 | 75 | 6.90 | 11.11 | 0.84 | 1.15 | 0.16 | 3.12 | 3.59 | 0.02 | 0.00 | 0.00 | 1065–1304 |
| mob_pen_live | C | 138 | 74 | 7.25 | 11.62 | 0.86 | 1.24 | 0.17 | 3.35 | 3.85 | 0.02 | 0.00 | 0.00 | 969–1023 |
| storage_room | C | 603 | 277 | 1.66 | 2.23 | 0.14 | 0.00 | 0.00 | 0.00 | 0.01 | 0.05 | 0.00 | 0.00 | 910–958 |
| map_wall | C | 232 | 109 | 4.31 | 4.81 | 0.83 | 0.46 | 0.00 | 0.00 | 2.11 | 0.47 | 0.00 | 0.00 | 1144–1204 |
| display_build | C | 534 | 247 | 1.87 | 2.50 | 0.62 | 0.14 | 0.00 | 0.01 | 0.45 | 0.03 | 0.00 | 0.00 | 700 |
| control | C | 814 | 345 | 1.23 | 1.62 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 965–1011 |
| mob_pen_static | D | 16 | 14 | 61.85 | 68.68 | 1.45 | 3.46 | 0.23 | 5.10 | 6.17 | 0.05 | 3.04 | 1.07 | 644–712 |
| mob_pen_live | D | 18 | 14 | 56.92 | 65.43 | 1.16 | 3.20 | 0.23 | 5.19 | 6.30 | 0.05 | 2.99 | 1.10 | 676–701 |
| storage_room | D | 31 | 27 | 31.88 | 35.71 | 0.32 | 0.00 | 0.00 | 0.00 | 0.02 | 0.22 | 0.46 | 0.00 | 1014–1051 |
| map_wall | D | 19 | 14 | 53.02 | 57.23 | 2.40 | 1.07 | 0.00 | 0.00 | 4.79 | 0.85 | 2.86 | 0.99 | 600 |
| display_build | D | 37 | 21 | 27.38 | 41.53 | 0.97 | 0.39 | 0.00 | 0.01 | 0.26 | 0.09 | 3.25 | 1.45 | 1294–1438 |
| control | D | 36 | 31 | 27.57 | 30.71 | 0.01 | 0.00 | 0.00 | 0.00 | 0.01 | 0.00 | 0.43 | 0.00 | 1013–1015 |

Without Mobium these scenes were (earlier rounds, other sessions): `storage_room` in A 3.33 ms (300 FPS; with the
cache 1.71 ms, 586 FPS, on 20 Oct), the mob pens as in the first column of the gate table above. Frames from different
sessions follow the GPU clock more than anything else (*Findings*), so a speed-up is only quoted from one session.

**The README's table** comes from these numbers: `storage_room` 300 → 586 FPS (setup A, 19 and 20 Oct 2026), the
three mob pen rows from the `/mobium gatec` table above (A, C, D).

## Players' results

`/mobium benchmark` files players sent (issue form, label `benchmark`; Discord), newest first. Each is one session:
Mobium off, on, on and off, 30 s per run, the median of the two runs in each state; the gain is the frame time off
over on (`<scene>_speedup`). Raw files:
[`players/`](https://github.com/btwonion/mobium-benchmarks/tree/main/players/).

| File | Machine | `storage_room` off → on | `mob_pen_live` off → on |
| --- | --- | --- | --- |
| [#32](https://github.com/btwonion/mobium/issues/32), 28 Oct 2026 | AMD Ryzen 7 7800X3D, Nvidia GeForce RTX 4070 Ti; desktop, 2560×1384 window, Fancy, Mod Menu | 248 → 2,773 FPS (4.04 → 0.36 ms), **11.2×** | 236 → 713 FPS (4.24 → 1.40 ms), **3.02×** |
| [#31](https://github.com/btwonion/mobium/issues/31), 28 Oct 2026 | Intel Core i7-13700HX, Nvidia GeForce RTX 4070 Laptop GPU; Lenovo Legion Pro 5 16IRX8 on mains, `balanced` profile, 2560×1515 window, Custom, no other mods | 111 → 252 FPS (9.05 → 3.96 ms), **2.28×** | 99 → 240 FPS (10.07 → 4.16 ms), **2.42×** |

Both ran 0.2.0 on Fabric (build `dd7253f`) without Sodium (setup `vanilla`), on OpenGL, without a shader pack or
resource packs, seed `mobium`, both modules active and Module 2's expand pass in the vertex stage. 1% lows: #32 164 →
685 and 169 → 370 FPS, #31 63 → 145 and 51 → 112 FPS. Without Sodium these frames do not compare with *Results for
0.2.0*. Module 2's check found no difference on either GPU (`docs/compat.md`, *On players' GPUs*).

- **On these Nvidia GPUs the frame gains what the CPU saves.** In `mob_pen_live` `prepare` fell by 2.90 ms on #32 and
  the frame by 2.83 ms, on #31 by 5.77 and 5.91 ms; on the Arc 140V the frame follows the clock that CPU and GPU share
  (*Gate C's redefinitions*, *Frames between sessions follow the GPU clock*).
- **`storage_room`'s gain is the vanilla frame over what the cache leaves.** The cache takes almost all of the CPU
  work (`prepare` 2.97 → 0.003 ms on #32, 5.84 → 0.015 ms on #31), so the gain depends on the rest of the frame: 0.36
  ms on #32, 3.96 ms on #31. Neither file has a `control` run to split that rest.
- **0.2.0's files name neither the OS nor the driver:** they record the GPU's renderer string but not `GL_VERSION`,
  and the power state only from Linux sysfs (`bench/MachineState.kt`). So #31 ran Linux; #32's empty power fields mean
  Windows or a Linux desktop without a mains supply or platform profile. Files from later versions have `# os` and
  `# driver` (*Tools*).

## Gates

Every gate also required zero screenshot differences (self-check and gametests, both loaders). Gates B and C were
redefined with Anton when their first targets turned out to depend on GPU work that vanilla pays as well.

| Gate | Decided | Definition | Result |
| --- | --- | --- | --- |
| A | 19 Oct 2026, setup A, `/mobium gatea` | `storage_room` at least 1.5× faster with the chest prototype than without | **1.53×** (3.33 → 2.18 ms) |
| B | 21 Oct 2026, setup A, `/mobium gateb`, build 6fb45dc | What the cached objects cost: `storage_room` with the cache's draw calls skipped, plus those draw calls' CPU time, at most 1.2× `control`; `control` with the cache at most 1.03× without | **1.170×** ((1.247 + 0.041) / 1.101 ms); `control` 1.003× |
| C | 26 Oct 2026, setups C and D, `/mobium gatec`, build b41d342 | In D `mob_pen_live`'s `prepare` + `shadow_prepare` at least 1.5× lower with Module 2; in C and D its frame at most 1.03× the frame without | **D: 1.90× lower** (13.70 → 7.22 ms), frame 0.84× (65.57 → 55.37 ms); **C: frame 0.80×** (9.05 → 7.23 ms) |

**Gate B's redefinition** (Anton, 20 Oct 2026). The first definition, the whole `storage_room` frame at most 1.2×
`control`, failed at 1.39× (1.695 against 1.218 ms). About 0.18 ms of the gap was Mobium's CPU work and about 0.30 ms
the GPU drawing the 1,000 chests and 300 signs, which vanilla pays as well; with no CPU work left at all the ratio would
still have been about 1.24×. Of the options (more CPU work first, gate against vanilla, waive the gate, gate Mobium's
own cost) Anton chose the last. The whole frame (1.395×) and the cost of drawing the cached objects (0.29 ms per
frame) are still reported, without a target. `/mobium gateb` records the extra run without the cache's draw calls
(setup `…+cache+nodraw`; the chests and signs vanish during it).

**Gate C's redefinitions** (with Anton, 25 Oct 2026). The first target, `mob_pen_live` 1.5× faster in D, was out of
reach: in D the frame is mostly GPU work, and removing all CPU tessellation would have made it at most 1.20× faster.
The second, 1.5× faster in C, was out of reach too: Fresh Animations' animations run inside `ModelPart.render`, so
3.2 of 5.3 ms of tessellation stay on the CPU without a single vertex written (*What Module 2 can take* below), at
most about 1.35×. Mobium can only take CPU work away; how much of it shows in the frame depends on the GPU. With Module
2 the iGPU clocked higher (CPU and GPU share one power budget), so the frames gained more than the CPU time saved. Gate
C held on 0.2.0-beta2's build (D 2.18× lower CPU work and 1.20× faster, C 1.36× faster; *Results for 0.2.0*).

## Findings

### Cost split per scene

19 Oct 2026, vanilla (no Mobium), setups without Fresh Animations or shader packs. What each module can win is bounded
by the stages it removes:

| Scene | Frame | Where the time goes | Bound for Mobium |
| --- | --- | --- | --- |
| `mob_pen_static`, `mob_pen_live` | 2.9–4.2 ms | Tessellate 0.9–1.3 ms, extract 0.55 ms (1.0–1.2 ms with EMF/ETF), the rest of prepare 0.6–0.8 ms, submit 0.2–0.3 ms. Pose is only 0.08–0.09 ms | Module 2 removes most of tessellate |
| `storage_room` | 3.3 ms (A, 1920×1080) | Prepare 1.7 ms: chest tessellation 0.34 ms and the 300 signs' text most of the rest; submit 0.26 ms, extract 0.16 ms, draw 0.22 ms | Module 1, chests and sign text |
| `map_wall` | 4.7 ms | Prepare 2.5 ms (items and maps in frames, shelf items), extract 0.75 ms, submit 0.5–0.6 ms, draw 0.45 ms | Module 1 for the shelves; maps in frames stay vanilla (translucent) |
| `display_build` | 1.7 ms | Extract 0.4–0.5 ms, prepare 0.75 ms (block and item models), submit 0.12–0.15 ms | Module 1 (display entities) |
| `control` | 1.1 ms | Nothing to accelerate | Must not get slower |

- **Writing vertices, not posing, is the cost of mobs** (tessellation 11–14× pose for vanilla models; with Fresh
  Animations tessellation grows from 1.0 to 4.3–4.6 ms, posing only to 0.17 ms).
- **EMF/ETF double the entity extraction** (0.55 → 1.0–1.2 ms) even with vanilla models; Module 2 does not touch it.
- **Display entities and item frames** are mostly extract, submit and prepare work, which the static cache skips for
  unchanged objects except their extraction (entities are extracted every frame; the cache's comparison costs about
  0.3 µs per display).

### What Module 2 can take

25 Oct 2026, `self_check.py fabric --mode profile-mobs` in the dev client (854×480): `mob_pen_live` as drawn, and with
every model part's vertex writes skipped (`Sections.skipModelVertices`; the mobs vanish). The difference is the most
Module 2 can take off the CPU. Timer averages in ms:

| Setup | Frame | `tessellate_entities` | `prepare` | `shadow_prepare` | `prepare` + `shadow_prepare` |
| --- | --- | --- | --- | --- | --- |
| plain, drawn | 4.79 | 1.32 | 2.24 | – | 2.24 |
| plain, no vertices | 2.66 | 0.38 | 0.68 | – | 0.68 |
| C, drawn | 11.07 | 5.34 | 6.47 | – | 6.47 |
| C, no vertices | 6.48 | 3.16 | 3.62 | – | 3.62 |
| D, drawn | 36.58 | 11.14 | 12.85 | 3.61 | 16.45 |
| D, no vertices | 23.59 | 3.92 | 4.64 | 0.75 | 5.38 |

- In C, 3.2 of 5.3 ms of tessellation stay without a single vertex: EMF evaluates Fresh Animations inside
  `ModelPart.render`, and the part walk and its pose math stay on the CPU too. Module 2 keeps all three.
- In D every vertex goes through Iris's per-quad extension (face normal, tangent, mid UV), so the vertex work is most
  of the entity CPU time: at most 3.1× lower.
- JFR could not split this: two recordings of the same scene charged the same third of `renderToBuffer` once to vertex
  writes and once to an EMF lambda (inlined frames). The skip switch measures it directly.

### Module 2's fixed cost: a compute batch of its own

27 Oct 2026. In the 0.2.0-beta1 round `display_build`, where Module 2 expands only 17 shields, got 0.15–0.3 ms
per frame slower. Measured in the Fabric dev client (1423×800 window, mains power, Mesa
26.2.5, uncapped), with `python3 benchmark/self_check.py fabric --mode expand-cost` (`--scene mob_pen_live`, `--scene
"mob_pen_live stages"`): each condition 8 s, in 4 rounds whose order rotates, static cache on. A fixed order did not
work: the laptop's CPU clocks drift within a round, and even `extract_entities`, which Module 2 does not touch, came
out 10 % apart between conditions. *GPU* is the time from a frame's start to its submit in GL timestamps; *passes* the
GPU time of the expand passes themselves, from a timestamp before each pass to one after its barrier (for a dispatch
that includes the hand-over between batches below).

**What costs:** Mesa's iris records `glDispatchCompute` in a compute batch of its own, apart from the render batch
with vanilla's upload and the draws, so each expand pass flushes both batches in the middle of the frame and the GPU
waits for the hand-over: three submissions per frame instead of one (`INTEL_DEBUG=submit`; docs/vanilla-notes.md
§16). Module 2's own CPU time is 25–30 µs per pass (records 5–7 µs for the shields, the dispatch call 10–15 µs). The
same shader compiled as a vertex shader and drawn as points with rasterisation off (the *vertex stage*) stays in the
render batch: one submission per frame, as without Module 2, and the same bytes (`--mode parts`).

| Scene, setup | Module 2 off | compute | vertex stage | no vertices |
| --- | --- | --- | --- | --- |
| `display_build`, A: frame / GPU | 1.150 / 0.568 ms | 1.337 / 0.810 ms | 1.252 / 0.600 ms | 1.102 / 0.589 ms |
| `mob_pen_live`, A | 4.18 / 1.10 ms | 3.05 / 1.34 ms | 3.06 / 1.19 ms | 2.22 / 0.54 ms |
| `mob_pen_live`, C | 11.46 / 2.38 ms | 8.98 / 2.90 ms | 9.00 / 2.46 ms | 6.77 / 1.19 ms |
| `mob_pen_live`, D | 66.7 / 29.2 ms | 48.8 / 26.4 ms | 58.6 / 30.9 ms | 44.8 / 22.7 ms |

The passes alone, two runs of compute against the vertex stage in each setup (frames in brackets):

| `mob_pen_live` | compute: passes (frame) | vertex stage: passes (frame) |
| --- | --- | --- |
| A | 320, 320 µs (2.73, 2.68 ms) | 121, 118 µs (2.65, 2.49 ms) |
| B (shader pack, two passes: level and shadow) | 1,880, 1,892 µs (33.8, 37.6 ms) | 762, 688 µs (40.0, 40.4 ms) |
| C | 735, 850 µs (9.19, 9.58 ms) | 279, 300 µs (9.40, 9.16 ms) |
| D | 3,794, 3,410 µs (59.5, 50.1 ms) | 1,015, 942 µs (64.8, 63.7 ms) |

What it shows:

- **Without a shader pack the vertex stage removes most of the fixed cost.** In `display_build` Module 2 costs the GPU
  0.03 ms per frame instead of 0.24, the frame 0.10 ms instead of 0.19; the passes cost the GPU 2.6–2.8× less in A
  and C. With the dev window CPU-bound, A's and C's frames are within noise of compute's; at 1920×1080, where A is
  closer to GPU-bound, the 0.2 ms should show (Anton's `/mobium stages`).
- **With a shader pack compute's frames are faster**, by 3–14 ms in B and D in every run, although its passes cost the
  GPU 1–2.8 ms more. The batch flushes a dispatch causes submit the frame's first part (shadow pass, terrain) early,
  so the GPU starts on it while the CPU still records; with the pack's 25–35 ms of GPU work per frame that matters more
  than the pass. A `glFlush` after the vertex stage's pass got half of D's difference back (63.7 → 56.3 ms, compute
  50.1), but with Mesa's threaded driver `glFlush` waits for the driver thread: 0.06 ms per frame in A, 2.5 ms in D.
  The dispatch's flushes happen on the driver thread.
- So the expand pass runs in the vertex stage without a shader pack and as a compute dispatch with one
  (`ExpandPass.stage`); `-Dmobium.parts.stage=compute|vertex` overrides that. At 1920×1080 the vertex stage is faster
  without a shader pack and both are equal with one (*Results for 0.2.0*). Single frames in B and D vary by ±5 ms
  between runs of the same condition in the dev window.

### Iris's shadow pass

From the 23–25 Oct 2026 round (Complementary Reimagined r5.9.3 at its defaults, as setups B and D run it):

- The shadow pass costs 0.43–0.47 ms in `control` (shadow terrain only) and up to 5.3 ms with entities; in D's mob
  pens without Module 2, 5.0–5.3 ms, of which entity tessellation 2.2 ms. Fresh Animations makes the shadow pass's
  tessellation 2.4× as expensive, as it does the main pass's.
- **Cached block entities cost it nothing:** Complementary sets `shadowBlockEntities = false`, so
  `shadow_tessellate_block_entities` is 0.000 ms in every scene and `storage_room`'s shadow pass equals `control`'s.
- **Cached entities do cost it:** in `display_build` the shadow pass prepares the 500 displays again (prepare
  1.9–2.0 ms of a 4.2–4.6 ms pass), which the main pass takes from the cache (`PLAN.md`, *Later*).

### Frames between sessions follow the GPU clock

The iGPU picks its clock per workload, and CPU and GPU share one power budget. Between sessions, scenes where Mobium
does nothing moved by up to ±18 % with the clock: `control` 1.18× slower in D at 668–766 against 900–942 MHz, 0.85× in C
at 1160–1195 against 904–958 MHz (26–27 Oct against 23–25 Oct). A's mob pens looked 1.07–1.14× slower with Module 2
across sessions and are 1.19× faster with it in one session. So speed-ups are measured within one session, alternating
(the gate commands, `/mobium stages`, `/mobium benchmark`), and `summary.csv` records `gpu_mhz`.

### Slow vanilla `storage_room`

On 19 Oct 2026, in three of five sessions, vanilla `storage_room` ran at 15–18 ms per frame for the whole session
instead of 3.3–4.2 ms, while other scenes in the same sessions ran normally. The CPU stages grew only from about 2.3 to
3.4 ms, so about 13 ms per frame were spent waiting; the factor of 5 matches the iGPU's clock range (400–1950 MHz).
The likely cause is the clock dropping when vanilla's per-frame upload of every chest and sign vertex makes CPU and
GPU wait for each other; not confirmed, and not seen since `gpu_mhz` is recorded (no round since had a vanilla
`storage_room` run). A baseline in this state does not count (Gate A's second session measured 8.05× and was not
counted).

### Measurement pitfalls

- Instance settings Prism ignores: until 23 Oct 2026 `benchmark/prism_instances.py` appended `-Dmobium.bench.expect`
  to the end of `instance.cfg`, where it landed in Prism's `[UI]` section, so the rounds of 21–22 Oct ran without the
  setup check (their `setup` column shows the right setups). A run on 22 Oct also refreshed new duplicate folders
  instead of the renamed A and D. The script now writes `[General]` and uses the folder Prism launched last.
- Vanilla's default 120 FPS cap, and its idle frame limit after 60 s without input (the bench handles the second).
- Battery power: vanilla `storage_room` 9 → 42 ms on the dev laptop (19 Oct 2026, gametest window); the bench now
  warns in chat.
- The first run after a scene is built (the suite waits until the world has settled).
- The slow state of vanilla `storage_room` above.
- Zombies in the sun (fixed in the datapack), packs that are installed but not selected, and quitting during
  `control` (all caught by the instances and the suite now).
- A bench kit built from an outdated checkout (21 Oct 2026: four commits behind, so the round measured the previous
  build and `/mobium gateb` the old gate). The jar now names its commit (`build` in `summary.csv`), and
  `benchmark/prism_instances.py` refuses a stale kit or a checkout behind its upstream.
- Day-to-day speed of the laptop (`control` 1.02–1.11 ms in A over three sessions): compare within one session.

## Earlier rounds

All on the machine above, fullscreen 1920×1080 on the AOC monitor from 20 Oct on. Write-ups in `git show
dd7253f:docs/benchmarks.md`, raw data in [btwonion/mobium-benchmarks](https://github.com/btwonion/mobium-benchmarks).

| Date | Build | What | Folder |
| --- | --- | --- | --- |
| 27 Oct 2026 | 8d26a26 | 0.2.0-beta2, unattended; *Results for 0.2.0* | `2026-10-27-thinkpad-x9-8d26a26` |
| 26–27 Oct 2026 | b41d342 | 0.2.0-beta1 release round, Module 2 on | `2026-10-27-thinkpad-x9` |
| 26 Oct 2026 | b41d342 | Gate C | `2026-10-26-thinkpad-x9` |
| 23–25 Oct 2026 | a6997e5 | 0.1.0-beta2, shadow-pass timers | `2026-10-23-thinkpad-x9` |
| 21–22 Oct 2026 | 6fb45dc | Entities cached; Gate B | `2026-10-21-thinkpad-x9` |
| 21 Oct 2026 | df4080d | Repeat of 20 Oct on an outdated kit (does not count for Gate B) | `2026-10-21-thinkpad-x9-df4080d` |
| 20 Oct 2026 | m5-static-cache | Static cache, block entities; Gate B's first definition | `2026-10-20-thinkpad-x9` |
| 19 Oct 2026 | M3 branch | Vanilla baselines (partial), Gate A | `2026-10-19-thinkpad-x9` |
