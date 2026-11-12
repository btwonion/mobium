# Benchmarks

How Mobium is measured, and what it measured, on the development laptop and on players' machines (*Players'
results*). Every gate in `PLAN.md` is decided with the numbers on this page. Raw data (every run's CSV, the suite and
gate medians) is in [btwonion/mobium-benchmarks](https://github.com/btwonion/mobium-benchmarks), one folder per round
and players' files in `players/`. A build id in an older round may predate a rewrite of this repository's history:
`grep ^<id> ../mobium-benchmarks/commit-map.txt` gives the current commit.

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
    which three come from Linux sysfs and stay empty elsewhere; also `os` and `driver`, as in
    `/mobium benchmark` below, and `throttled_frames`: the frames during which the game capped its own frame rate,
    which it does at 10 FPS while an exclusive-fullscreen window is minimized or not focused (`FramerateLimitTracker`).
    A run with such frames warns in chat and does not count. A `summary.csv` with older columns is renamed to
    `summary-before-<timestamp>.csv`. The suite and gate files end with `# throttled_runs,<n>` (runs with such frames;
    the medians include them) and `# build,<commit>` lines.
  `setup` is detected when omitted: `sodium` or `vanilla`, `+iris` if a shader pack is active, `+emf` if Entity Model
  Features is installed, `+fa` if a resource pack with "fresh" in its id is enabled. Before it starts, the run warns in chat about anything that breaks the protocol: a window other than
  1920×1080, battery power, a low-power profile, or a setup other than the instance's (below). `/mobium stop` aborts a
  run.
- **`/mobium suite [seconds] [runs] [scenes…]`** — runs a whole setup unattended (defaults: 60 s, 3 runs, all seven
  scenes). For each scene it builds the scene through the integrated server (singleplayer only; no cheats needed),
  waits at least 10 s and until section rebuilds, client light updates and server ticks have calmed down, then
  records `runs` runs. It rewrites `mobium-bench/suite-<timestamp>.csv` with the medians after every run (quitting
  halfway keeps what was measured) and prints them at the end. It refuses to start when the instance was made for
  another setup than the active one (see *Prism instances*).
- **`/mobium gatea [seconds] [runs]`** — Gate A: builds `storage_room` and records it with the static cache off and
  on, alternating (defaults: 60 s, 3 runs each), then prints the speed-up against the 1.5× target and writes
  `mobium-bench/gatea-<timestamp>.csv`. It compares the whole static cache and stays useful as a before/after
  number.
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
- **`/mobium gatec [seconds] [runs] [scene]`** — Gate C: records a scene with Module 2 off and on, alternating
  (defaults: 60 s, 3 runs each, `mob_pen_live`; about 7 minutes), then prints the entity CPU work (`prepare` +
  `shadow_prepare`) without over with Module 2 (target at least 1.5× in setup D) and the frame time with over without
  (must not be slower), and writes `mobium-bench/gatec-<timestamp>.csv`. The targets are Gate C's, so they apply to
  `mob_pen_live` only: in another scene (`/mobium gatec 60 3 item_drops`) it prints and writes the same two numbers
  without a verdict, and the file's `scene` column names the scene. Like the other gates, it switches Module 2 on for
  the plan.
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
  (`docs/vanilla-notes.md` §19). It warns about battery power and low-power profiles but not about the window size.
  It refuses outside a classic superflat overworld, without the pack, and when neither module can run. In dev
  environments `/mobium benchmark <seconds>` shortens the runs (`self_check.py --mode benchmark` uses 5 s).
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
See `docs/vanilla-notes.md` §0 for the stages.

What the shadow pass draws is up to the shader pack. Complementary Reimagined r5.9.3 at its defaults, as B and D run it
(`ENTITY_SHADOW` 1, also in its HIGH to ULTRA profiles), sets `shadowBlockEntities = false` in `shaders.properties`:
entities cast shadows, block entities do not, so `shadow_tessellate_block_entities` reads 0 there (0.000 ms in the
self-check's gallery, with the whole shadow pass at 2.0–2.8 ms). Its POTATO to MEDIUM profiles
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
set. The light one is MakeUp - Ultra Fast 9.6 (Modrinth `makeup-ultra-fast-shaders`) at its defaults: shadows on,
and unlike Complementary its shadow pass draws block entities too, so it covers cached objects in Iris's shadow pass
(`docs/vanilla-notes.md` §24). It is in no setup and no self-check yet.

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
| `item_drops` | About 1,070 dropped bamboo items covering the ground in view (an item every 0.6 blocks, within 22 blocks), stacks of 1, 4, 16 and 64 in turn, so each is drawn 1, 2, 2 or 5 times; they never merge, despawn or get picked up | `/function mobium-bench:item_drops` |
| `control` | Empty superflat, nothing to accelerate | `/function mobium-bench:control` |

Every function switches you to spectator mode, teleports you to the scene's camera position and rotation, and builds
the scene two seconds later in the same place (each scene replaces the previous one). Wait for "Scene … is ready".

### Running a round unattended

`python3 benchmark/bench_round.py` runs setups A–D one after another without anyone at the keyboard (about three and
a half hours; `A D` for some setups, `--dry-run` to see the plan, `--quick` for a five-minute smoke test). Before it
starts it checks mains power, an external monitor, that every instance has the bench kit's Mobium jar and that none of
them is running. For each setup it writes the plan (`PLANS` in the script: `/mobium suite`, `/mobium stages` and
`/mobium gatec 60 3 item_drops` in A–D, `/mobium gatec` in A, C and D, and in A also
`/mobium stages 60 3 display_build`) to the instance's `mobium-bench/autorun` and starts the instance with Prism's
command line straight into the world. The game opens on the display the desktop chooses (the laptop's own screen);
as soon as it has started, before the world loads, Mobium (`bench/BenchAutorun.kt`) moves the fullscreen window to the
monitor whose name contains `--display` (default `AOC`) at 1920×1080, and checks it again before the first command.
Then it runs the commands in turn, writes `mobium-bench/autorun-done` (what it did, the window and display it measured
on, and when the window lost or regained focus) and closes the game. The script prints each focus change with the
keyboard or mouse input around it, from GNOME's idle monitor (`org.gnome.Mutter.IdleMonitor`, polled every 5 s): without
input a program took the focus, with input someone at the computer. Afterwards the script copies the round's files into
`<yyyy-mm-dd>-<machine>/<setup>/` of the data repository (`-<build>` appended when an earlier round of the day is
there), prints the medians, and commits and pushes the round. Runs with `throttled_frames` are named after each setup,
at the end and in the commit message; such a setup has to run again (`bench_round.py B`). Nobody may use the computer
while it runs: the game owns the monitor, anything else running costs frame time, and a click anywhere takes the focus
from the game, which then runs at 10 FPS.

The data repository is [btwonion/mobium-benchmarks](https://github.com/btwonion/mobium-benchmarks); the raw-data
links below point into it. Its checkout sits next to this one (`../mobium-benchmarks`, or `--data`); the script
clones it when missing and pulls it before a round. If the commit fails at the end (for example
because the signing key is locked), the script prints the commands to finish by hand.

### Running a setup by hand

1. Start the setup's Prism instance on mains power, put the fullscreen window on a 1920×1080 monitor and open the
   "Mobium Bench" world.
2. `/mobium suite`. Check chat for warnings before you step away, then leave the computer alone until chat shows the
   medians (about 26 minutes). The HUD stays hidden until the very end: the last scene, `control`, is an empty field
   and looks finished, but it records for another three minutes. The result for a scene and setup is the median of
   the three runs.
3. Setup A only: `/mobium gateb` for Gate B (about 16 minutes); `/mobium gatea` gives the cache's speed-up in
   `storage_room` (about 7 minutes).
4. Do this for all four setups, then copy each instance's `minecraft/mobium-bench/` folder into
   `<yyyy-mm-dd>-<machine>/<setup>/` of the data repository (per-run CSVs gzipped), commit and push it there, and
   add the results to this page, or hand the folders to Claude Code.

By hand instead: `/function mobium-bench:<scene>`, wait for "Scene <scene> is ready" plus 10 seconds, then
`/mobium bench <scene> 60` three times without touching anything.

## Latest results

Build **a484d88**, unattended (`bench_round.py`), fullscreen 1920×1080 on the AOC monitor, mains power, uncapped,
VSync off; the static cache and Module 2 on, Module 2's expand pass in the vertex stage in A and C and as a compute
dispatch in B and D (shader pack), as `ExpandPass.stage` chooses. Setup B is from build b264421, which draws the same
and only adds `throttled_frames` to the bench; B's runs on a484d88 do not count, as its window lost focus halfway
(*Measurement pitfalls*). Machine: Lenovo ThinkPad X9-14 Gen 1, Intel Core Ultra 7 258V, Intel Arc 140V iGPU (`xe`,
Mesa 26.2, OpenGL 4.6), 32 GB, Fedora 44. Raw data:
[`2026-11-12-thinkpad-x9/`](https://github.com/btwonion/mobium-benchmarks/tree/main/2026-11-12-thinkpad-x9/) (A, C,
D) and
[`2026-11-12-thinkpad-x9-b264421/`](https://github.com/btwonion/mobium-benchmarks/tree/main/2026-11-12-thinkpad-x9-b264421/)
(B).

### Module 2 off and on, same session

`/mobium gatec` and `/mobium gatec 60 3 item_drops` (Module 2 off and on in turn, medians of three 60-second runs;
*CPU work* is `prepare` + `shadow_prepare`):

| `mob_pen_live` | without Module 2: frame / CPU work | with: frame / CPU work | frame | CPU work |
| --- | --- | --- | --- | --- |
| A (vertex stage) | 3.49 / 2.01 ms (287 FPS) | 3.26 / 1.10 ms (307 FPS) | 1.07× faster | 1.82× lower |
| C (vertex stage) | 9.93 / 5.97 ms (101 FPS) | 7.34 / 3.84 ms (136 FPS) | 1.35× faster | 1.55× lower |
| D (compute) | 73.35 / 17.80 ms (13.6 FPS) | 61.16 / 7.69 ms (16.4 FPS) | 1.20× faster | 2.31× lower |

| `item_drops` | without Module 2: frame / CPU work | with: frame / CPU work | frame | CPU work |
| --- | --- | --- | --- | --- |
| A (vertex stage) | 12.02 / 8.77 ms (83 FPS) | 4.18 / 0.52 ms (239 FPS) | 2.88× faster | 16.9× lower |
| B (compute) | 85.82 / 46.38 ms (11.7 FPS) | 38.62 / 0.83 ms (25.9 FPS) | 2.22× faster | 55.6× lower |
| C (vertex stage) | 13.39 / 9.80 ms (75 FPS) | 4.19 / 0.48 ms (239 FPS) | 3.20× faster | 20.6× lower |
| D (compute) | 104.92 / 49.74 ms (9.5 FPS) | 61.13 / 0.98 ms (16.4 FPS) | 1.72× faster | 50.6× lower |

- **In A's mob pen the frame gains less than the CPU saves:** both sides ran at 700 MHz, the CPU work fell by 0.91 ms
  and the frame by 0.23 ms, so the frame with Module 2 waits for the GPU. The CPU saving is the same in every session,
  the frame follows the clock (*Frames between sessions follow the GPU clock*).
- **With a shader pack the items are drawn twice:** Iris's shadow pass falls from 25.8 to 4.0 ms in B and from 27.7 to
  4.4 ms in D, so most of the CPU work Module 2 takes is there.
- **Extract and submit read higher with Module 2 on in `item_drops`**, by 0.5–0.6 ms in A and C and by 1.2–1.6 ms in B
  and D (submit 1.5–1.8 → 2.1–2.4 ms, extract 0.4–0.5 → 0.6–1.2 ms), as in the dev client (*Dropped items*). The
  frames include it.

`/mobium stages` (compute and the vertex stage in turn, three 60-second runs each; ms per frame per run, the GPU clock
in MHz in brackets, and the ratio of the medians):

| Scene, setup | compute | vertex stage | vertex / compute |
| --- | --- | --- | --- |
| `mob_pen_live`, A | 3.37 (700), 3.39 (700), 3.03 (810) | 3.25 (700), 2.93 (845), 2.60 (1065) | 0.868× |
| `display_build`, A | 1.90 (775), 1.75 (953), 1.66 (1111) | 1.56 (1086), 1.62 (1021), 1.72 (895) | 0.927× |
| `mob_pen_live`, C | 7.69 (991), 7.64 (933), 7.62 (928) | 7.37 (847), 7.43 (900), 7.37 (843) | 0.965× |
| `mob_pen_live`, B (shader pack) | 29.76 (1394), 30.15 (1390), 28.78 (1472) | 29.99 (1445), 30.20 (1449), 31.94 (1364) | 1.015× |
| `mob_pen_live`, D (shader pack) | 56.60 (761), 62.35 (632), 53.49 (796) | 59.09 (707), 55.55 (817), 59.75 (722) | 1.044× |

- Without a shader pack the vertex stage is faster at the same or a lower GPU clock (A's mob pens by 0.12–0.14 ms at
  700 MHz, C's by 0.2–0.3 ms at 843–900 against 928–991 MHz, `display_build` by 0.03–0.10 ms): the 0.2 ms of GPU time
  per frame of *Module 2's fixed cost* below. The rest of A's ratio is the clock, which rose during the vertex
  stage's runs.
- With a shader pack the two are equal: the pairs disagree in sign and each run follows the GPU clock (D: compute
  53.5 ms at 796 MHz and 62.4 ms at 632 MHz, the vertex stage 55.6 ms at 817 MHz). So `ExpandPass.stage` keeps compute
  there.

### Suites

Median of three 60-second runs per scene and setup, both modules on, ms per frame; extract, submit, pose and
tessellate add entities and block entities, *Shadow* is Iris's whole shadow pass.

| Scene | Setup | Avg FPS | 1% low | Avg ms | p95 ms | Extract | Submit | Pose | Tessellate | Prepare | Draw | Shadow | Shadow prepare | GPU MHz |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| mob_pen_static | A | 301 | 155 | 3.32 | 4.66 | 0.60 | 0.25 | 0.09 | 0.56 | 1.23 | 0.02 | 0.00 | 0.00 | 700–743 |
| mob_pen_live | A | 303 | 161 | 3.30 | 4.64 | 0.66 | 0.27 | 0.10 | 0.60 | 1.23 | 0.03 | 0.00 | 0.00 | 700–770 |
| storage_room | A | 573 | 270 | 1.74 | 2.51 | 0.13 | 0.00 | 0.00 | 0.00 | 0.01 | 0.05 | 0.00 | 0.00 | 868–911 |
| map_wall | A | 225 | 98 | 4.44 | 5.06 | 0.93 | 0.50 | 0.00 | 0.00 | 2.01 | 0.51 | 0.00 | 0.00 | 1515–1586 |
| display_build | A | 515 | 242 | 1.94 | 2.83 | 0.60 | 0.12 | 0.00 | 0.01 | 0.51 | 0.03 | 0.00 | 0.00 | 700 |
| item_drops | A | 226 | 104 | 4.43 | 6.88 | 0.61 | 2.05 | 0.00 | 0.00 | 0.50 | 0.01 | 0.00 | 0.00 | 700–750 |
| control | A | 767 | 325 | 1.30 | 1.88 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 894–952 |
| mob_pen_static | B | 36 | 24 | 28.02 | 37.28 | 1.02 | 0.41 | 0.11 | 0.59 | 1.01 | 0.06 | 1.69 | 0.39 | 1640–1745 |
| mob_pen_live | B | 34 | 23 | 29.20 | 38.30 | 0.97 | 0.45 | 0.12 | 0.64 | 1.09 | 0.05 | 1.73 | 0.38 | 1240–1519 |
| storage_room | B | 38 | 30 | 26.13 | 31.47 | 0.27 | 0.00 | 0.00 | 0.00 | 0.02 | 0.20 | 0.41 | 0.00 | 1355–1509 |
| map_wall | B | 27 | 15 | 37.46 | 51.09 | 1.87 | 0.85 | 0.00 | 0.00 | 3.32 | 0.92 | 2.87 | 0.98 | 838–1264 |
| display_build | B | 37 | 22 | 27.23 | 39.65 | 1.01 | 0.31 | 0.00 | 0.01 | 0.27 | 0.09 | 2.52 | 0.72 | 1006–1541 |
| item_drops | B | 25 | 16 | 39.48 | 58.77 | 0.83 | 2.15 | 0.00 | 0.00 | 0.42 | 0.03 | 3.78 | 0.38 | 1321–1597 |
| control | B | 46 | 33 | 21.98 | 28.00 | 0.01 | 0.00 | 0.00 | 0.00 | 0.01 | 0.00 | 0.39 | 0.00 | 1322–1550 |
| mob_pen_static | C | 143 | 76 | 6.98 | 11.10 | 0.83 | 1.20 | 0.17 | 3.19 | 3.67 | 0.02 | 0.00 | 0.00 | 1080–1235 |
| mob_pen_live | C | 138 | 75 | 7.26 | 11.42 | 0.84 | 1.26 | 0.18 | 3.39 | 3.89 | 0.02 | 0.00 | 0.00 | 891–1151 |
| storage_room | C | 711 | 308 | 1.41 | 1.95 | 0.11 | 0.00 | 0.00 | 0.00 | 0.01 | 0.05 | 0.00 | 0.00 | 992–1237 |
| map_wall | C | 246 | 112 | 4.06 | 4.58 | 0.85 | 0.46 | 0.00 | 0.00 | 1.83 | 0.50 | 0.00 | 0.00 | 1287–1361 |
| display_build | C | 631 | 261 | 1.58 | 2.20 | 0.63 | 0.12 | 0.00 | 0.01 | 0.31 | 0.03 | 0.00 | 0.00 | 881–1057 |
| item_drops | C | 239 | 34 | 4.18 | 6.81 | 0.62 | 1.41 | 0.00 | 0.00 | 0.58 | 0.01 | 0.00 | 0.00 | 625–990 |
| control | C | 817 | 339 | 1.22 | 1.58 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 934–982 |
| mob_pen_static | D | 16 | 14 | 63.19 | 69.53 | 1.46 | 3.64 | 0.24 | 5.19 | 6.25 | 0.05 | 3.15 | 1.09 | 625–722 |
| mob_pen_live | D | 17 | 14 | 58.83 | 68.30 | 1.21 | 3.46 | 0.23 | 5.14 | 6.15 | 0.05 | 3.07 | 1.08 | 664–679 |
| storage_room | D | 32 | 26 | 30.93 | 35.86 | 0.29 | 0.00 | 0.00 | 0.00 | 0.03 | 0.22 | 0.47 | 0.01 | 1051–1095 |
| map_wall | D | 19 | 14 | 52.63 | 57.07 | 2.51 | 1.23 | 0.00 | 0.00 | 4.16 | 0.83 | 3.52 | 1.19 | 600 |
| display_build | D | 34 | 21 | 29.05 | 43.95 | 1.10 | 0.41 | 0.00 | 0.01 | 0.31 | 0.10 | 2.75 | 0.86 | 1253–1395 |
| item_drops | D | 17 | 14 | 59.98 | 68.76 | 1.12 | 2.39 | 0.00 | 0.00 | 0.53 | 0.03 | 4.18 | 0.48 | 661–919 |
| control | D | 36 | 31 | 27.44 | 31.00 | 0.01 | 0.00 | 0.00 | 0.00 | 0.01 | 0.00 | 0.43 | 0.01 | 1025–1082 |

C's `item_drops` row: two of its three runs lost the window's focus for a moment (29 and 217 frames at 100 ms), so its
1% low does not count and its frame is about 0.2 ms high; the `item_drops` table above has clean runs of the scene.
One of B's `map_wall` runs has 27 such frames; it is not the median.
Without Mobium these scenes were (other sessions): `storage_room` in A 3.33 ms (300 FPS; with the cache 1.71 ms, 586
FPS), the mob pens and `item_drops` as in the first columns of the tables above. Frames from different sessions
follow the GPU clock more than anything else (*Findings*), so a speed-up is only quoted from one session.

**The README's table** comes from these numbers: `storage_room` 300 → 586 FPS (setup A, two earlier sessions; this
round does not measure it without Mobium), `item_drops` from the table above (A, B) and the three mob pens from the
`mob_pen_live` table above (A, C, D), as average FPS. A's mob pen gained 1.19× at 750 MHz on build 8d26a26
([raw data](https://github.com/btwonion/mobium-benchmarks/tree/main/2026-10-27-thinkpad-x9-8d26a26/)) with the same CPU
saving, hence the README's range (*Frames between sessions follow the GPU clock*).

## Players' results

`/mobium benchmark` files players sent (issue form, label `benchmark`; Discord), newest first. Each is one session:
Mobium off, on, on and off, 30 s per run, the median of the two runs in each state; the gain is the frame time off
over on (`<scene>_speedup`). Raw files:
[`players/`](https://github.com/btwonion/mobium-benchmarks/tree/main/players/).

| File | Machine | `storage_room` off → on | `mob_pen_live` off → on |
| --- | --- | --- | --- |
| [#32](https://github.com/btwonion/mobium/issues/32) | AMD Ryzen 7 7800X3D, Nvidia GeForce RTX 4070 Ti; desktop, 2560×1384 window, Fancy, Mod Menu | 248 → 2,773 FPS (4.04 → 0.36 ms), **11.2×** | 236 → 713 FPS (4.24 → 1.40 ms), **3.02×** |
| [#31](https://github.com/btwonion/mobium/issues/31) | Intel Core i7-13700HX, Nvidia GeForce RTX 4070 Laptop GPU; Lenovo Legion Pro 5 16IRX8 on mains, `balanced` profile, 2560×1515 window, Custom, no other mods | 111 → 252 FPS (9.05 → 3.96 ms), **2.28×** | 99 → 240 FPS (10.07 → 4.16 ms), **2.42×** |

Both ran a release build on Fabric (`dd7253f`) without Sodium (setup `vanilla`), on OpenGL, without a shader pack or
resource packs, seed `mobium`, both modules active and Module 2's expand pass in the vertex stage. 1% lows: #32 164 →
685 and 169 → 370 FPS, #31 63 → 145 and 51 → 112 FPS. Without Sodium these frames do not compare with *Latest
results*. Module 2's check found no difference on either GPU (`docs/compat.md`, *On players' GPUs*).

- **On these Nvidia GPUs the frame gains what the CPU saves.** In `mob_pen_live` `prepare` fell by 2.90 ms on #32 and
  the frame by 2.83 ms, on #31 by 5.77 and 5.91 ms; on the Arc 140V the frame follows the clock that CPU and GPU share
  (*Frames between sessions follow the GPU clock*).
- **`storage_room`'s gain is the vanilla frame over what the cache leaves.** The cache takes almost all of the CPU
  work (`prepare` 2.97 → 0.003 ms on #32, 5.84 → 0.015 ms on #31), so the gain depends on the rest of the frame: 0.36
  ms on #32, 3.96 ms on #31. Neither file has a `control` run to split that rest.
- **These two files name neither the OS nor the driver:** they record the GPU's renderer string but not `GL_VERSION`,
  and the power state only from Linux sysfs (`bench/MachineState.kt`). So #31 ran Linux; #32's empty power fields mean
  Windows or a Linux desktop without a mains supply or platform profile. Current files have `# os` and `# driver`
  (*Tools*).

## Gates

Every gate also requires zero screenshot differences (self-check and gametests, both loaders). Gates B and C measure
what Mobium can change, without the GPU work that vanilla pays as well.

| Gate | Measured | Definition | Result |
| --- | --- | --- | --- |
| A | Setup A, `/mobium gatea`, chest prototype | `storage_room` at least 1.5× faster with Mobium than without | **1.53×** (3.33 → 2.18 ms) |
| B | Setup A, `/mobium gateb`, build 6fb45dc | What the cached objects cost: `storage_room` with the cache's draw calls skipped, plus those draw calls' CPU time, at most 1.2× `control`; `control` with the cache at most 1.03× without | **1.170×** ((1.247 + 0.041) / 1.101 ms); `control` 1.003× |
| C | Setups C and D, `/mobium gatec`, build b41d342 | In D `mob_pen_live`'s `prepare` + `shadow_prepare` at least 1.5× lower with Module 2; in C and D its frame at most 1.03× the frame without | **D: 1.90× lower** (13.70 → 7.22 ms), frame 0.84× (65.57 → 55.37 ms); **C: frame 0.80×** (9.05 → 7.23 ms) |

**Gate B** leaves out the GPU drawing the 1,000 chests and 300 signs, about 0.30 ms per frame that vanilla pays as
well. The whole `storage_room` frame (1.395× `control`) and the cost of drawing the cached objects (0.29 ms per frame)
are reported without a target; `/mobium gateb` records the extra run without the cache's draw calls for that.

**Gate C** counts the entity CPU work: in D the frame is mostly GPU work, and in C Fresh Animations' animations run
inside `ModelPart.render`, so 3.2 of 5.3 ms of tessellation stay on the CPU without a single vertex written (*What
Module 2 can take* below). Mobium can only take CPU work away; how much of it shows in the frame depends on the GPU.
With Module 2 the iGPU clocked higher (CPU and GPU share one power budget), so the frames gained more than the CPU time
saved. Gate C holds on build a484d88 too (D 2.31× lower CPU work and 1.20× faster, C 1.35× faster; *Latest results*).

## Findings

### Cost split per scene

Vanilla (no Mobium), setups without Fresh Animations or shader packs. What each module can win is bounded by the stages
it removes:

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

`self_check.py fabric --mode profile-mobs` in the dev client (854×480): `mob_pen_live` as drawn, and with
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

### Dropped items

`self_check.py <loader> --mode profile-mobs --scene item_drops` in the dev client (854×480; Sodium, Iris without a
shader pack, EMF and ETF): about 1,070 dropped bamboo items, about 2,700 drawn copies, with Module 2 off, on, and with
every item's vertex writes skipped (the items vanish; the bound for what the CPU can lose). Timer averages in ms:

| Loader | Module 2 | Frame | `extract_entities` | `submit_entities` | `prepare` |
| --- | --- | --- | --- | --- | --- |
| Fabric | off | 16.40 | 0.63 | 1.05 | 12.95 |
| Fabric | on | 4.20 | 0.83 | 1.50 | 0.72 |
| Fabric | no vertices | 2.36 | 0.56 | 1.16 | 0.18 |
| NeoForge | off | 15.98 | 0.77 | 1.23 | 12.24 |
| NeoForge | on | 4.38 | 1.00 | 1.65 | 0.68 |
| NeoForge | no vertices | 2.76 | 0.73 | 1.34 | 0.18 |

- Frames 3.9× (Fabric) and 3.6× (NeoForge) faster; `prepare` 18× lower. At 1920×1080 in the benchmark round
  (*Latest results*) 2.9× (A), 2.2× (B), 3.2× (C) and 1.7× (D) faster, the entity CPU work 17–56× lower; with a shader
  pack most of it is Iris's shadow pass, which draws the items again. Besides the vertex writes Module 2 also
  removes most of their upload and the per-quad builder lookups: vanilla asks for the builder once per quad, and Iris's
  and ETF's hooks on that call cost about as much as the vertices (`docs/vanilla-notes.md` §25).
- What stays on the CPU: the render states (rebuilt each frame, with each item model's bounding box), one submit per
  drawn copy, and the client's own tick of the items, which runs between frames and is outside Mobium's scope.
- Extract and submit read 0.2–0.45 ms higher with Module 2 on (each condition the average of two rounds; in the
  benchmark round 0.5–0.6 ms in A and C and 1.2–1.6 ms with a shader pack). Mobium adds no work there: the CPU runs
  slower while the GPU draws more frames (*Extract and submit follow the CPU's clock*). In this table one of the two
  rounds with Module 2 ran under Java Flight Recorder, which slows the render thread further; `profile-mobs` keeps that
  round out of its averages.

### Module 2's fixed cost: a compute batch of its own

In a full round `display_build`, where Module 2 expands only 17 shields, was 0.15–0.3 ms per frame slower with
Module 2. Measured in the Fabric dev client (1423×800 window, mains power, Mesa
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
  closer to GPU-bound, it shows (`/mobium stages` in *Latest results*).
- **With a shader pack compute's frames are faster**, by 3–14 ms in B and D in every run, although its passes cost the
  GPU 1–2.8 ms more. The batch flushes a dispatch causes submit the frame's first part (shadow pass, terrain) early,
  so the GPU starts on it while the CPU still records; with the pack's 25–35 ms of GPU work per frame that matters more
  than the pass. A `glFlush` after the vertex stage's pass got half of D's difference back (63.7 → 56.3 ms, compute
  50.1), but with Mesa's threaded driver `glFlush` waits for the driver thread: 0.06 ms per frame in A, 2.5 ms in D.
  The dispatch's flushes happen on the driver thread.
- So the expand pass runs in the vertex stage without a shader pack and as a compute dispatch with one
  (`ExpandPass.stage`); `-Dmobium.parts.stage=compute|vertex` overrides that. At 1920×1080 the vertex stage is faster
  without a shader pack and both are equal with one (*Latest results*). Single frames in B and D vary by ±5 ms
  between runs of the same condition in the dev window.

### Iris's shadow pass

With Complementary Reimagined r5.9.3 at its defaults, as setups B and D run it:

- The shadow pass costs 0.43–0.47 ms in `control` (shadow terrain only) and up to 5.3 ms with entities; in D's mob
  pens without Module 2, 5.0–5.3 ms, of which entity tessellation 2.2 ms. Fresh Animations makes the shadow pass's
  tessellation 2.4× as expensive, as it does the main pass's.
- **Cached block entities cost it nothing:** Complementary sets `shadowBlockEntities = false`, so
  `shadow_tessellate_block_entities` is 0.000 ms in every scene and `storage_room`'s shadow pass equals `control`'s.
- **Cached entities do cost it:** in `display_build` the shadow pass prepares the 500 displays again (prepare
  1.9–2.0 ms of a 4.2–4.6 ms pass), which the main pass takes from the cache.

### Frames between sessions follow the GPU clock

The iGPU picks its clock per workload, and CPU and GPU share one power budget. Between sessions, scenes where Mobium
does nothing moved by up to ±18 % with the clock: `control` 1.18× slower in D at 668–766 against 900–942 MHz, 0.85× in C
at 1160–1195 against 904–958 MHz. A's mob pens looked 1.07–1.14× slower with Module 2 across sessions and are
faster with it in one session, by as much as the clock allows: Module 2 lowers their CPU work 1.8–1.9× in every
session, and the frame gained 1.19× at 750 MHz on build 8d26a26 and 1.07× at 700 MHz on a484d88. So speed-ups are
measured within one session, alternating (the gate commands, `/mobium stages`, `/mobium benchmark`), and `summary.csv`
records `gpu_mhz`.

- **A's mob pens with Module 2 are GPU-bound.** In one session their frame followed the clock: 3.25 ms at 700 MHz,
  2.93 ms at 845 MHz, 2.60 ms at 1065 MHz (`/mobium stages`, vertex stage). Without Module 2 it does not (3.50–3.66 ms
  at 750–882 MHz in 8d26a26's `/mobium gatec`): the CPU limits it.
- **So A's gain moves with the clock, not with the build.** Between the rounds on 8d26a26 and a484d88, `/mobium gatec`
  measured the same CPU work with Module 2 (1.12 and 1.10 ms) and without it (2.12 and 2.01 ms) and the same frame
  without it (3.55 and 3.49 ms); the frame with it went from 2.98 to 3.26 ms as the clock went from 750–776 to 700 MHz.
  The expand pass's changes for items add uniform branches for cubes and read the quad's extra word only for items.
  Extract and submit, whose code did not change between the builds, read 2–5 % higher in a484d88's suite, like
  `prepare` (7 %): the CPU ran slower too (next section).
- **The package power limit holds the iGPU's clock down**, which CPU and GPU share. The firmware picks the clock
  (`xe`, GuC SLPC, `power_profile` base) between the efficient frequency (`tile0/gt0/freq0/rpe_freq`, 650 MHz in a
  dev-client session) and 1950 MHz. In the dev client at 1423×800, `item_drops` with Module 2 kept the GPU busy 90–95 %
  of the time (`gtidle/idle_residency_ms`) at only 750–850 MHz, and `xe` named the power limit as the reason in nearly
  every sample (`freq0/throttle/reasons`: `pl1`). So a GPU-bound frame does not get the GPU's top clock while the CPU
  draws power too. The rounds record `gpu_mhz` but not the reason.
- **`display_build` does not get slower with Module 2's items.** The static cache draws all 100 item displays, so Module
  2 takes no item there: `self_check.py fabric --mode expand-cost --scene "display_build items"` (Module 2 off, on
  without its items, on with them, in rotating order) counted 0 item runs and 34 parts (the 17 shields) per frame with
  and without items. The frames of its rounds followed where the render thread ran (next section), and A's 1.80 →
  1.94 ms between the two rounds is the clock (750 against 700 MHz).

### Extract and submit follow the CPU's clock

With Module 2 on, `extract_entities` and `submit_entities` read higher in `item_drops` (*Latest results*: by 0.5–0.6 ms
in A and C, by 1.2–1.6 ms in B and D, and Iris's `shadow_extract` and `shadow_submit` too) and by 5–8 % in A's mob pens.
Mobium runs the same code there with Module 2 off and on: the timers wrap only vanilla's calls, and the one check per
entity (`PartTransform.excludes`) runs in both. What changes is how fast the CPU runs it. The Core Ultra 7 258V has
four P-cores (4.7–4.8 GHz) and four LP E-cores (3.7 GHz, outside the P-cores' L3), and CPU and GPU share the package's
power limit. Measured in the Fabric dev client (854×480 and 1423×800) by sampling the render thread every 2 ms from
outside (its core in `/proc/<pid>/task/<tid>/stat`, that core's `scaling_cur_freq`, `xe`'s clock and throttle reasons):

- **The CPU's clock drops while the GPU works.** With Module 2 the iGPU draws the items at three times the frame rate
  instead of waiting for the CPU, at 1,150–1,500 MHz with `pl1` as the reason it is not higher. In
  `/mobium gatec 10 2 item_drops` with the render thread held on the P-cores, its core ran at 1.8–2.1 GHz with Module 2
  and at 2.5–2.6 GHz without, and the two stages followed the clock: `extract_entities` 0.91 and `submit_entities`
  1.73 ms at 1.8 GHz, 0.75–0.77 and 1.46–1.52 ms at 2.0–2.1 GHz, 0.74–0.79 and 1.39–1.47 ms without Module 2.
- **The scheduler moves the render thread to the E-cores now and then**, where the same work takes about twice as long.
  Linux places threads by capacity (`cpu_capacity` 1005–1024 for the P-cores, 676 for the E-cores), so a thread that
  waits for the GPU for part of each frame can land on an E-core. In `profile-mobs --scene item_drops` at 1423×800,
  without Module 2 the render thread was busy 96–97 % of the time and never ran on an E-core; with it, it waited for the
  GPU (busy 81–84 %) and spent 45–50 % of its time on E-cores, and `extract_entities` read 0.89 against 0.69 ms,
  `submit_entities` 1.53 against 1.37 ms (no vertices: 0.60 and 1.40 ms, on P-cores). With a shader pack the frames show
  two modes: B's `item_drops` with Module 2 has `shadow_extract` at 0.30–0.45 ms in 13 % of its frames and at 0.80–1.05
  ms in the rest, without Module 2 at 0.35–0.50 ms in all. In `display_build` (dev client) the rounds of one condition
  whose render thread spent 21–31 % of its time on E-cores were up to 1.3× slower than one at 4 %. Java Flight
  Recorder's sampling, which stops the thread a thousand times a second, put it on E-cores for 60–84 % of a round.
- **One condition differs between sessions by as much:** C's suite run of `item_drops` with Module 2 had
  `submit_entities` at 1.44 ms, its `/mobium gatec` runs at 2.30 ms, at the same frame time (3.7 ms).

So this is not work Mobium adds, but it is in the frame wherever the CPU limits it (A and C at 1920×1080), and the
speed-ups in *Latest results* include it. On desktops, where CPU and GPU do not share a power limit, the frame gains
what the CPU saves (*Players' results*).

### Slow vanilla `storage_room`

In three of five early sessions, vanilla `storage_room` ran at 15–18 ms per frame for the whole session
instead of 3.3–4.2 ms, while other scenes in the same sessions ran normally. The CPU stages grew only from about 2.3 to
3.4 ms, so about 13 ms per frame were spent waiting; the factor of 5 matches the iGPU's clock range (400–1950 MHz).
The likely cause is the clock dropping when vanilla's per-frame upload of every chest and sign vertex makes CPU and
GPU wait for each other; not confirmed, as no round that records `gpu_mhz` has had a vanilla `storage_room` run. A
baseline in this state does not count.

### Measurement pitfalls

- Instance settings Prism ignores: Java arguments at the end of `instance.cfg` land in its `[UI]` section, and a
  renamed instance folder leaves a duplicate; `benchmark/prism_instances.py` writes `[General]` and uses the folder
  Prism launched last.
- Vanilla's default 120 FPS cap, and its idle frame limit after 60 s without input (the bench handles the second).
- Vanilla's 10 FPS cap while an exclusive-fullscreen window is minimized or not focused, whatever the inactivity
  setting: every frame takes 100 ms and the GPU idles. `throttled_frames` in `summary.csv` counts those frames, the run
  warns in chat, the autorun log notes the focus change and `bench_round.py` prints it with the keyboard or mouse
  input around it. In the rounds on a484d88 and b264421 the window lost the focus three times (B for good, C for half
  a minute, B's rerun for 2 s). None of the likely programs took it: GNOME shows notifications on the laptop's screen
  without taking the focus (a mail notification during A took none), the session never went idle or locked, 1Password
  showed nothing, and no long frame came first (as GNOME's "not responding" dialog would need). In B's rerun the
  Claude desktop app's window received the focus in that second, with no session activity before it; the other two
  left no trace in the journal or the apps' logs. The laptop's own screen stays on during a round, with its windows
  on it, so a click there takes the focus.
- Battery power: vanilla `storage_room` 9 → 42 ms on the dev laptop (gametest window); the bench
  warns in chat.
- The first run after a scene is built (the suite waits until the world has settled).
- The slow state of vanilla `storage_room` above.
- Zombies in the sun (fixed in the datapack), packs that are installed but not selected, and quitting during
  `control` (all caught by the instances and the suite now).
- A bench kit built from an outdated checkout measures the previous build. The jar names its commit (`build` in
  `summary.csv`), and `benchmark/prism_instances.py` refuses a stale kit or a checkout behind its upstream.
- Day-to-day speed of the laptop (`control` 1.02–1.11 ms in A over three sessions): compare within one session.
