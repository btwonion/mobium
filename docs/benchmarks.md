# Benchmarks

How Mobium is measured, and the results. Every gate in `PLAN.md` (A–C) is decided with the numbers on this page.

## Tools

- **`benchmark/mobium-bench`** — a datapack that builds the scenes. It works without Mobium; regenerate it with
  `python3 benchmark/generate.py` after changing the generator, never edit the `.mcfunction` files by hand.
- **`/mobium bench <scene> <seconds> [setup]`** — Mobium's client command. It hides the HUD, warms up for 5 s, records
  every frame for `<seconds>`, then prints a summary in chat and writes two files to `<game dir>/mobium-bench/`:
  - `<scene>-<setup>-<yyyyMMdd-HHmmss>.csv`: one row per frame (frame time, every timer below, entity and block
    entity counts);
  - `summary.csv`: one row per run, appended (averages, Minecraft and Mobium version, loader, backend, GPU, the
    monitor and video mode, the window size, `ac` or `battery`, the power profile, and `gpu_mhz`, the GPU's actual
    clock sampled once per second; the last three come from Linux sysfs and stay empty elsewhere). A `summary.csv`
    with older columns is renamed to `summary-before-<timestamp>.csv`.
  `setup` is detected when omitted: `sodium` or `vanilla`, `+iris` if a shader pack is active, `+emf` if Entity Model
  Features is installed, `+fa` if a resource pack with "fresh" in its id is enabled, `+chestcache` while the M3 chest
  prototype is on. Before it starts, the run warns in chat about anything that breaks the protocol: a window other than
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
  `mobium-bench/gatea-<timestamp>.csv`. Gate A was passed with the M3 chest prototype; since M5 the command compares
  the whole static cache (chests and signs) and stays useful as a before/after number.
- **`/mobium gateb [seconds] [runs]`** — Gate B: records `storage_room` with the static cache, `control` with it and
  `control` without it, in turn (defaults: 60 s, 3 rounds; about 12 minutes), then prints both of Gate B's numbers:
  `storage_room` against `control` (target: at most 1.2×) and `control` with the cache against `control` without it
  (must not be slower; up to 1.03× counts as noise), and writes `mobium-bench/gateb-<timestamp>.csv` with the medians
  and the two ratios. Both gate commands switch the cache themselves for the plan, even when it is off in the settings
  (which they do not save).
- **Setup names** get `+cache` while the static cache is active. Since M5 the cache is on by default, so a plain
  `/mobium suite` measures with it; switch Mobium off in the settings (or with the A/B key, the key right of P: `]` on US, `+` on German keyboards) for
  vanilla numbers.
- **F3 entry `mobium:timings`** — the same timers averaged over the last 64 frames. Enable it in the debug options
  screen (the timers only run while it is shown or a benchmark records).
- **Prism instances** — `./gradlew :26.3-fabric:benchKit` collects the mods of the four setups and the datapack,
  `./gradlew :26.3-fabric:runBenchWorld` saves a ready benchmark world (both under `versions/26.3-fabric/build/`), and
  `python3 benchmark/prism_instances.py` creates or updates four Prism Launcher instances "Mobium Bench A–D" with the
  mods, the world and the settings below. It also refreshes the datapack inside the instances' worlds, downloads the
  shader pack and Fresh Animations from Modrinth once (pinned versions, checked against Modrinth's SHA-512; skip with
  `--no-download`), installs and selects them in the setups that need them, and adds
  `-Dmobium.bench.expect=<setup>` to each instance's Java arguments, so `/mobium suite` and `/mobium gatea` refuse to
  run with the wrong packs. Re-run the first and last step after changing Mobium.

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

