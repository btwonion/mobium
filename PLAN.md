# Mobium — Implementation Plan

This file is the build plan for Mobium. It is written for the coding agent (Claude Code) working in this
repository and for Anton, who runs the game. Read it fully before writing code, work through the milestones in
order, and tick the checkboxes in this file as tasks land.

Design doc (background and reasoning): https://claude.ai/code/artifact/0475447c-94e1-45bc-acb5-2e882690c1e4
Everything needed to build is repeated here; when this file and the design doc disagree, this file wins.

---

## Current state (updated 25 Oct 2026)

Read this first when picking the work up in a new session.

- **Repository:** `btwonion/mobium` on GitHub. #1–#8 and #10–#12 are merged; `main` has M0–M5 (#7, `m5-static-cache`,
  merged on 22 Oct 2026 at Anton's request once Gate B had passed and the checks were green), the release prep (#8),
  the new icon (#10) and the beta2 work (#11 post-release, #12 cache follow-ups and shadow-pass timers, both merged on
  23 Oct 2026). From M6 on: one branch from `main` per milestone, one PR against `main`; merging is Anton's call.
- **CI:** green. The Fabric gametests run under `xvfb-run` with SDL forced onto Mesa's EGL (`SDL_VIDEO_FORCE_EGL=1`,
  `LIBGL_ALWAYS_SOFTWARE=1`), because Xvfb's GLX has no sRGB-capable visual (comments in
  `.github/workflows/build-commit.yml`); `release.yml` does the same. If CI hangs or fails, read the job log first
  (`gh run view --log-failed`).
