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
the fast state, the conservative choice.

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
window `storage_room` would be about 1.2× `control` even with no CPU work at all. Whether Gate B's 1.2× holds on the
protocol's 1920×1080 is Anton's measurement (`/mobium gateb`); if it does not, the target is his call.