All timers are CPU wall-clock time on the render thread and only count the main level pass (not the hand, the GUI or
Iris's shadow pass). See `docs/vanilla-notes.md` §0 for the stages.

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
set; the light one is still open (`PLAN.md` §11).

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

### Running a setup

1. Start the setup's Prism instance on mains power, put the fullscreen window on a 1920×1080 monitor and open the
   "Mobium Bench" world.
2. `/mobium suite`. Check chat for warnings before you step away, then leave the computer alone until chat shows the
   medians (about 22 minutes). The HUD stays hidden until the very end: the last scene, `control`, is an empty field
   and looks finished, but it records for another three minutes. The result for a scene and setup is the median of
   the three runs.
3. Setup A only: `/mobium gateb` for Gate B (about 12 minutes); `/mobium gatea` gives the cache's speed-up in
   `storage_room` (about 7 minutes).
4. Do this for all four setups, then copy each instance's `minecraft/mobium-bench/` folder into
   `docs/benchmarks/results/<yyyy-mm-dd>-<machine>/<setup>/` (per-run CSVs gzipped) and fill in the tables below, or
   hand the folders to Claude Code.

By hand instead: `/function mobium-bench:<scene>`, wait for "Scene <scene> is ready" plus 10 seconds, then
`/mobium bench <scene> 60` three times without touching anything.

## Results

### 19 Oct 2026, M2 baseline and Gate A

Machine: Lenovo ThinkPad X9-14 Gen 1, Intel Core Ultra 7 258V, Intel Arc 140V iGPU (`xe` driver, Mesa 26.2.4, OpenGL
4.6), 32 GB, Fedora 44 · Mobium 0.1.0-beta1 (M3 branch) · Graphics preset: custom (Fancy with the protocol's changes) ·
Raw data: [`results/2026-10-19-thinkpad-x9/`](benchmarks/results/2026-10-19-thinkpad-x9/) (`summary.csv`, the suite
and Gate A medians, and one gzipped CSV per run).

This round does not cover the whole protocol. The table says what was actually measured:

- **Setup A** has only `storage_room`, from the two `/mobium gatea` sessions; the suite was not run.
- **No shader pack and no Fresh Animations anywhere:** B and D ran with Iris installed but no pack selected (Mobium
  recorded them as `sodium` and `sodium+emf`), C and D without Fresh Animations. B is therefore "A + Iris idle", C "A +
  EMF/ETF with vanilla models", D "C + Iris idle".
- **Mob pens:** only the first of three runs counts. The zombies' leather helmets wore out in the sun about two minutes
  after the scene was built, and about 155 zombies burned to death during runs 2 and 3 (500 → 345 mobs, stutter from
  fire and death animations). Fixed for the next round: the datapack now gives them unbreakable helmets.
- **`control`** exists only for D: the B and C suites were quit during the last scene, which looks finished because
  it is an empty field.
- **Display:** only Gate A session 1 ran at the protocol's 1920×1080 (external AOC monitor, 240 Hz); every other
  session ran on the built-in panel at 1728×1080 (120 Hz).
- **Two states of vanilla `storage_room`:** in three of five sessions (A session 2, B, D) vanilla `storage_room` ran at
  about 60 FPS instead of 240–300, see *Slow vanilla `storage_room`* below.

The datapack fix and the checks that catch the rest (packs installed and selected by the Prism script, the setup
check, warnings for display and power, medians saved after every run) are in place for the next round; refresh the
instances as described under *Prism instances* first.

Median of three 60-second runs, Mobium modules off, times in ms per frame. Extract, submit, pose and tessellate add
entities and block entities.

| Scene | Setup | Avg FPS | 1% low | Avg ms | p95 ms | Extract | Submit | Pose | Tessellate | Prepare | Draw | Note |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mob_pen_static | B | 341 | 198 | 2.94 | 3.74 | 0.55 | 0.20 | 0.08 | 1.01 | 1.70 | 0.02 | run 1 only |
| mob_pen_static | C | 302 | 206 | 3.31 | 4.15 | 0.99 | 0.28 | 0.08 | 0.89 | 1.60 | 0.02 | run 1 only |
| mob_pen_static | D | 284 | 162 | 3.52 | 4.37 | 1.04 | 0.31 | 0.08 | 0.91 | 1.67 | 0.02 | run 1 only |
| mob_pen_live | B | 332 | 192 | 3.01 | 3.78 | 0.56 | 0.24 | 0.09 | 1.01 | 1.75 | 0.02 | run 1 only |
| mob_pen_live | C | 241 | 153 | 4.15 | 4.99 | 1.20 | 0.34 | 0.09 | 1.26 | 2.09 | 0.02 | run 1 only |
| mob_pen_live | D | 273 | 169 | 3.66 | 4.42 | 1.09 | 0.33 | 0.08 | 0.96 | 1.75 | 0.02 | run 1 only |
| storage_room | A | 300 | 206 | 3.33 | 3.75 | 0.16 | 0.26 | 0.03 | 0.34 | 1.68 | 0.22 | Gate A session 1, 1920×1080 |
| storage_room | A | 60 | 52 | 16.73 | 17.93 | 0.46 | 0.45 | 0.04 | 0.48 | 2.39 | 0.29 | Gate A session 2, slow state |
| storage_room | B | 61 | 50 | 16.48 | 18.10 | 0.31 | 0.43 | 0.03 | 0.44 | 2.43 | 0.26 | slow state |
| storage_room | C | 238 | 144 | 4.20 | 4.87 | 0.21 | 0.42 | 0.04 | 0.43 | 2.26 | 0.28 | |
| storage_room | D | 57 | 48 | 17.46 | 18.78 | 0.47 | 0.67 | 0.04 | 0.48 | 2.84 | 0.28 | slow state |
| map_wall | B | 213 | 103 | 4.69 | 5.13 | 0.78 | 0.53 | 0 | 0 | 2.48 | 0.44 | |
| map_wall | C | 213 | 103 | 4.71 | 5.16 | 0.73 | 0.61 | 0 | 0 | 2.43 | 0.49 | |
| map_wall | D | 208 | 100 | 4.82 | 5.32 | 0.76 | 0.48 | 0 | 0 | 2.63 | 0.46 | |
| display_build | B | 577 | 239 | 1.73 | 1.97 | 0.53 | 0.12 | 0.00 | 0.01 | 0.74 | 0.01 | |
| display_build | C | 576 | 251 | 1.74 | 1.98 | 0.53 | 0.15 | 0.00 | 0.01 | 0.73 | 0.01 | |
| display_build | D | 579 | 235 | 1.73 | 1.97 | 0.40 | 0.15 | 0.00 | 0.01 | 0.80 | 0.01 | |
| control | D | 889 | 575 | 1.12 | 1.41 | 0.00 | 0.00 | 0 | 0 | 0.00 | 0.00 | |

Runs of the same scene and setup agree within 2 % (8 % in the slow state; mob pens have one valid run each), so the
differences between setups of 10 % and more are real.

### Cost split per scene

What each module can win is bounded by the stages it removes. Without Fresh Animations and shader packs:

| Scene | Frame | Where the time goes | Bound for Mobium |
| --- | --- | --- | --- |
| `mob_pen_static`, `mob_pen_live` | 2.9–4.2 ms | Tessellate 0.9–1.3 ms, extract 0.55 ms (1.0–1.2 ms with EMF/ETF), the rest of prepare 0.6–0.8 ms, submit 0.2–0.3 ms. Pose is only 0.08–0.09 ms: the vanilla walk animation costs almost nothing, and `live` is barely slower than `static`. | Module 2 removes most of tessellate: at most about 1.5× (3.0 → 2.0 ms in B) |
| `storage_room` | 3.3 ms (A, 1920×1080) | Prepare 1.7 ms, of which chest tessellate 0.34 ms and the 300 signs (text, glyph quads) most of the rest; submit 0.26 ms, extract 0.16 ms, draw 0.22 ms, about 1 ms outside the stages | Module 1: the chest prototype alone reaches 2.18 ms (1.53×); the signs are what is left |
| `map_wall` | 4.7 ms | Prepare 2.5 ms (items and maps in frames, shelf items; no `Model` parts, so pose and tessellate are 0), extract 0.75 ms, submit 0.5–0.6 ms, draw 0.45 ms | Module 1 (item frames, shelves): most of the 3.6 ms above `control` |
| `display_build` | 1.7 ms | Extract 0.4–0.5 ms, prepare 0.75 ms (block and item models), submit 0.12–0.15 ms | Module 1 (display entities): most of the 0.6 ms above `control` |
| `control` | 1.1 ms | Nothing to accelerate | Must not get slower |

Consequences for the plan:

- **Tessellation, not posing, is the cost of vanilla mobs** (11–14× pose), which is what Module 2 targets. The bound
  of about 1.5× in B is exactly Gate C's target, so Gate C depends on what Fresh Animations and Iris's 56-byte
  vertex format add to tessellate and pose. Measure setup D with both before starting M6.
- **EMF/ETF double extract** for mobs (0.55 → 1.0–1.2 ms) even with vanilla models. Module 2 does not touch extract.
- **Signs decide Gate B.** With the chests cached, `storage_room` is 2.18 ms against 1.12 ms for `control` (about 1.9×,
  measured in different sessions; Gate B allows 1.2×). M5 has to cache sign text as well, although the plan keeps translucent geometry on the
  vanilla path (M5, *Only solid and cutout geometry is cached*); that conflict has to be settled in M5.
- **Display entities and item frames** are mostly extract, submit and prepare work that Module 1's static cache can
  skip for unchanged objects.

### Gate A

`/mobium gatea` in setup A alternates the chest prototype off and on, 3 runs of 60 s each, in one session:

| Session | Display | Prototype off | Prototype on | Speed-up |
| --- | --- | --- | --- | --- |
| 1 (21:17) | AOC 24", 1920×1080 @ 240 Hz | 3.33 ms, 300 FPS | 2.18 ms, 460 FPS | **1.53×** (slowest pairing of single runs: 1.51×) |
| 2 (22:32) | Built-in, 1728×1080 @ 120 Hz | 16.73 ms, 60 FPS | 2.08 ms, 482 FPS | 8.05× (slow state, not counted) |

With the prototype on, extract and submit for block entities drop from 0.16 + 0.26 to 0.09 + 0.20 ms (what is left
is the signs), tessellate from 0.34 ms to 0 and prepare from 1.68 to 1.15 ms. **Gate A passes on session 1, at the
protocol's resolution, with 1.53×**; session 2 is ignored because its baseline is in the slow state. For "no visible
difference", the `BenchmarkScenesGameTest` screenshot comparison of `storage_room` with the prototype off and on finds
0 differing pixels, and no difference was reported from the runs; if one shows up in the live game (A/B key: `]` on US, `+` on German keyboards),
the gate is open again.

### Slow vanilla `storage_room`

In A session 2, B and D, every vanilla `storage_room` run took 15–18 ms per frame for the whole run; in A session 1
and C it took 3.3 and 4.2 ms. The CPU stages only grew from about 2.3 to 3.4 ms, so about 13 ms per frame are spent
outside them, waiting for the GPU or for a buffer. It is not the whole machine: the mob pens and `map_wall` in the same
sessions ran at their normal speed, and in A session 2 the chest prototype ran at 2.08 ms (its usual speed) in the
runs between the slow vanilla ones. The factor of 5 matches this iGPU's clock range (400–1950 MHz), so the likely
explanation is that the GPU clock drops to its minimum when vanilla's per-frame upload of all chest and sign vertices
makes the CPU and GPU wait for each other; this is not confirmed. The next round records the GPU clock (`gpu_mhz`
in `summary.csv`), which settles it. A first reading from the gametest (20 Oct, on battery) shows that the clock does
follow the workload: 500 MHz in `mob_pen_live`, 1950 MHz in `control`, in the same session. Until then Gate A uses only
the fast state, the conservative choice. (The 20 Oct round below has no vanilla `storage_room` run, so this is still
open.)

### 20 Oct 2026, M5 static cache, all four setups

Machine as on 19 Oct · every run fullscreen at 1920×1080 on the external AOC monitor (240 Hz), on mains power · Mobium
0.1.0-beta1 from `m5-static-cache` (block entities cached; item frames, armor stands and displays not yet) ·
Complementary Reimagined r5.9.3 selected in B and D, Fresh Animations 1.11.1 in C and D (installed and selected by
`benchmark/prism_instances.py`) · Raw data: [`results/2026-10-20-thinkpad-x9/`](benchmarks/results/2026-10-20-thinkpad-x9/).

- Every suite ran with the static cache on (the setups are `…+cache`); the only vanilla runs of this round are
  Gate B's `control` runs. Speed-ups against vanilla below compare with 19 Oct, another session.
- C's suite ended after `display_build` (no `control`), D's `control` has one run.
- B and D: Complementary at 1920×1080 keeps this iGPU at about 30 FPS even in the empty `control` (33 ms), so those
  frames are GPU-bound; the stage timers still show the CPU side.
- GPU clock: `gpu_mhz` is the mean of one `act_freq` sample per second (an idle GPU counts as 0). It stayed between
  550 and 1,190 MHz in every run, well below the 1,950 MHz maximum: the driver picks the clock per workload.

Median of three 60-second runs, static cache on, times in ms per frame; extract, submit, pose and tessellate add
entities and block entities.

| Scene | Setup | Avg FPS | 1% low | Avg ms | p95 ms | Extract | Submit | Pose | Tessellate | Prepare | Draw | GPU MHz | Runs |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mob_pen_static | A | 336 | 215 | 2.98 | 3.67 | 0.59 | 0.22 | 0.09 | 1.00 | 1.72 | 0.02 | 700 | 3 |
| mob_pen_live | A | 322 | 195 | 3.11 | 3.86 | 0.62 | 0.23 | 0.09 | 1.04 | 1.80 | 0.02 | 700 | 3 |
| storage_room | A | 586 | 353 | 1.71 | 2.16 | 0.12 | 0.00 | 0 | 0 | 0.01 | 0.05 | 827–944 | 3 |
| map_wall | A | 232 | 106 | 4.31 | 4.82 | 0.87 | 0.41 | 0 | 0 | 2.08 | 0.47 | 1116–1190 | 3 |
| display_build | A | 588 | 260 | 1.70 | 1.87 | 0.52 | 0.12 | 0.00 | 0.01 | 0.68 | 0.01 | 700–853 | 3 |
| control | A | 903 | 560 | 1.11 | 1.41 | 0.00 | 0.00 | 0 | 0 | 0.00 | 0.00 | 1050–1114 | 3 |
| mob_pen_static | B | 16 | 14 | 63.07 | 67.81 | 1.54 | 0.68 | 0.29 | 3.90 | 5.71 | 0.18 | 550–574 | 3 |
| mob_pen_live | B | 19 | 15 | 53.06 | 62.17 | 1.00 | 0.51 | 0.22 | 3.47 | 5.02 | 0.16 | 569–699 | 3 |
| storage_room | B | 28 | 20 | 35.78 | 46.73 | 0.43 | 0.00 | 0 | 0 | 0.03 | 0.28 | 825–935 | 3 |
| map_wall | B | 19 | 13 | 53.97 | 59.07 | 2.65 | 1.06 | 0 | 0 | 4.51 | 0.99 | 580–672 | 3 |
| display_build | B | 20 | 18 | 49.73 | 54.01 | 1.41 | 0.44 | 0.00 | 0.04 | 2.42 | 0.23 | 578–579 | 3 |
| control | B | 30 | 22 | 33.35 | 42.98 | 0.01 | 0.00 | 0 | 0 | 0.01 | 0.00 | 757–806 | 3 |
| mob_pen_static | C | 117 | 73 | 8.55 | 12.23 | 0.85 | 1.18 | 0.17 | 4.31 | 5.13 | 0.02 | 758–774 | 3 |
| mob_pen_live | C | 111 | 69 | 8.97 | 12.79 | 0.89 | 1.28 | 0.18 | 4.57 | 5.43 | 0.02 | 793–796 | 3 |
| storage_room | C | 666 | 420 | 1.50 | 1.84 | 0.12 | 0.00 | 0 | 0 | 0.01 | 0.04 | 970–1014 | 3 |
| map_wall | C | 246 | 114 | 4.07 | 4.54 | 0.81 | 0.43 | 0 | 0 | 1.96 | 0.46 | 1146–1169 | 3 |
| display_build | C | 590 | 249 | 1.69 | 1.87 | 0.40 | 0.15 | 0.00 | 0.01 | 0.74 | 0.01 | 700 | 3 |
| mob_pen_static | D | 15 | 12 | 67.93 | 78.20 | 1.45 | 3.17 | 0.22 | 8.88 | 10.89 | 0.18 | 654–757 | 3 |
| mob_pen_live | D | 16 | 13 | 63.70 | 75.29 | 1.17 | 3.07 | 0.22 | 8.73 | 10.56 | 0.15 | 660–701 | 3 |
| storage_room | D | 27 | 21 | 36.97 | 45.67 | 0.37 | 0.00 | 0 | 0 | 0.03 | 0.26 | 773–844 | 3 |
| map_wall | D | 19 | 13 | 54.00 | 59.17 | 2.93 | 1.21 | 0 | 0 | 4.61 | 1.00 | 587–597 | 3 |
| display_build | D | 19 | 18 | 51.37 | 54.12 | 1.52 | 0.59 | 0.00 | 0.04 | 2.36 | 0.20 | 550 | 3 |
| control | D | 30 | 23 | 33.53 | 42.27 | 0.01 | 0.00 | 0 | 0 | 0.01 | 0.00 | 792 | 1 |

What the round shows:

- **`storage_room` (A): 3.33 → 1.71 ms**, 1.95× faster than vanilla on 19 Oct (Gate A session 1, same monitor), and
  0.47 ms faster than Gate A's chest prototype (2.18 ms): the signs are cached too. Block entities cost 0.12 ms of
  extraction, nothing of submit, 0.01 ms of prepare (vanilla: 1.68) and 0.05 ms of draw calls.
- `map_wall`: the shelves are cached (prepare 2.08 ms against 2.43–2.63 ms on 19 Oct); the 400 item frames with maps
  stay vanilla by design (maps are translucent custom geometry). `display_build`, the mob pens: nothing cached yet.
- **Fresh Animations** (C against A): the mob pens take 8.5–9.0 ms instead of 3.0–3.1; tessellation grows from 1.0 to
  4.3–4.6 ms, posing only from 0.09 to 0.17–0.18 ms, so even with Fresh Animations writing vertices is the cost, not
  animating. This is the M6 measurement `PLAN.md` asked for.
- **Gate C outlook** (D, `mob_pen_live`): 63.7 ms against 33.5 ms for `control`. The timed CPU stages make up about
  15 ms of the 30 ms difference (tessellation 8.7 ms); the rest is GPU work and Iris's shadow pass, whose entity
  drawing the timers do not count. M6 needs shadow-pass timers before it can promise 1.5×.

### Gate B (20 Oct 2026): failed, 1.39×

`/mobium gateb` in setup A ([`gateb-20261020-164524.csv`](benchmarks/results/2026-10-20-thinkpad-x9/A/gateb-20261020-164524.csv),
three rounds of 60 s, medians):

| Run | Frame | p95 | Extract BE | Prepare | Draw | GPU MHz |
| --- | --- | --- | --- | --- | --- | --- |
| `storage_room`, cache on | 1.695 ms | 2.13 ms | 0.126 ms | 0.008 ms | 0.050 ms | 865–904 |
| `control`, cache on | 1.218 ms | 1.61 ms | 0.000 ms | 0.003 ms | 0.000 ms | 923–981 |
| `control`, cache off | 1.213 ms | 1.59 ms | 0.001 ms | 0.003 ms | 0.000 ms | 946–983 |

- **`storage_room` / `control` = 1.39× (target at most 1.2×): failed.** No round came close: 1.37×, 1.34×, 1.41×.
- `control` with / without the cache = 1.005× (at most 1.03×): passes.

**Where the 0.48 ms go.** Mobium's timed CPU work in `storage_room` is about 0.18 ms: block entity extraction 0.126 ms
(Sodium's per-block-entity loop plus the cache's fast-path check, about 0.1 µs for each of the 1,300 objects), the
cache's draw calls 0.050 ms, prepare 0.005 ms more than `control`. The other ~0.30 ms are outside every timer: the GPU
drawing 1,000 chests and 300 signs. In the dev window, skipping the cache's draw calls measured about 0.25 ms of GPU
time for them at 1423×800 (*Gate B first look* below); 1920×1080 has 1.8× the pixels. Vanilla draws the same
vertices and pays the same; no cache can avoid it. The driver's clock choice adds to it: in A `storage_room` took
1.60 ms at 944 MHz and 1.76 ms at 827 MHz, and in C, which keeps the GPU at about 1,000 MHz, 1.50 ms.

**What is left to optimise, and the bound.** 1.2 × 1.218 ms = 1.46 ms, so `storage_room` would have to lose 0.23 ms.
Removing all of Mobium's timed CPU work (0.18 ms) leaves about 1.51 ms, **1.24×**: on this machine Gate B as written
cannot be reached through CPU work alone.

- Skip Sodium's per-block-entity loop for sections whose cached objects are all unchanged: needs event-driven
  invalidation (lid events, block entity data, light and block updates) instead of the per-frame check, and a hook in
  Sodium's internals. Saves at most about 0.1 ms (→ about 1.31×).
- Fewer draw calls (bigger buckets or multi-draw): at most 0.03 ms of CPU, perhaps a little GPU state switching.
- The GPU cost itself only shrinks with fewer pixels or vertices, which would change the picture.

Gates are hard stops (`PLAN.md` §7): how to proceed is Anton's call, and the release waits for it.

### Measurement pitfalls

- Vanilla's default 120 FPS cap, and its idle frame limit after 60 s without input (the bench handles the second).
- Battery power: vanilla `storage_room` 9 → 42 ms on the dev laptop (19 Oct 2026, gametest window); the bench now
  warns in chat.
- The first run after a scene is built (the suite waits until the world has settled).
- The slow state of vanilla `storage_room` above.
- Zombies in the sun (fixed in the datapack), packs that are installed but not selected, and quitting during
  `control` (all caught by the instances and the suite now).

### First look (not a baseline)

Run on mains power: on battery the laptop's vanilla `storage_room` frame grows to about 42 ms while the cached and
empty scenes stay as fast, which inflates every speed-up (19 Oct 2026: 7–8× on battery).

From the Fabric client gametest on the development laptop (Intel Arc 130V/140V iGPU, Mesa 26.2.4, OpenGL, 854×480
gametest window, Sodium + EMF/ETF without Fresh Animations, no shader pack). Max framerate unlimited and VSync off:
with vanilla's default 120 FPS cap these scenes sit at the cap (8.33 ms) and differences disappear.

`storage_room`, 5-second runs, M3 chest prototype off and on (the gametest also checks that both pictures are
identical):

| Setup | Frame | Extract BE | Submit BE | Tessellate BE | Prepare | Draw |
| --- | --- | --- | --- | --- | --- | --- |
| Chest prototype off | 9.38 ms (107 FPS) | 0.45 ms | 0.79 ms | 0.81 ms | 4.81 ms | 0.56 ms |
| Chest prototype on | 5.55 ms (180 FPS) | 0.34 ms | 0.47 ms | 0 | 3.11 ms | 0.62 ms |

With the prototype on, what is left of `prepare` and `submit` is almost entirely the 300 signs. (M3 thought their
text was translucent; it is opaque and drawn in the solid phase, and since M5 the static cache takes it too.)

`mob_pen_live`, 5-second run (measured before the frame-rate cap was lifted, at 110 FPS, just under it):

| Frame | Extract E | Submit E | Pose E | Tessellate E | Prepare | Draw |
| --- | --- | --- | --- | --- | --- | --- |
| 9.09 ms (110 FPS) | 1.98 ms | 0.67 ms | 0.16 ms | 2.28 ms | 3.82 ms | 0.04 ms |

Even without Fresh Animations, writing vertices for 500 vanilla mobs costs about 14 times as much CPU time as posing
them; treat this as a hint for what to look for in the real baselines, not as a result.

### Gate B first look (M5, not a measurement)

20 Oct 2026, `python3 scripts/self_check.py fabric --mode gateb` on the development laptop: `/mobium gateb 20 2` in the
Fabric dev client's 1423×800 window (Sodium + EMF/ETF, no shader pack), then `storage_room` with the static cache
twice with its draw calls on and twice with them skipped (a dev switch; the cached objects then vanish):

| Run | Frame |
| --- | --- |
| `control`, cache on / off | 1.128 / 1.126 ms |
| `storage_room`, cache on | 1.63 ms (**1.44×** `control`) |
| `storage_room`, cache on, its draw calls skipped | 1.22–1.24 ms |

Stage timers of the cached `storage_room`: block entity extraction 0.20 ms (Sodium's loop and the cache's per-object
check for 1,300 objects), the cache's draw calls 0.09 ms of CPU. Skipping the draws saves 0.33–0.36 ms, so drawing the
1,000 chests and 300 signs costs the GPU about 0.25 ms. Vanilla pays that as well, and no cache can avoid it: in this
window `storage_room` would be about 1.2× `control` even with no CPU work at all. At the protocol's 1920×1080 Anton
measured 1.39× (*Gate B* above).