- **Done:** M0–M5. Gate A passed (1.53×), **Gate B passed (1.17×**, Anton's `/mobium gateb` on 21 Oct, build 6fb45dc;
  §M5). Module 1, the static cache, caches chests, signs (with text), skulls, decorated pots, shelves, item frames,
  armor stands without armor and `FIXED` display entities; on by default. Self-check on both loaders: every
  Mobium-vs-vanilla comparison 0 changed pixels (Complementary on Fabric: within the pack's own noise).
- **Released: `0.1.0-beta1`** on 22 Oct 2026 (`release.yml` run 39831233395, Fabric/Quilt and NeoForge, beta on
  Modrinth `mobium` = `8i5oedw8` and CurseForge `mobium-perf` = `1759818`, GitHub pre-releases
  `v0.1.0-beta1-26.3+fabric` / `+neoforge`). The first run failed before building: the runner image's apt source for
  `packages.microsoft.com` answered 403 during an Actions outage; the re-run passed unchanged (#9, a workaround, was
  closed unmerged). The beta1 jars carry the old cube icon; the project icons are set on Modrinth and CurseForge by
  hand. Never run `release.yml` or `releaseMod` yourself.
- **Next: `0.1.0-beta2`** (version from `gradle.properties`, `mod.beta=2`; `changelog.md` collects its notes): the
  Module 1 follow-ups below plus shadow-pass timers (`Sections.SHADOW…`, `compat/iris/ShadowRendererMixin`), so that
  Anton's next benchmark round on beta2 also measures what Iris's shadow pass costs, and fixes for whatever beta1
  testers report (`.github/ISSUE_TEMPLATE/bug_report.yml`; none so far). The work is on `main` (#11, #12, merged on 23
  Oct, build `a6997e5`); the release prep is branch `release-0.1.0-beta2`, which also takes the benchmark round's
  results (#13). Self-check on both loaders passed on 23 Oct (Fabric 74/74 with Complementary and Fresh Animations,
  NeoForge 67/67; every Mobium-vs-vanilla comparison 0 changed pixels; 90 light updates 56 blocks from the gallery
  added no full checks, and the sweep pruned the 8 sections kept back to 0). The self-check now switches vsync off:
  with it the dev window behind other windows ran at ~17 FPS on Wayland, which paused the armor stands (at 17 FPS the
  60 frames after a capture last 3.5 s, and their state still changed in them) and put a client tick between most
  picture pairs. **Benchmark round done** (A–C 23 Oct, C and D 25 Oct, build `a6997e5`; `docs/benchmarks.md`
  "23–25 Oct 2026"): nothing slower than on 21–22 Oct beyond the usual spread, the shadow pass timed in B and D.
  #13 (release prep, results) merged on 25 Oct 2026. Left: Anton's visual checks of #12 (§10), then `release.yml`.
- **Now M6** (Module 2, GPU part transform → Gate C), branch `m6-part-transform`. **Gate C redefined a second time
  with Anton on 25 Oct 2026** (§M6): in D, `prepare` + `shadow_prepare` of `mob_pen_live` at least 1.5× lower with
  Module 2; in C and D its frame no slower (≤ 1.03×). Basis: the dev client with the models' vertex writes skipped
  (`python3 scripts/self_check.py fabric --mode profile-mobs`, `docs/benchmarks.md` *Gate C: what M6 can reach*):
  in C (Fresh Animations) 3.2 of 5.3 ms of tessellation stay without any vertices, because EMF animates inside
  `ModelPart.render`, so 1.5× in C was out of reach (at most about 1.35×); in D `prepare` + `shadow_prepare` drop from
  16.5 to 5.4 ms (at most 3.1×). So Module 2 needs Iris's extended format and the shadow pass in its first version,
  and Fresh Animations' parts are EMF's own (`EMFModelPart.compile`, `EMFCube`), not vanilla cubes
  (`docs/vanilla-notes.md` §16).
- **Follow-ups for Module 1** (from the #7 review, none affects what is drawn; the first four done for beta2 on
  `beta2-cache-followups`, 23 Oct 2026):
  - [x] one light epoch for the whole level sent every cached object through the full check after any light update.
    Now `cache/LightStamps.kt`: the epoch of each section's last light update; an entry keeps the epoch it last read
    its light at and reads it again only if one of its sections was updated after that, so an update elsewhere costs
    one or two lookups per object and no full check.
  - [x] the per-section light versions only grew. The 600-frame sweep now forgets the sections whose last update every
    remaining entry has seen (a stamp cannot repeat after pruning, unlike a per-section counter).
  - [x] the sweep, the budget eviction and `removeType` used Kotlin collection operators and lambdas; now loops, map
    iterators and reused arrays (`MemoryBudget.selectEvictions` sorts indirectly with fastutil's `DoubleArrays`).
  - [x] entity render states referenced their entity. Vanilla makes states per frame and clears the level's list after
    submitting; what keeps states longer is Iris's shadow renderer (until its next pass), the player's avatar state and
    item pickup particles (`docs/vanilla-notes.md` §15). Only cached types outside the shadow pass get the link now.
  - `display_build` at 1920×1080 is GPU-bound on this iGPU: entities cached save 0.4–2 ms of CPU but no frame time
    there; the per-entity check costs about 0.3 µs per display.
  - Iris's shadow pass draws cached objects through vanilla (the cache leaves that pass alone). Complementary at its
    defaults (`ENTITY_SHADOW` 1, as in B and D) leaves block entities out of the shadow map (`shadowBlockEntities =
    false`; `shadow_tessellate_block_entities` 0.000 ms in every scene of the 23–25 Oct round), so for block entities
    it costs nothing; a pack or setting that shadows them tessellates every cached chest once more per frame. Cached
    entities do cost it: in `display_build` the shadow pass prepares the 500 displays again (prepare 1.9–2.0 ms of a
    4.2–4.6 ms shadow pass, against 0.4 ms in the main pass with the cache). Candidate: draw cached entities in the
    shadow pass from the cache too (needs the shadow pass's vertex format and render types; check with the self-check's
    shader pack comparisons).
  - F3 `mobium:modules`: vanilla puts the third of its lines, the static cache line, into the right column, and that
    line is wider than the room next to the left column's first line, so the two overlap (seen in the self-check's
    `f3-modules.png`; beta2's "sections with light updates kept" made it longer). The numbers at its end stay readable.
    Shorter lines, or a line group of its own.
  - later: cache armor (keep submit orders), renderer API item meshes (`ExtendedItemSubmit`).
- **A/B key:** a physical key position (SDL scancode, the key right of P): `+` on Anton's German keyboard, `]` on a
  US one; chat and action-bar messages name the bound key.
- **Known issue (not Mobium):** Iris 1.11.7 on NeoForge crashes with Complementary Reimagined r5.9.3 in
  `CenterDepthSampler` ("Missing uniform InSampler"), also with Mobium switched off
  (`python3 scripts/self_check.py neoforge --mode iris-without-mobium`); the self-check skips the shader pack there.
- **Working in a worktree:** the gitignored dev run folders are not there. Copy `versions/26.3-fabric/run/` and `run/`
  (`options.txt`, `config`, `saves`, `shaderpacks`, `resourcepacks`), `versions/26.3-fabric/build/bench-world` and
  `versions/26.3-fabric/build/bench-packs` from the main checkout before running the self-check or the Prism script.
- **Local test assets (gitignored dev run folders):** `shaderpacks/ComplementaryReimagined_r5.9.3.zip` and
  `resourcepacks/FreshAnimations_v1.10.3.zip` in `versions/26.3-fabric/run/` and `run/`; the self-check uses them when
  present.
- **Rebuilding the benchmark instances after code changes:** `git pull` first, then
  `./gradlew :26.3-fabric:benchKit && python3 benchmark/prism_instances.py`. The script refuses a kit that was not
  built from the checkout's commit and a checkout behind its upstream (the 21 Oct round was lost to that), prints the
  installed build, finds instances whose folder Prism renamed ("Mobium Bench A (Sodium)", "Mobium Bench D (all)"),
  refreshes the datapack in their worlds, installs and selects Complementary Reimagined r5.9.3 (B, D) and Fresh
  Animations 1.11.1 (C, D) and sets `-Dmobium.bench.expect` so the suite refuses the wrong setup. Every `summary.csv`
  row names its build (`build`). Until 23 Oct the script appended `JvmArgs` to the end of `instance.cfg`, which put it
  into Prism's `[UI]` section, where Prism ignores it (no expected-setup check in the rounds of 21–22 Oct), and a run
  on 22 Oct made new `mobium-bench-a`/`-d` folders next to the renamed ones and updated those from then on (moved to
  `PrismLauncher/mobium-bench-duplicates-20261023/`). Now it writes `[General]` and, when several instances carry
  one name, uses the one Prism launched last and names the others. A new world comes from `./gradlew :26.3-fabric:runBenchWorld` followed by deleting
  `saves/Mobium Bench` in the instances.
- **Measurement pitfalls found so far** (details in `docs/benchmarks.md`):
  - the default 120 FPS cap and vanilla's idle frame limit (the bench handles the second);
  - battery power and low-power profiles, and windows other than 1920×1080 (the bench warns);
  - the first run after a scene build (the suite waits for the world to settle);
  - packs installed but not selected, zombies burning in the mob pens, quitting during `control` (A's suite on 21 Oct
    has two `control` runs);
  - a bench kit from an outdated checkout (now refused);
  - day-to-day speed of the laptop (`control` 1.02–1.11 ms in A): compare within one session, as the gates do;
  - vanilla `storage_room` sometimes running 5× slower for a whole session (unexplained; not seen since `gpu_mhz` is
    recorded, because no round since has a vanilla `storage_room` run).

---

## 0. How to work in this repository

1. **One milestone at a time.** Each milestone is one branch and one PR. Do not start a milestone before the
   previous one's acceptance criteria are met (gates in §7 are hard stops).
2. **Never guess the 26.3 API.** Minecraft 26.x is unobfuscated, so the source you see after Loom/ModDevGradle
   decompiles it uses Mojang's real names. Look up every class, method and field before you hook it, and record
   what you learned in `docs/vanilla-notes.md` (milestone M1). Names in this plan marked *(verify)* are
   expectations, not facts.
3. **You cannot see the game.** Claude Code has no GPU and cannot judge visuals or FPS. Every PR that changes
   rendering ends with a **"How to verify"** section for Anton: the exact Gradle task, the benchmark scene,
   key presses, and what correct and broken look like.
4. **Vanilla first, fast path second.** Every feature has a config switch, a per-type switch and a runtime
   fallback to the unmodified vanilla path. A new hook ships disabled until its screenshot tests pass.
5. **Follow the house structure.** Layout, build scripts, Stonecutter usage, config handling, publishing and CI
   copy `btwonion/autodrop` (single root project) and `btwonion/better-boat-movement` (newer conventions).
   When unsure how something is done, look at those repositories first.

---

## 1. What Mobium is

Mobium makes entities and block entities cheap to draw by keeping their geometry on the GPU and sending only
small per-frame updates, while vanilla, EMF and Iris still decide how everything looks.

**The one rule:** Mobium may change *how* geometry reaches the GPU, never *what* ends up on screen.

| Module | What it does | Default |
| --- | --- | --- |
| 1 · Static cache | Block entities and static entities (closed chests, signs, beds, shelves, item frames, armor stands, display entities) are captured once and redrawn from GPU buffers until they change | On |
| 2 · GPU part transform | Animated models keep CPU posing (`setupAnim`, EMF), but the CPU writes one ~80-byte record per model part and a compute shader expands records into vertices | On where compute exists |
| 3 · Animation LOD | Far or tiny-on-screen mobs reuse last frame's part records; root transform still updates every frame | Off |

**Must-have compatibility:** Iris shader packs; EMF/ETF including the Fresh Animations resource pack.
**Also expected to coexist with:** Sodium 0.9, EntityCulling, ModMenu.
**Non-goals:** game-logic changes (client-only mod), terrain rendering, occlusion culling, hiding/capping entities,
requiring shader packs to be patched.

**Headline targets** (confirmed or revised after M2 profiling):
- Storage room full of chests and signs: what the cached objects cost Mobium at most 0.2× an empty frame, i.e.
  `storage_room` without drawing the cached objects (the GPU cost of drawing them, which vanilla pays too, is reported
  separately) at most 1.2× `control` (Gate B, redefined by Anton on 20 Oct 2026).
- 500-mob scene with Sodium + Iris + Fresh Animations: at least 1.5× faster.
- Zero visible differences against the vanilla path in the screenshot suite.

---

## 2. Decisions already made

| Topic | Decision |
| --- | --- |
| Name / mod id | Mobium / `mobium` |
| Maven group / package | `dev.nyon` / `dev.nyon.mobium` (mixins in `dev.nyon.mobium.mixins`) |
| Author | `btwonion` |
| Repository | `btwonion/mobium` *(assumed; change `mod.repo` if different)* |
| License | GPL-3.0 (`modLicense = "GNU General Public License v3.0"`) |
| Loaders | Fabric (+ Quilt) and NeoForge from day one, one source tree via Stonecutter + modstitch |
| First targets | `26.3-fabric`, `26.3-neoforge`; `26.4-*` added in M7 |
| Side | Client only (`"environment": "client"`, NeoForge dependency `side = "CLIENT"`) |
| Language | Kotlin for all logic, Java only for mixins |
| Java | 25 (as 26.x requires; same as CI) |
| Config storage | `dev.nyon:konfig` (versioned JSON at `config/mobium.json`) |
| Config UI | Vanilla-widget screen like autodrop (ModMenu on Fabric, `IConfigScreenFactory` on NeoForge) plus a page in Sodium's video settings through the Sodium Config API when Sodium is installed |
| Translations | `en_us.json` and `de_de.json`, always with identical keys |

---

## 3. Repository layout (target state)

```
mobium/
├── .editorconfig                       # copy from autodrop (4 spaces, 120 columns)
├── .gitignore                          # .idea .gradle build run .kotlin
├── .github/workflows/
│   ├── build-commit.yml                # matrix: 26.3 × fabric/neoforge; build + unit tests + Fabric client gametests
│   ├── release.yml                     # manual dispatch, copy from autodrop
│   └── readme-modrinth-sync.yml        # copy from autodrop
├── CLAUDE.md                           # agent guidance (commands, Stonecutter rules, architecture)
├── PLAN.md                             # this file
├── LICENSE                             # GPL-3.0 text
├── README.md                           # user-facing; synced to Modrinth/CurseForge
├── changelog.md                        # notes for the upcoming release only
├── settings.gradle.kts                 # Stonecutter targets
├── stonecutter.gradle.kts              # active target + postUpdate Discord task (copy from autodrop)
├── build.gradle.kts                    # central per-target script (modstitch)
├── gradle.properties                   # mod.* properties
├── gradle/libs.versions.toml
├── versions/
│   ├── 26.3-fabric/gradle.properties
│   └── 26.3-neoforge/gradle.properties
├── docs/
│   ├── vanilla-notes.md                # M1: how 26.3 renders entities, exact hook points
│   ├── benchmarks.md                   # protocol + results table
│   ├── benchmarks/results/             # CSVs from Anton's runs
│   └── compat.md                       # compatibility matrix and known issues
├── benchmark/
│   └── mobium-bench/                   # datapack that builds the benchmark scenes (works without Mobium too)
└── src/
    ├── main/java/dev/nyon/mobium/mixins/            # thin hooks only, forward into Kotlin
    ├── main/kotlin/dev/nyon/mobium/                 # see §5
    ├── main/resources/
    │   ├── mobium.mixins.json
    │   └── assets/mobium/{icon,lang,shaders}/
    ├── main/templates/fabric.mod.json
    ├── main/templates/META-INF/neoforge.mods.toml
    ├── test/kotlin/dev/nyon/mobium/                 # JUnit 5; pure Kotlin, no Minecraft classes
    └── gametest/                                    # Fabric client gametests (screenshots, scene smoke tests)
```

---

## 4. Build setup (milestone M0 builds this)

Copy autodrop's files and change only what is listed here.

**settings.gradle.kts**
- `rootProject.name = "mobium"`; same `pluginManagement` repositories; `id("dev.kikugie.stonecutter") version "0.9"`.
- `stonecutter { kotlinController = true; centralScript = "build.gradle.kts"; shared { version("26.3-fabric", "26.3"); version("26.3-neoforge", "26.3"); vcsVersion = "26.3-fabric" }; create(rootProject) }`

**gradle/libs.versions.toml** — start from autodrop's catalog, re-checked in M0 (19 Oct 2026): kotlin `2.4.21`,
modstitch `0.8.5`, fabric-loader `0.20.0`, fabric-language-kotlin `1.14.2+kotlin.`, KotlinLangForge `2.15.0` (catalog
key `klf`; a `kotlin-*` key would shadow `libs.versions.kotlin`), konfig `3.0.1`, moddev `2.0.148`, mod-publish
`2.2.+`, JUnit `6.1.3`. The Gradle wrapper stays on the house version 9.4.1.

**gradle.properties**
```
org.gradle.jvmargs=-Xmx4096M
org.gradle.parallel=true

mod.main-branch=main
mod.color=<pick an int colour for the Discord embed>
mod.icon=assets/mobium/icon/icon.png
mod.icon-url=https://raw.githubusercontent.com/btwonion/mobium/main/src/main/resources/assets/mobium/icon/icon.png
mod.group=dev.nyon
mod.repo=btwonion/mobium
mod.slug=mobium
mod.curseforge-slug=mobium-perf
mod.id=mobium
mod.name=Mobium
mod.description=Draws chests, signs and item frames from the GPU
mod.beta=1
mod.major-version=0.1.0
mod.supported-loaders=fabric,quilt,neoforge
```

**versions/26.3-fabric/gradle.properties** (looked up on Modrinth and the CaffeineMC Maven in M0; Fabric uses Sodium
0.9.2, the build Iris 1.11.7 lists on Modrinth)
```
vers.mcVersion=26.3
vers.mcVersionRange=>26.2
vers.supportedMcVersions=26.3

vers.deps.fapi=0.164.0+26.3
vers.deps.modMenu=21.0.0
vers.deps.sodium=mc26.3-0.9.2-fabric
vers.deps.sodiumApi=0.9.2+mc26.3
vers.deps.iris=1.11.7+26.3-fabric
vers.deps.emf=3.3.12-fabric-26.3
vers.deps.etf=7.2.6-fabric-26.3

modstitch.platform=fabric-loom
```

**versions/26.3-neoforge/gradle.properties**
```
vers.mcVersion=26.3
vers.mcVersionRange=>26.2
vers.supportedMcVersions=26.3

vers.deps.fml=26.3.0.94
vers.deps.klf=3.1
vers.deps.sodium=mc26.3-0.9.3-beta.1-neoforge
vers.deps.sodiumApi=0.9.3-beta.1+mc26.3
vers.deps.iris=1.11.7+26.3-neoforge
vers.deps.emf=3.3.12-neoforge-26.3
vers.deps.etf=7.2.6-neoforge-26.3

modstitch.platform=moddevgradle
```

NeoForge needs Sodium 0.9.3-beta.1: Sodium 0.9.2-neoforge carries a `LevelExtractorMixin` for an
`extractVisibleBlockEntities(Camera, float, LevelRenderState, Frustum)` patch that NeoForge 26.3.0.94 no longer has,
and the dev client crashes on startup (Iris 1.11.7 accepts Sodium `[0.6,)`). Move Fabric to the same Sodium once a
0.9.3 release is out.

Maven coordinates: `maven.modrinth:sodium|iris|entity-model-features|entitytexturefeatures:<version>`, Sodium API
`net.caffeinemc:sodium-<loader>-api:<sodiumApi>`. Mod ids (same on both loaders): `sodium`, `iris`,
`entity_model_features`, `entity_texture_features`, `modmenu`.

**build.gradle.kts** — autodrop's central script with these changes:
- `modLicense = "GNU General Public License v3.0"`, `modAuthor = "btwonion"`.
- `mixin { addMixinsToModManifest = true; configs.register("mobium") }`.
- Repositories: add `https://maven.caffeinemc.net/releases` (Sodium Config API) and `https://api.modrinth.com/maven`
  (Iris, EMF, ETF, Sodium as `maven.modrinth:<slug>:<version>`).
- Compat mods are **compile-only** dependencies (Mobium must load without them) and **dev-runtime** dependencies so
  `runClient` starts with the full compat stack: `modstitchModCompileOnly` and `modstitchModLocalRuntime`
  (confirmed in modstitch 0.8.5; they proxy Loom's/MDG's `compileOnly` and `localRuntime`).
- Fabric targets only: enable Fabric client gametests (`fabricApi { configureTests { createSourceSet = true;
  modId = "mobium-test"; eula = true; enableGameTests = false } }`, see Fabric's "Automatic testing" docs; server
  gametests are off because Mobium is client-only) and register a `runClientGameTest` dependency from `check`.
  The `fabricApi` extension is reached with `extensions.configure<FabricApiExtension>("fabricApi")` because the
  central script has no generated Loom accessors. Locally, `./gradlew build` therefore opens a game window for
  the gametests; CI runs it under `xvfb-run`.
- Keep `publishMods` and the `nyon` Maven publication, but leave `modrinth.projectId` / `curseforge.projectId` as
  TODO until the projects exist; do not run `releaseMod` before the Gate B release.
- NeoForge `mainClient` run: same as autodrop (`gameDirectory = ../../run`, X11 env vars).

**Templates**
- `fabric.mod.json`: copy autodrop's (client environment, kotlin adapter). Entrypoints: `client` →
  `dev.nyon.mobium.MobiumEntrypoint`, `modmenu` → `dev.nyon.mobium.config.ModMenuImpl`,
  `sodium:config_api_user` → `dev.nyon.mobium.config.SodiumOptions`. `breaks` / `conflicts`: none (overlaps are
  handled at runtime, §6). M0 declares only `client`; `modmenu` and `sodium:config_api_user` are added in M4
  together with the classes they name (an entrypoint pointing at a missing class crashes the game).
- `neoforge.mods.toml`: copy autodrop's (`modLoader = "klf"`, minecraft dependency `side = "CLIENT"`).
- `mobium.mixins.json`: package `dev.nyon.mobium.mixins`, `compatibilityLevel` `JAVA_25`,
  `injectors.defaultRequire = 1`, all mixins under `client`. Add a mixin plugin
  (`dev.nyon.mobium.mixins.MobiumMixinPlugin`) so compat mixins apply only when their target mod is present.

**Commands (also go into CLAUDE.md)**
```
./gradlew build                            # all targets; what CI runs
./gradlew :26.3-fabric:build               # one target
./gradlew :26.3-fabric:runClient           # dev client, Fabric (compat stack on the classpath)
./gradlew :26.3-neoforge:runMainClient     # dev client, NeoForge (game dir: run/)
./gradlew :26.3-fabric:runClientGameTest   # client gametests (screenshots)
./gradlew "Set active project to 26.3-neoforge"   # switch the version the src/ comments are written for
```

---

## 5. Architecture

### 5.1 How 26.3 draws entities (what Mobium plugs into)

Confirmed in M1 against the 26.3 sources; details, signatures and excerpts are in `docs/vanilla-notes.md`. Vanilla
renders world objects in four stages, and posing and tessellation happen together in the third:

1. **Extract** — `LevelExtractor.extract` culls and builds one render state per visible object:
   `extractVisibleEntities` → `EntityRenderDispatcher.extractEntity`, `extractVisibleBlockEntities` →
   `BlockEntityRenderDispatcher.tryExtractRenderState` (a new state object per block entity per frame).
2. **Submit** — `LevelRenderer.submitFeatures` → `EntityRenderDispatcher.submit` / `BlockEntityRenderDispatcher.submit`
   → `renderer.submit(state, poseStack, collector, cameraState)` queues records on `SubmitNodeStorage`
   (`submitModel`, `submitModelPart`, `submitItem`, `submitText`, `submitCustomGeometry`, …). In 26.3 `submitModel`
   takes a `@Nullable UvMapping`. A model submit keeps references to the shared `Model` and the render state.
3. **Prepare (pose + tessellate)** — `FeatureRenderDispatcher.prepareFrame` groups records by phase, feature type and
   render type, then each feature renderer writes vertices into one shared `StagedVertexBuffer` per frame. For models,
   `ModelFeatureRenderer.prepareModel` calls `model.setupAnim(state)` (EMF / Fresh Animations act here) and right
   after it `model.renderToBuffer(…)` → `ModelPart.render` → `ModelPart.compile` → `Cube.compile`, which transforms
   every vertex on the CPU. `StagedVertexBuffer.upload()` then copies all vertices to pooled GPU buffers once.
   (`LivingEntityRenderer.submit` also calls `setupAnim` at submit time so that layers can read the posed parts.)
4. **Draw** — the level's `main` frame pass calls the prepared frame per phase (`executeSolid` after opaque terrain,
   then classic `executeTranslucent`/`executeTranslucentAfterTerrain` or, with improved transparency (OIT), `executeOit`
   once per `OitStage` — `DEPTH_BOUNDS`, `TRANSMITTANCE`, `ACCUMULATE` — so every translucent feature is drawn three
   times), then outline, see-through and always-on-top passes. Every draw is
   `PreparedRenderType.drawFromBuffer(ExecuteInfo, RenderPass)`. `FeatureRenderDispatcher.renderAllFeatures` is only
   used for the hand, GUI, picture-in-picture and Iris's shadow pass.

The GPU layer is **Renderpearl** (`com.mojang.renderpearl`, split into `api`, `backend`, `frontend`); parts of
`com.mojang.blaze3d` remain (`RenderSystem`, vertex formats, platform). Windowing uses SDL. OpenGL is the 26.3 default
(`PreferredGraphicsApi.DEFAULT` tries GL, then Vulkan); Vulkan is opt-in and becomes the default in 26.4. Renderpearl
has no compute API and no storage-buffer usage, so compute runs on raw GL 4.3 against `GlBuffer.handle()` (M6) and on
the game's Vulkan device later (M7).

### 5.2 Where each module hooks in

| Module | Hook (confirmed in M1, see `docs/vanilla-notes.md` §10) | Replaces | Output |
| --- | --- | --- | --- |
| 1 · Static cache | `BlockEntityRenderDispatcher.tryExtractRenderState` and `.submit` (reached by vanilla, Sodium's own block entity iteration and Iris's shadow pass); `EntityRenderDispatcher.submit` for item frames, armor stands and `FIXED` display entities | Steps 1–3 for unchanged objects | Section buckets drawn with `PreparedRenderType.drawFromBuffer` at the end of `PreparedFrame.executeSolid` in the level pass |
| 2 · GPU part transform | `ModelPart.compile(PoseStack.Pose, VertexConsumer, int, int, int)` inside `ModelFeatureRenderer.prepareModel`: below EMF's per-part render-type switches and ETF's emissive re-renders, above Sodium's `Cube.compile` fast path | The CPU vertex transform of step 3 | Records expanded by compute into the frame's uploaded staged vertex buffer, drawn by vanilla's own draws |
| 3 · Animation LOD | `ModelFeatureRenderer.prepareModel` around `Model.setupAnim` (and `LivingEntityRenderer.submit`'s layer `setupAnim`) | Posing on skipped frames | Reuses Module 2's last records. EMF already throttles animations (`animationLODDistance`, default 20) |

### 5.3 Package map

```
dev.nyon.mobium
├── MobiumEntrypoint.kt        # Fabric ClientModInitializer / NeoForge @Mod (Stonecutter branches, like autodrop)
├── Mobium.kt                  # bootstrap(configPath), module registry, logger "Mobium"
├── config/
│   ├── Config.kt              # @Serializable config: global switch, per-module switches, per-type switches, budgets
│   ├── ConfigHandler.kt       # CONFIG_VERSION + migrate(), konfig wiring (autodrop pattern)
│   ├── ModMenuImpl.kt         # Fabric only
│   ├── SodiumOptions.kt       # Sodium Config API page (Fabric entrypoint / NeoForge @ConfigEntryPointForge)
│   └── screen/                # MobiumSettingsScreen: vanilla OptionsSubScreen editing a copy of the config
├── core/
│   ├── ModuleState.kt         # Module, OffReason, ModuleStatus and the pure ModuleRules
│   ├── ModuleStates.kt        # live states: re-resolved on every change, logged, isActive() per frame
│   ├── Fallback.kt            # catch → disable module for this session, log once, one toast (FailureLedger: pure)
│   ├── Capabilities.kt        # graphics API, GL version, compute support, MultiDrawIndirect
│   └── Compat.kt              # detects Sodium, Iris (+ shader pack active), EMF, ETF, EntityCulling, overlapping mods
├── debug/
│   ├── DebugLines.kt          # F3 mobium:timings (stage timers)
│   ├── ModuleLines.kt         # F3 mobium:modules: module states, GPU memory, cached objects, failed types
│   ├── AbToggle.kt            # keybind toggling all modules live for A/B comparison
│   ├── Sections.kt            # timers around extract / submit / pose / tessellate / draw
│   ├── Validation.kt          # dev builds: draw both ways off-screen and pixel-diff (compare() exists)
│   └── selfcheck/             # dev builds: in-game scripted check of a PR's "How to verify", both loaders
├── bench/
│   ├── BenchCommand.kt        # client command: /mobium bench <scene> <seconds>
│   └── FrameRecorder.kt       # frame times → CSV + summary (avg FPS, 1% low, avg and p95 frame ms, section times)
├── gpu/
│   ├── GpuBackend.kt          # interface: buffers, uploads, compute dispatch, draw a range with a vanilla render type
│   ├── RenderpearlBackend.kt  # buffers + draws through Renderpearl (works on GL and Vulkan)
│   ├── RingAllocator.kt       # pure ring arithmetic (slots, alignment, doubling), unit-tested
│   ├── RingBuffer.kt          # 3 frames in flight, fences, grow by doubling
│   ├── gl/GlCompute.kt        # OpenGL 4.3 compute + SSBOs + memory barrier
│   ├── vk/VkCompute.kt        # M7: compute pipeline on the game's Vulkan device/queue
│   └── Shaders.kt             # loads GLSL from assets/mobium/shaders, compiles per backend
├── cache/                     # Module 1
│   ├── StaticCache.kt         # lookup / capture / invalidate, per-frame "mark section" list
│   ├── CaptureConsumer.kt     # VertexConsumer recording per render type, block-local space, active vertex format
│   ├── SectionBucket.kt       # concatenated meshes per (chunk section, render type), rebuilt on change
│   ├── CachePolicy.kt         # per-type rules: when cacheable, when vanilla
│   ├── Invalidation.kt        # data/state/light/reload/shader-pack listeners
│   └── MemoryBudget.kt        # default 128 MB, evict by distance
├── parts/                     # Module 2
│   ├── MeshPool.kt            # ModelPart identity → local-space cuboid mesh in a shared GPU buffer
│   ├── PartRecords.kt         # off-heap record writer (layout below)
│   ├── PartWalker.kt          # ModelPart.compile hook: emits one record per drawn part (vanilla and EMF walks stay on the CPU)
│   ├── VertexFormats.kt       # vanilla entity format + Iris extended format descriptions
│   └── ExpandPass.kt          # per render type: dispatch compute, then draw the ring range
├── lod/                       # Module 3
│   └── AnimationLod.kt        # screen-size rule, presets, exclusions, record reuse
└── util/                      # MemoryUtil helpers, LogOnce, math
```

**Part record layout (Module 2), 80 bytes, std430-friendly:**

| Offset | Field | Type |
| --- | --- | --- |
| 0 | pose matrix, 3 columns of a 4×3 affine transform | 12 × f32 |
| 48 | UV mapping (atlas sprite u0, v0, u-scale, v-scale) | 4 × f32 |
| 64 | mesh id (index into MeshPool) | u32 |
| 68 | packed light (block, sky) | u32 |
| 72 | packed overlay (hurt flash / white) | u32 |
| 76 | tint colour ARGB | u32 |

Iris's extended attributes are computed per quad inside the compute shader, matching Iris 1.11.7's own vertex
writer (`MixinBufferBuilder`, confirmed in M1): `IrisVertexFormats.ENTITY` is the 36-byte vanilla entity format plus
`iris_Entity` RGBA16_UINT (entity, block entity and item id, written **per vertex**), `mc_midTexCoord` RG32_FLOAT (mean
UV of the quad) and `at_tangent` RGBA8_SNORM (`NormalHelper.computeTangent`), 56 bytes in total; while rendering the
level Iris also replaces each quad's normals with the face normal. The three ids come from the submit
(`CapturedRenderingState`), so they have to travel with the records: either 8 more bytes per record (88 bytes) or a
per-submit table the record indexes into — decided in M6.

---

## 6. Runtime behaviour every module must follow

- **Fallback ladder:** per submit (unknown or wrapped vertex consumer → vanilla), per type (config or repeated
  failure → vanilla), per module (exception or GPU error → off for the session with one log line and one toast).
- **Overlap detection:** if Enhanced Block Entities Reloaded, an Accelerated Rendering port or CrankShaft is
  installed, switch off the overlapping Mobium module and say so in the log and the settings screen.
- **Sodium:** take over only the submits Mobium accelerates; everything else falls through to Sodium.
- **Iris:** capture and expand in Iris's active vertex format; treat a shader pack change like a resource reload
  (clear caches). Shadow-pass entity draws reuse the frame's records where possible.
- **EMF/ETF:** never bypass `setupAnim`; EMF parts are pooled like vanilla parts; ETF emissive textures are just
  another render-type pass.
- **Thread:** all render work stays on the render thread. No new threads in Modules 1–2.
- **Defaults:** Module 1 on; Module 2 on when compute is available; Module 3 off.

---

## 7. Milestones

Each milestone lists tasks, what "done" means, and what Anton checks. Gates A–C are go/no-go decisions: if a
gate fails, stop, write up why in the PR, and agree on the next step before continuing.

### M0 · Scaffold
- [x] Root files from §3–§4 (settings, build, stonecutter, gradle.properties, version catalog, version folders,
      wrapper, .editorconfig, .gitignore, LICENSE + COPYING, README skeleton, changelog.md).
- [x] `MobiumEntrypoint` for both loaders (Stonecutter branches as in autodrop's `AutoDropEntrypoint.kt`) that
      loads the konfig config and logs the detected capabilities and compat mods.
- [x] Empty `mobium.mixins.json` + `MobiumMixinPlugin`; `en_us.json` / `de_de.json` with the mod name.
- [x] CI `build-commit.yml` with the 26.3 × fabric/neoforge matrix (copy autodrop/better-boat-movement).
- [x] CLAUDE.md updated with the real commands.

Done when: `./gradlew build` is green for both targets locally and in CI, and both dev clients reach the title
screen with Mobium listed.
How to verify (Anton): run `:26.3-fabric:runClient` and `:26.3-neoforge:runMainClient`, open the mod list.

Status (19 Oct 2026): `./gradlew build` is green locally for both targets, including the Fabric client gametest
(`StartupGameTest`: title screen reached with Mobium, Sodium, Iris, EMF, ETF and ModMenu loaded, capabilities
logged). The NeoForge dev client starts with the same stack and logs Mobium's compat and graphics lines. Open: CI
(no GitHub repository yet, see §11) and Anton's visual check of both dev clients.

### M1 · Vanilla recon (documentation only)
- [x] `docs/vanilla-notes.md` answers, with class + method signatures and short source excerpts:
  - [x] Where block entities are iterated, culled and submitted each frame (and what Sodium 0.9 replaces there).
  - [x] How item frames, armor stands and display entities are submitted.
  - [x] The model feature renderer: where `setupAnim` runs relative to tessellation, how `ModelPart` trees are
        walked (visibility, `skipDraw`, children), how `UvMapping` is applied.
  - [x] `FeatureRenderDispatcher.renderAllFeatures`: order, render-type grouping, OIT multi-pass behaviour.
  - [x] Renderpearl: buffer creation/upload, how a render type's pipeline is bound and drawn, how the GL and
        Vulkan backends are selected, whether any compute API exists.
  - [x] Change signals: block-entity data updates on the client, block state changes, section light changes,
        resource reload, render distance / level change.
  - [x] Iris 1.11: extended entity vertex format layout, how tangent and mid-texture coordinates are computed,
        how `entityId` / `blockEntityId` reach the shader (per vertex or per-draw uniform), shader-pack reload
        signal, shadow pass.
  - [x] EMF/ETF: which `ModelPart` / `setupAnim` paths they hook, whether EMF already throttles animations, how
        ETF draws emissive layers.
  - [x] Sodium 0.9: whether it still carries a model-part fast path and where; Config API artifact coordinates.
- [x] A final "Hook list" table: hook name → target class/method → injection type → which module uses it.

Done when every *(verify)* in this plan is either confirmed or corrected in PLAN.md.

Status (19 Oct 2026): done. `docs/vanilla-notes.md` answers every question; §5.1–§5.3 are corrected (posing and
tessellation are one prepare stage, the level does not use `renderAllFeatures`, Iris's format is 56 bytes with
per-vertex ids). Findings that change later milestones: Module 2 hooks `ModelPart.compile` instead of walking trees
itself (EMF/ETF), the part record needs Iris's three ids, Sodium `@Overwrite`s `LevelExtractor`'s dirty methods (hook
the `ClientLevel`/`ClientChunkCache` callers), and EMF already has an animation LOD.

### M2 · Instrumentation and benchmark harness (Phase 0)
- [x] `debug/Sections.kt`: timers around extract, submit, pose, tessellate and draw, split by entity vs block
      entity, shown on F3 and visible in spark.
- [x] `bench/`: `/mobium bench <scene> <seconds>` client command (Fabric client command API / NeoForge
      `RegisterClientCommandsEvent`), writing `run/mobium-bench/<scene>-<setup>-<timestamp>.csv` and printing a
      summary in chat.
- [x] `benchmark/mobium-bench` datapack with one function per scene, each building the scene in a superflat
      world and teleporting the player to a fixed position and rotation:

      | Scene | Contents |
      | --- | --- |
      | `mob_pen_static` | 500 mixed mobs (cows, villagers, zombies) with NoAI, for repeatable runs |
      | `mob_pen_live` | The same mobs with AI in 1×1 pens, so walk animations run |
      | `storage_room` | 1,000 chests and 300 signs with text |
      | `map_wall` | 400 item frames with filled maps, plus shelves with items |
      | `display_build` | A decoration built from item and block display entities |
      | `control` | Empty superflat, nothing to accelerate |

- [x] Fabric client gametest that builds each scene and saves baseline screenshots.
- [x] `docs/benchmarks.md`: the protocol (60 s per run, median of 3, four setups: Sodium only; + Iris with one
      named shader pack; + EMF/ETF with Fresh Animations; all together) and an empty results table.

Done when Anton has committed baseline CSVs for all scenes and setups, and `docs/benchmarks.md` states the cost
split (pose vs tessellate + draw) per scene.
How to verify (Anton): load the datapack, run each scene in each setup with `/mobium bench`, commit the CSVs.

Status (20 Oct 2026): done. Anton's baselines from 19 Oct are in `docs/benchmarks/results/2026-10-19-thinkpad-x9/`,
the table and the cost split in `docs/benchmarks.md`. That round is partial: setup A has only `storage_room`, no
shader pack or Fresh Animations was active, `control` exists only for D, the mob pens have one valid run each
(the zombies burned once their helmets wore out), and most sessions ran on the built-in 1728×1080 panel. The cost split
holds anyway: for vanilla mobs tessellation costs 11–14× as much as posing, EMF/ETF double the entity extract, and with
the chests cached the 300 signs are what is left of `storage_room`. The harness is fixed for the next round
(unbreakable helmets; the Prism script installs and selects the packs and declares each instance's setup, which the
suite checks; display, power and GPU clock in `summary.csv`; medians saved after every run). The missing cells are
measured with the fixed harness before Gate B (A, `control`) and before M6 (B–D with the packs).

Timers are on the F3 entry `mobium:timings` and in the vanilla profiler (`mobium:*` sections). The datapack is
generated by `benchmark/generate.py`; all scenes share one origin (x −3840, z −2816) in the seed-`mobium` classic
superflat world, which `BenchmarkScenesGameTest` checks is ~600 blocks from the nearest village (villages must stay on
for the exploration maps). The gametest also runs `/mobium bench` and checks the CSVs, and shows the F3 entry. Abort is
`/mobium stop` (a `bench stop` literal would be ambiguous with the scene argument).
Later additions for running the protocol: `/mobium suite` (whole setup, medians) and `/mobium gatea`, the `benchKit` and
`runBenchWorld` Gradle tasks and `benchmark/prism_instances.py`, which installed the Prism instances "Mobium Bench A–D"
on the development machine.

### M3 · Chest prototype → Gate A
- [x] Minimal Module 1 for closed chests only, OpenGL only, behind `cache.prototype` (off by default).
- [x] A temporary keybind toggles the prototype on and off live, so Anton can compare in the same spot.

**Gate A:** `storage_room` runs at least 1.5× faster than the Sodium-only baseline with no visible difference.
If it fails, stop and report the measurements.

Status (20 Oct 2026): **Gate A passed, 1.53×.** `/mobium gatea` in setup A on 19 Oct, at 1920×1080: 3.33 → 2.18 ms per
frame (300 → 460 FPS, median of 3 alternating runs; slowest pairing 1.51×); the gametest's screenshots with the
prototype off and on differ in 0 pixels. A second session measured 8.05×, but its vanilla baseline was in the slow
state described in `docs/benchmarks.md` (*Slow vanilla `storage_room`*) and does not count. Prototype in
`cache/ChestPrototype.kt`, `CaptureConsumer.kt`, `SectionBucket.kt`. How it works:
- Closed, unbroken chests are captured once by running vanilla's own submit and `ModelFeatureRenderer.prepareModel`
  (through `buildGroup`) with the vertex builder redirected into a capture buffer. Every mod hook on that path still
  runs.
- The captured vertices go into one GPU buffer per (section, render type). The extract hook returns no render state
  for cached chests, and the buckets are drawn at the end of the level's solid features through
  `PreparedRenderType.drawFromBuffer`, with the section origin in the model-view matrix.
- Light is re-read only after a light update in the chest's section, plus every 128 frames as a safety net.
- The key `]` toggles the prototype live (since M4 the A/B key, which switches all of Mobium).

`BenchmarkScenesGameTest` compares `storage_room` with the prototype off and on: 0 differing pixels. First look
(5 s, laptop iGPU, Sodium + EMF/ETF, uncapped): 9.38 → 5.55 ms per frame, 1.69×. With the default 120 FPS cap the
gain disappears, and the remaining cost in `storage_room` is mostly the 300 signs' text, which is translucent and
therefore outside Module 1 as planned (§M5).

### M4 · Core infrastructure
- [x] `core/` (ModuleState, Fallback, Capabilities, Compat) with unit tests for the pure parts.
- [x] `gpu/` GpuBackend + RenderpearlBackend + RingBuffer (unit-test the ring arithmetic and growth).
- [x] `debug/` F3 lines, A/B toggle, Validation skeleton (dev builds only).
- [x] Full `Config` (global, per-module, per-type, memory budget, LOD preset) with `CONFIG_VERSION` and
      `migrate()` following autodrop's konfig rules (unknown keys must keep failing).
- [x] Settings screen (vanilla widgets) + ModMenu + NeoForge `IConfigScreenFactory` + Sodium Config API page
      (`sodium:config_api_user` on Fabric, `@ConfigEntryPointForge("mobium")` on NeoForge), all bound to the same
      config. Every visible string translated in `en_us` and `de_de`.

Done when both loaders show identical settings, toggling a module at runtime logs the state change, and a forced
exception in a module disables it with one toast.

Status (20 Oct 2026): done on branch `m4-core`, verified on both loaders with the in-game self-check
(`scripts/self_check.py`: Fabric 26/26, NeoForge 27/27 checks; screenshots read by eye). The self-check runs #5's
*How to verify* inside the dev clients: settings through Mod Menu's factory and the NeoForge mod list's Config button,
English and German without raw keys, Mobium's Sodium page and its Apply path, saved settings, and in `storage_room`
the A/B key (Mobium and vanilla pictures: 0 differing pixels), F3 `mobium:modules`, `/mobium debug fail` (one toast,
chests still drawn) and the status line. It found two real problems, both fixed: Mobium's `neoforge.mods.toml` used
the deprecated `logoFile` (a NeoForge loading warning; now `iconFile`, plus `displayURL`), and the Sodium page's first
group had no heading.
- **Module states.** `core/ModuleRules` (pure, unit-tested) resolves each module from config, backend, compute, shader
  pack, A/B key, overlapping mods and failures, with one reason when it is off. `core/ModuleStates` re-resolves on
  every change and logs each transition ("Mobium static_cache: active -> vanilla (ab_toggle) (A/B key: vanilla)").
  Module 1 is still the M3 chest prototype (opt-in, `cache.prototype`); Modules 2 and 3 resolve to "not built".
- **Fallback.** `core/Fallback` takes module failures (log with stack trace, one toast, module on vanilla until
  restart) and type failures (vanilla after 3). The prototype reports through it: an exception in extract, prepare or
  draw switches Module 1 off, repeated capture failures switch chests off.
- **Overlaps.** Enhanced Block Entities (and Reloaded), Vanillate (CrankShaft) and Accelerated Rendering switch off the
  modules they overlap (`docs/compat.md`).
- **GPU.** `gpu/GpuBackend` with `RenderpearlBackend` (buffers, uploads, fences, quad draws through
  `PreparedRenderType.drawFromBuffer`, memory accounting); the prototype's section buckets use it. `RingBuffer` (3
  frames in flight, fences, doubling with a GPU copy) waits for M6; its arithmetic (`RingAllocator`) is unit-tested.
- **Debug.** F3 `mobium:modules` (states, A/B, GPU memory, prototype counts, failed types); `]` is now the A/B key for
  all of Mobium (replacing the prototype key); dev environments get `/mobium debug fail <module>` and the
  `Validation` skeleton (`/mobium debug validate`; the off-screen comparison comes with M5).
- **Config 3.** Global switch; Module 1 switch, 9 type switches, GPU memory budget (16–1024 MB, default 128),
  prototype switch; Module 2 switch and entity exclusions; Module 3 preset (Off/Subtle/Aggressive) and exclusions.
  Version 2 files decode unchanged; a migration branch keeps their two settings if the rest is broken. The entity
  exclusion lists are only editable in `config/mobium.json` until M6/M7 use them.
- **Settings.** `MobiumSettingsScreen` from vanilla's `OptionsSubScreen` and `OptionInstance` widgets, editing a copy
  (Done applies and saves, Cancel discards), with the module status at the top; opened from Mod Menu, NeoForge's mod
  list and a button on the Sodium page, which has the same options as native Sodium controls.
- **Checks.** `SettingsGameTest` (Fabric): the screen opens and scrolls (screenshots), a saved change re-resolves the
  modules, a forced failure switches a module off with exactly one toast. Both targets build; the NeoForge dev client
  starts and logs the module states.

### M5 · Module 1, static cache (Phase 1) → Gate B → first release
- [x] `CaptureConsumer`: records what the vanilla renderer emits, per render type, in block-local space, in the
      vertex format active at capture time (Iris-extended when a shader pack is on).
      Captures run in the cache's prepare step inside `LevelRenderer.render` (`cache/Capture.kt`), where vanilla and
      Iris submit too: an object is drawn by vanilla in the frame it is first seen and from the cache from the next.
      With Complementary on, captures are 56-byte entity and 52-byte glyph vertices (Iris's formats).
- [x] `SectionBucket`: one buffer per (chunk section, render type); rebuilt on the next frame after any member
      changes; drawn through vanilla's pipeline (no shader edits). If M1 shows Iris needs per-draw IDs, split buckets
      by ID.
      *Changed in M5:* not with the section offset in the model-view matrix. Vanilla's entity and text shaders take
      the fog distance from the raw `Position` attribute (`docs/vanilla-notes.md` §15), so the M3 prototype drew
      cached chests with the fog of a closer object (max channel difference 6 in `storage_room`, under the tests'
      threshold of 16). The CPU keeps section-local vertices and writes camera-relative ones into the bucket's buffer
      when it is drawn after the camera moved or a member changed; with a still camera nothing is written.
- [x] `CachePolicy`:

      | Object | Cached while | Vanilla path while |
      | --- | --- | --- |
      | Chests, ender chests, shulker boxes | Closed | Opening, open, closing (+1 frame) |
      | Signs and hanging signs, incl. text and glow ink | Always | Being edited |
      | Beds, skulls and heads, decorated pots | Always | Pot wobbling; dragon head powered |
      | Shelves and their items | Always | An item is animated (e.g. a compass) |
      | Item frames and glow item frames | Item and rotation unchanged | Item changes or rotates; a map; name tag, glowing |
      | Armor stands, item and block display entities | Pose and transform unchanged | Interpolating or moving; armor; name tag, glowing; billboard displays |

      Banners, bells, conduits and lecterns animate every frame and wait for Module 2.
      Done (`cache/CachePolicy.kt`): chests (also trapped, copper, ender) and shulker boxes; signs and hanging signs
      (vanilla while edited; obfuscated, sprite and player-head text never); skulls (player heads with an owner stay
      vanilla: their skin loads later); decorated pots; shelves (vanilla extracts every frame and the shelf is drawn
      from the cache while every item still draws the same, `ItemFingerprint`; items whose model changes right after a
      capture, such as a spinning compass, pause on vanilla). **Beds have no block entity in 26.3** (terrain, like sign
      boards); their switch is gone (config 4). Models EMF animates stay vanilla.
      Entities (`cache/EntityPolicy.kt`, 20 Oct 2026): entities are always extracted, so the hook wraps the renderer's
      submit inside `EntityRenderDispatcher.submit` (flame and shadow stay vanilla) and skips it while the freshly
      extracted state still matches the captured one: position exactly, light, and per rule its inputs
      (`ItemFingerprint` for items, `BlockModelFingerprint` for block models). The entry lives on the entity; render
      states get a reference to their entity. Captures go through `EntityRenderDispatcher.submit` at 0, 0, 0, because
      Iris records the entity id there. Item frames and glow item frames (maps stay vanilla: translucent custom
      geometry); armor stands (vanilla while wobbling, hurt, with an owned player head, and **with armor**: equipment
      layers are drawn in submit orders one after another, which the cache does not keep); block and item displays
      with the `FIXED` billboard and finished interpolation. Everything with a name tag, an outline (glowing), a leash
      or riding a minecart stays vanilla. On Fabric, block models come as Fabric renderer API submits
      (`ExtendedBlockModelSubmit`, drawn by Sodium's or Indigo's feature renderer), which the capture lets that
      renderer write.
- [x] Only solid and cutout geometry is cached; translucent parts stay vanilla unless OIT is on. Any object with
      the breaking overlay uses vanilla for that frame.
      *Conflict found at Gate A:* with the chests cached, `storage_room` is still about 1.9× `control`, and what is
      left is the signs' text. Gate B (1.2×) is out of reach unless sign text is cached too; decide early in M5
      whether text quads can be drawn from a cached buffer in the translucent phase (they need no sorting among
      themselves) or whether Gate B's target changes.
      *Settled (20 Oct 2026): sign text is cached, Gate B unchanged.* Sign text is opaque with no background, so
      vanilla draws it in the **solid** phase (`SubmitNodeCollection.canRenderAsSolid`), and the cache draws it there
      too. The self-check compares Mobium with vanilla in `storage_room` (1,000 chests, 300 signs, glow ink with and
      without outline) from the default view, a moved camera and at render distance 2 with the chests in the fog band:
      0 changed pixels, max channel difference 0, on Fabric and NeoForge.
- [x] `Invalidation` for data, block state, section light, resource reload, shader-pack change, level change.
      Data: each rule's inputs are compared every frame (sign text, pot sherds, skull owner and mouth, items).
      Block state: identity. Light: section light versions plus a re-read every 128 frames. Reload, render distance,
      level: `LevelExtractor.allChanged` / `setLevel`. Shader pack: Iris's pipeline identity, checked at the start of
      each frame's extraction. Removed block entities: `BlockEntity.setRemoved`, and never cached when removed.
- [x] `MemoryBudget`: 128 MB default, evict farthest first (`cache/MemoryBudget.kt`, unit-tested; evicted objects stay
      vanilla until usage is below 75 %).
- [x] Screenshot tests per scene and per effect (Fabric client gametests); Validation mode diff = 0.
      Gametest: every static scene compared with the cache off and on, plus the gallery, `/mobium gatea` and
      `/mobium gateb`. Self-check (both loaders): `storage_room` from three views, the `cache_gallery` scene (with
      item frames, armor stands and displays) and eleven effects (lid, breaking overlay, light, sign text, block
      state, item frame turned, frame item swapped, display moved, armor stand pose), Complementary (Fabric) and Fresh
      Animations, and `/mobium debug validate`, all compared from two frames of one client tick. Every comparison: 0
      changed pixels (`--mode gallery` runs only the gallery part).
- [x] `docs/compat.md` filled for Sodium, Iris (+ named packs), EMF/ETF + Fresh Animations, EntityCulling, on
      both loaders. (Entity Culling is not in the dev environment; NeoForge + Complementary crashes inside Iris, also
      with Mobium off.)

**Gate B:** zero screenshot differences; what the cached objects cost Mobium within 1.2× of `control`: `storage_room`
with the cache's draw calls skipped, plus the CPU time of those draw calls, over `control` (`/mobium gateb`,
`bench/GateB.kt`); `control` not slower than baseline (at most 1.03×); works with the full compat stack on Fabric and
NeoForge. The whole `storage_room` frame over `control` and the cost of drawing the cached objects are reported
alongside, without a target.
*Changed on 20 Oct 2026 (Anton's decision):* the first definition, the whole `storage_room` frame within 1.2× of
`control`, failed at **1.39×** (Anton's `/mobium gateb` in setup A at 1920×1080: `storage_room` 1.695 ms, `control`
1.218 ms; `control` with/without the cache 1.005×, passed). About 0.18 ms of the gap is Mobium's CPU work and about
0.30 ms is the GPU drawing the 1,000 chests and 300 signs, which vanilla pays as well; even with no CPU work left the
ratio would have been about 1.24×. Of the options (keep 1.2× and do more CPU work first, gate against vanilla, waive it
for the beta, or gate Mobium's own cost), Anton chose the last. `/mobium gateb` therefore records a fourth run per
round, `storage_room` with the cache's draw calls skipped (`…+cache+nodraw`; the chests and signs vanish during it).
Write-up in `docs/benchmarks.md` (*Gate B*).
**Status (22 Oct 2026): Gate B passed, 1.17×.** Anton's `/mobium gateb` in setup A on 21 Oct (build 6fb45dc,
1920×1080, mains power, medians of 3): `storage_room` without the cache's draw calls 1.247 ms plus their CPU time
0.041 ms over `control` 1.101 ms = 1.170× (rounds 1.07×, 1.21×, 1.16×); `control` with/without the cache 1.003×; the
whole frame 1.395× and 0.29 ms for drawing the cached objects, reported without a target. Zero screenshot differences
(self-check and gametests on both loaders); the compat stack works on both loaders (NeoForge without a shader pack:
Iris crashes there with Complementary, Mobium off too). A first round on 21 Oct had measured the previous build
(Anton's checkout was four commits behind); the jar now names its commit and `benchmark/prism_instances.py` refuses a
stale kit. Full round with entities cached (A–D) in `docs/benchmarks.md` (*21–22 Oct 2026*). Release prep for
`0.1.0-beta1` is on the same branch (README, changelog, `release.yml`); left for Anton: the Modrinth/CurseForge
projects and their IDs, then `release.yml` (§10).

### M6 · Module 2, GPU part transform (Phase 2) → Gate C
- [x] `MeshPool`: first time a cube is seen, write its faces once in local space; kept on the cube itself
      (`PartMeshHolder`), so EMF's cubes work; written again after a resource reload, level change or render
      distance change. *Changed in M6:* per cube, in the layout of the path that would have drawn it: polygons for
      vanilla and EMF cubes, Sodium's `ModelCuboid` (corner origin, sizes, its face order) for vanilla cubes with
      Sodium (`parts/MeshPool.kt`, `parts/SodiumCubes.kt`).
- [x] `PartWalker` + `PartRecords`. *Changed in M6:* Module 2 does not walk the tree itself. EMF overrides
      `ModelPart.render` and `compile` and animates inside `render`, so vanilla's and EMF's walks run as before and
      Module 2 replaces each part's `compile` (`ModelPartMixin`, `compat.emf.EmfModelPartMixin`): it reserves the
      part's vertices in the staged builder (as after a Sodium push, so Iris's per-vertex state stays as it would be)
      and writes one part record (pose, normal matrix, light, overlay, colour, sprite, Iris's ids) and one record per
      cube (`parts/PartTransform.kt`, `parts/PartBatch.kt`). The records are per staged buffer and frame, off-heap.
- [x] Per-submit safety checks, per part: the consumer is a builder the frame's staged buffer opened (directly or
      through an atlas sprite), its draw is unsorted, its format is the entity format or Iris's, every cube is a
      vanilla cube or an `EMFCube`; otherwise vanilla compiles. Entity types in `parts.excludedEntities` stay vanilla.
- [x] `ExpandPass`: one GLSL 430 compute shader (`assets/mobium/shaders/expand_parts.comp`) for both formats and
      both cube layouts, one dispatch per staged buffer right after its `upload()` (the level's, Iris's shadow pass's,
      the hand's, GUI ones), writing into the uploaded vertex buffer, then a vertex-attribute barrier; vanilla's own
      draws draw it in vanilla's order. The upload leaves the reserved bytes out of its copy. Every float operation
      is the Java code's own, in its order, with correctly rounded square roots and divisions (`docs/vanilla-notes.md`
      §16).
- [x] Effects in the first version: light, hurt overlay, tint, UV mapping (armour trims, atlas sprites), ETF's
      emissive pass where it draws unsorted. Glint, outline and breaking overlay stay vanilla (other formats or
      consumers).
- [x] Reference test. *Changed in M6:* the CPU path itself is the reference. `parts/PartValidation.kt` compiles every
      part Module 2 takes over on the CPU too and compares the GPU's vertices with it byte for byte: in the self-check
      (`--mode parts`, the dev GPU) and in the Fabric gametests (Mesa's software renderer in CI). A Kotlin copy of the
      shader's math would only have tested the copy; the 24k wrong normals Mesa's double square root produced on the
      Arc 140V were found this way.
- [x] Banners, bells, conduits and lecterns move onto this path: their models are model parts, so Module 2 takes
      them like the mobs' (`part_gallery` scene: 0 differing vertices, pictures identical; translucent banner patterns
      stay vanilla without improved transparency).
- [x] `/mobium gatec`: records `mob_pen_live` with Module 2 off and on in one session (three runs each, medians, as
      `/mobium gateb`) and prints Gate C's numbers: `prepare` + `shadow_prepare` without over with Module 2 (target
      ≥ 1.5× in D) and the frame time with over without (target ≤ 1.03×); run in setup C, then in D.

Status (26 Oct 2026): built on `m6-part-transform`. Self-check `--mode parts` on Fabric 25/25 (setups A, B, D, C:
0 differing vertices; pictures identical, within the pack's own noise in the part gallery with Complementary), NeoForge
without shader pack; the regular self-check passes on both loaders with Module 2 on (Fabric 74/74, NeoForge 67/67).
Dev-client measurement (`--mode profile-mobs`, window 854×480, `docs/benchmarks.md` *Module 2, first measurement*):
`prepare` + `shadow_prepare` in D 2.1× lower, frames 1.11× (D), 1.25× (C) and 1.22× (vanilla models) faster. Gate C
waits for Anton's `/mobium gatec` in setups C and D.

**Gate C** (redefined a second time with Anton on 25 Oct 2026, after measuring what Module 2 can reach):
- in setup D (Sodium + EMF/ETF + Fresh Animations + Iris with Complementary Reimagined), `mob_pen_live`'s entity CPU
  work at least **1.5× lower** with Module 2: `prepare` + `shadow_prepare` (posing, tessellation and upload of the
  level's and the shadow pass's prepare stages), medians of three 60-second runs each, one session;
- in setups C and D, `mob_pen_live`'s frame time **no slower** with Module 2: at most 1.03× the frame time without it
  (the noise allowance of Gate B's `control` check); both frame ratios are reported;
- zero screenshot differences (self-check and gametests, both loaders). Then release.

Why (`docs/benchmarks.md`, *Gate C: what M6 can reach*): Mobium can only take CPU work away; whether the frame gets
faster depends on the GPU. In D on Anton's iGPU the frame is mostly GPU work. In C, a dev-client measurement with the
models' vertex writes skipped (`--mode profile-mobs`) left 3.2 of 5.3 ms of tessellation: Fresh Animations' animation
runs inside `ModelPart.render` (EMF animates there, not in `setupAnim`), and the part walk and pose math stay on the
CPU too. So the first redefinition (1.5× in C) was out of reach as well: at most about 1.35× even if Module 2 cost
nothing. In D the vertex work is 11 of 16.5 ms of `prepare` + `shadow_prepare` (Iris's extended vertices are
expensive), at most 3.1× lower.
Earlier definitions, both out of reach: 1.5× frame time in D (the plan's first; at most 1.20× from the 23–25 Oct
round), then 1.5× frame time in C and no slower in D (first redefinition, 25 Oct 2026; at most about 1.35×, above).

### M7 · Phase 3: Vulkan, Aperture, Module 3, 26.4
- [ ] Add `26.4-fabric` / `26.4-neoforge` Stonecutter targets when 26.4 releases (track its snapshots in a branch
      before that). Vulkan becomes the default there.
- [ ] `VkCompute` on the game's own Vulkan device and queue; same GLSL compiled to SPIR-V.
- [ ] Aperture (Iris's Vulkan successor) support once public; contact the Iris team before starting.
- [ ] Module 3 (`lod/`): throttle by projected size; hurt flash, death animation and targeted mob always full
      rate; presets Off / Subtle / Aggressive; per-type exclusions; exclude EMF-animated models until tested.
- [ ] Glint and outline passes generated from the same records.

---

## 8. Coding rules

- **Mixins:** Java, in `dev.nyon.mobium.mixins`, thin: inject and forward to a Kotlin object. Handler methods are
  prefixed `mobium$`. Prefer MixinExtras (`@WrapOperation`, `@ModifyExpressionValue`) over `@Redirect` and
  `@Overwrite` for compatibility. Every mixin has a comment naming the vanilla behaviour it changes and its fallback.
- **Stonecutter:** loader/version code uses `/*? if fabric {*/ … /*?} else {*/ … /*?}*/` like autodrop. Each
  `//? if` directive stays on its own line above the line it guards. Commit with `26.3-fabric` active; after
  touching directives, switch to another target and back and check `git diff` is empty. Edit only `src/`.
- **Hot paths** (anything per frame, per entity or per part): no allocation, no boxing, no capturing lambdas, no
  Kotlin collection operators. Use `@JvmField`, primitive arrays and LWJGL `MemoryUtil` for off-heap writes.
  Check with a JFR allocation profile before each gate.
- **Render thread only.** Assert it at module entry points.
- **Logging:** logger `Mobium`; repeated warnings through a log-once helper.
- **Formatting:** `.editorconfig` (4 spaces, 120 columns), Kotlin official code style.
- **Config changes:** bump `CONFIG_VERSION` and add a migration branch for any format change.
- **Translations:** `en_us.json` and `de_de.json` keep identical keys.

---

## 9. Testing and verification

| Check | Who / where | When |
| --- | --- | --- |
| `./gradlew build` both targets | Claude Code + CI | Every PR |
| Unit tests (pure Kotlin: ring buffer, budget, record packing, expand reference math, config migration) | CI | Every PR |
| Fabric client gametests: scene smoke tests and screenshots | CI under xvfb with Mesa's software OpenGL (supports compute, so correctness of the compute path can be checked; speed cannot) | Every PR from M2 |
| In-game self-check on both dev clients (`scripts/self_check.py`): the PR's *How to verify* as scripted checks, pixel comparisons and screenshots | Claude Code, locally | Every rendering PR from M4 |
| Visual check with shader packs and Fresh Animations | Anton | Every rendering PR |
| Benchmarks (`/mobium bench`, all scenes, four setups) | Anton | Gates A–C and before each release |
| NeoForge parity | Anton (manual) + CI build | Every rendering PR |
| Other GPUs (AMD, Intel, macOS for Module 1) | Community testers | Before each release |

Every rendering PR description contains **How to verify** (task to run, scene, keys, expected result) and the
benchmark numbers if performance changed.

---

## 10. Release checklist

`0.1.0-beta1` (M5, Gate B):

- [x] `changelog.md` contains only the upcoming release's notes.
- [x] Gate for the release passed and results committed to `docs/benchmarks/results/` (Gate B 1.17×, 21 Oct 2026).
- [x] `docs/compat.md` updated.
- [x] `README.md` describes what the release does (synced to Modrinth/CurseForge by `release.yml` and
      `readme-modrinth-sync.yml`; links are absolute so they work there).
- [x] `release.yml` builds like CI (the Fabric gametests need Mesa's EGL under xvfb).
- [x] Anton's visual checks of the release's rendering PR (#7, *How to verify* steps 3–5), 22 Oct 2026.
- [x] Modrinth and CurseForge project IDs set in `build.gradle.kts` (`modrinth.projectId`, `curseforge.projectId`),
      `release.yml` and `readme-modrinth-sync.yml` (there also the push trigger on `README.md`, as in autodrop):
      Modrinth `mobium` = `8i5oedw8`, CurseForge `mobium-perf` = `1759818` (22 Oct 2026).
- [x] Repository secrets for `release.yml`: `MODRINTH_API_KEY`, `CURSEFORGE_API_KEY`, `NYON_USERNAME`,
      `NYON_PASSWORD`, `DISCORD_WEBHOOK`, `DISCORD_ROLE_ID` (as in autodrop).
- [x] Run `release.yml` (manual dispatch on `main`): builds, `releaseMod`, Discord post, README sync. Released on
      22 Oct 2026 (run 39831233395).

`0.1.0-beta2` (Module 1 follow-ups, shadow-pass timers):

- [x] `changelog.md` contains only beta2's notes (23 Oct 2026, no tester reports to add).
- [x] `docs/compat.md` and `README.md` mention the shadow-pass timers.
- [x] Self-check on both loaders: every Mobium-vs-vanilla comparison 0 changed pixels (23 Oct 2026: Fabric 74/74
      with Complementary and Fresh Animations, NeoForge 67/67 without the shader pack, see *Current state*).
- [x] Benchmark round A–D on beta2 (with the shadow-pass timers), committed to `docs/benchmarks.md` (23–25 Oct 2026,
      build `a6997e5`, `docs/benchmarks/results/2026-10-23-thinkpad-x9/`).
- [ ] Anton's visual checks of the beta2 PR's *How to verify*.
- [ ] Run `release.yml` (manual dispatch on `main`; Anton).

---

## 11. Open questions

- [x] GitHub repository name (`btwonion/mobium`) and the project slugs: Modrinth `mobium`, CurseForge `mobium-perf`
      (created by Anton on 22 Oct 2026).
- [ ] Which shader packs are the official test set (suggest one heavy and one light pack). Heavy: Complementary
      Reimagined r5.9.3 (Anton's pick, 19 Oct 2026); light: open.
- [ ] Which GPU/OS Anton tests on; who covers AMD/Intel. Anton: ThinkPad X9-14 Gen 1 (Core Ultra 7 258V, Arc 140V
      iGPU, Fedora 44, Mesa), external 1920×1080 monitor for benchmarks. AMD and Nvidia: open.
- [ ] Whether to reach out to the Sodium and Iris maintainers before M5 or before M6.

---

## 12. References

- House structure: `btwonion/autodrop`, `btwonion/better-boat-movement`, `btwonion/magnetic`
- Stonecutter: https://stonecutter.kikugie.dev/ · modstitch: https://github.com/isXander/modstitch
- Fabric client gametests: https://docs.fabricmc.net/develop/automatic-testing
- Sodium Config API: `net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint` (Maven `https://maven.caffeinemc.net/releases`)
- 26.2 → 26.3 rendering changes: https://docs.neoforged.net/primer/docs/26.3/
- Prior art: Accelerated Rendering (https://github.com/Argon4W/AcceleratedRendering), Flywheel/Vanillin,
  Enhanced Block Entities, CrankShaft
