# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Mobium is a client-side Minecraft performance mod (Kotlin, mixins in Java) that draws entities and block entities
from GPU buffers instead of rebuilding them on the CPU every frame. One source tree targets both loaders
(Fabric/Quilt and NeoForge) via [Stonecutter](https://stonecutter.kikugie.dev/); [modstitch](https://github.com/isXander/modstitch)
hides the Loom/ModDevGradle differences. Targets are declared in `settings.gradle.kts` (`26.3` × fabric/neoforge).
The structure follows `btwonion/autodrop`; check it first when unsure how something is done.

**Start with `PLAN.md`.** It is the plan of the work ahead: work through its milestones in order, tick its checkboxes
as tasks land, and treat its gates as hard stops. Its *Current state* section at the top says where the work stands
and what to do next; keep it up to date at the end of every session. How Mobium works today is in
`docs/architecture.md`; keep it true when the code changes.

## Commands

Requires JDK 25 (same as CI).

```bash
./gradlew build                            # all targets; what CI runs. Jars land in versions/<target>/build/libs/
./gradlew :26.3-fabric:build               # a single target
./gradlew :26.3-fabric:test                # unit tests only (src/test, pure Kotlin)
./gradlew :26.3-fabric:runClient           # dev client, Fabric (Sodium, Iris, EMF, ETF on the dev classpath)
./gradlew :26.3-neoforge:runMainClient     # dev client, NeoForge (game dir: run/)
./gradlew :26.3-fabric:runClientGameTest   # Fabric client gametests (scene smoke tests, screenshots)
./gradlew "Set active project to 26.3-neoforge"   # switch the version the src/ comments are written for
./gradlew :26.3-fabric:benchKit            # mods of the four benchmark setups + datapack -> versions/26.3-fabric/build/bench-kit
./gradlew :26.3-fabric:runBenchWorld       # saves a ready benchmark world -> versions/26.3-fabric/build/bench-world
python3 benchmark/prism_instances.py       # creates/updates the Prism instances "Mobium Bench A–D" from the two above
python3 benchmark/bench_round.py           # unattended round A–D in those instances (about 3 h; --quick, --dry-run),
                                           # CSVs pushed to btwonion/mobium-benchmarks (checkout ../mobium-benchmarks)
python3 benchmark/self_check.py [fabric|neoforge]   # in-game self-check of a PR's "How to verify" on the dev clients
python3 benchmark/self_check.py fabric --api vulkan # the same on Vulkan, with the validation layer
./gradlew :26.3-fabric:runClient -Pmobium.graphicsBackend=vulkan  # any run task on Vulkan; -Pmobium.vulkanValidation
```

`benchmark/self_check.py` starts each dev client with a request file; `debug/selfcheck/SelfCheck.kt` (dev environments
only) then walks through the checks inside the game: screens opened the loader's way, both languages, the Sodium page,
and in the benchmark world key presses, commands and pixel comparisons against vanilla. It writes
`<game dir>/mobium-selfcheck/report.txt` plus screenshots, restores every option it touched, and closes the game.
Extend the scenario for every rendering PR, run it on both loaders, and read the screenshots before asking Anton.
`--mode gallery` runs only the `cache_gallery` part (every cached type, effects, shader pack, Fresh Animations) and is
the quick loop while working on the static cache; failed count checks name why an object was not cached. `--mode
parts` is the same for Module 2 (GPU vertices against the CPU's, pictures, in `mob_pen_live` and `part_gallery` with
the four setups' models, both expand stages), `--mode profile-mobs [--scene <scene>]` measures Module 2 off, on and
without vertices, `--mode expand-cost [--scene <scene>]` its fixed cost per frame (compute against the vertex stage,
CPU time per step, GPU timestamps of the frame and of each pass; `ExpandProfile`).

In game: `/mobium bench <scene> <seconds> [setup]` records a benchmark, `/mobium suite` runs every scene of a setup
and `/mobium gatea` / `gateb` / `gatec` run Gates A–C unattended (`/mobium stages` compares Module 2's two expand
stages; `/mobium stop` aborts), scenes come from the
`benchmark/mobium-bench` datapack (`python3 benchmark/generate.py` regenerates it), and the F3 entry `mobium:timings`
shows the stage timers. Protocol and results: `docs/benchmarks.md`. Settings: Mod Menu (Fabric), the mod list
(NeoForge) or Mobium's page in Sodium's video settings. The A/B key switches all of Mobium off and on live; key
bindings are physical key positions (SDL scancodes), and its default is the key right of P: `]` on a US keyboard,
`+` on a German one (the action bar names the bound key; rebind under Controls → Key Binds → Mobium). The F3 entry
`mobium:modules` shows each module's state and why it is off, and in dev environments `/mobium debug fail <module>`
forces a module failure (one toast, module on vanilla until restart) and `/mobium debug validate` compares a frame
with Mobium against the next with vanilla.

On the Fabric target `check` depends on `runClientGameTest`, so `build` starts the game: locally a window opens and
closes by itself, CI runs it under `xvfb-run`. Gametests live in `src/gametest` (Fabric only, mod id `mobium-test`);
screenshots land in `versions/26.3-fabric/build/run/clientGameTest/screenshots/`.

## Ground rules

- **The one rule:** Mobium may change how geometry reaches the GPU, never what ends up on screen.
- **You cannot see the game.** Every PR that touches rendering ends with a "How to verify" section for Anton: the
  Gradle task, the benchmark scene, the keys to press, and what correct and broken look like.
- **Never guess the Minecraft API.** 26.x is unobfuscated; read the decompiled source and record findings in
  `docs/vanilla-notes.md` (a new section at the end; the code references its section numbers). Plan entries marked
  *(verify)* are expectations, not facts.
- **Vanilla first.** Every feature has a config switch, a per-type switch and a runtime fallback to the vanilla
  path. New hooks ship disabled until their screenshot tests pass.
- **Must-have compatibility:** Iris shader packs and EMF/ETF with Fresh Animations. Never bypass `setupAnim`.
- **Hot paths** (per frame, per entity, per part): no allocation, no boxing, no capturing lambdas, no Kotlin
  collection operators; use `@JvmField`, primitive arrays and LWJGL `MemoryUtil`. Render thread only.
- **Mixins** stay thin: inject and forward to Kotlin, handler methods prefixed `mobium$`, MixinExtras over
  `@Redirect`/`@Overwrite`, a comment naming the vanilla behaviour changed and its fallback.

## Stonecutter

- Version- and loader-specific code lives in comments such as `//? if >26.3` and
  `/*? if fabric {*/ … /*?} else {*/ … /*?}*/`. Code for inactive targets is commented out in `src/`.
- Each `//? if` directive must stay on its own line, directly above the line it guards. After touching directives,
  switch to another target and back and check `git diff` is empty.
- The repo is committed with `26.3-fabric` active (`stonecutter.gradle.kts`); switch back before committing.
- Edit only `src/`; `versions/*/build/generated/stonecutter` is generated.
- Shared mod properties are in `gradle.properties`; per-target Minecraft version, version range and dependency
  versions (`vers.deps.*`, `modstitch.platform`) are in `versions/<target>/gradle.properties`.

## Architecture

`docs/architecture.md` describes both modules step by step, the core, the GPU layer, the package map and the design
decisions; `docs/vanilla-notes.md` holds every vanilla, Sodium, Iris and EMF detail the code relies on (§10 is the
hook table, §18 the Vulkan backend). Every module asks
`core/ModuleStates` whether it may run (resolved by the pure `ModuleRules` from config, backend, compute, A/B key,
overlapping mods and failures) and reports exceptions to `core/Fallback`.

- **Module 1, static cache (`cache/`):** captures unchanged block entities and entities through vanilla's own submit
  and prepare (`Capture.kt`, in Iris's formats while a pack is on), keeps them in per-section buckets on the GPU
  (`gpu/Gpu.backend`, Renderpearl) and draws them at the end of `PreparedFrame.executeSolid`; `CachePolicy` and
  `EntityPolicy` decide per type and compare each object's inputs every frame, `LightStamps` notices light changes.
- **Module 2, GPU part transform (`parts/`):** wraps `ModelPart.compile` and EMF's `EMFModelPart.compile`. A part
  whose cubes it knows (vanilla cubes, written as vanilla or as Sodium would; EMF's `EMFCube`) reserves its vertices in
  the staged builder and writes records (`PartBatch`, meshes in `MeshPool`); after `StagedVertexBuffer.upload()`
  `ExpandPass` runs `assets/mobium/shaders/expand_parts.comp` (raw GL 4.3, `gpu/gl/GlCompute`), which writes exactly
  the bytes the CPU path would have, Iris's extension included: compiled as a vertex shader drawn as points without a
  shader pack (Mesa's iris puts a compute dispatch in a batch of its own, a fixed cost per frame), as a compute
  dispatch with one (`ExpandPass.stage`). `PartValidation` compares them byte for byte: every part in the dev
  validation, the first parts of each shader path in normal play (`PartCheck`, which switches Module 2 off on a
  difference); any change to the shader or the records must keep `--mode parts` at 0 differing vertices in both stages.
- **Backends:** the static cache runs on OpenGL and Vulkan, Module 2 on OpenGL only so far (`OffReason.BACKEND` on
  Vulkan, `ModuleRules.runsOn`); Module 2 on Vulkan is `PLAN.md` M7.

## Dev environment

- **Worktrees** lack the gitignored dev run folders. Copy `versions/26.3-fabric/run/` and `run/` (`options.txt`,
  `config`, `saves`, `shaderpacks`, `resourcepacks`), `versions/26.3-fabric/build/bench-world` and
  `versions/26.3-fabric/build/bench-packs` from the main checkout before running the self-check or the Prism script.
- **Test assets** (gitignored): `shaderpacks/ComplementaryReimagined_r5.9.3.zip` and
  `resourcepacks/FreshAnimations_v1.10.3.zip` in `versions/26.3-fabric/run/` and `run/`; the self-check uses them when
  present.
- **Vulkan:** `--api vulkan` needs Khronos' validation layer (Fedora `vulkan-validation-layers`; without root,
  `VK_ADD_LAYER_PATH`, `docs/vanilla-notes.md` §21). Iris stays off the dev classpath on Vulkan, where it stops the
  game at startup.
- **Known issue outside Mobium:** Iris 1.11.7 on NeoForge crashes with Complementary Reimagined r5.9.3 in
  `CenterDepthSampler`, also with Mobium off (`self_check.py neoforge --mode iris-without-mobium`); the self-check
  skips the shader pack there.
- **Benchmark data** lives in `../mobium-benchmarks` (`btwonion/mobium-benchmarks`); `bench_round.py` pulls and pushes
  it. Rebuild the Prism instances after code changes as `docs/benchmarks.md` (*Tools*) says.

## Config and translations

Config is stored with konfig at `config/mobium.json` (`{"version", "config"}`). A format change must bump
`CONFIG_VERSION` and add a migration branch; unknown keys must keep failing (don't enable `ignoreUnknownKeys`).
`assets/mobium/lang/en_us.json` and `de_de.json` must keep the same keys.

## Pull requests

One branch from `main` per piece of work, one PR against `main`; merging is Anton's call. PRs are squash-merged
(Anton, 28 Oct 2026): the PR title becomes the commit title on `main` and the branch's commit messages its body, so
write both for `main`'s history. A benchmark build id from a branch commit is not on `main` after the merge;
`git fetch origin pull/<n>/head` brings it back.

## Releases

`changelog.md` holds only the notes for the upcoming release; publishing and the Discord post read it. Keep it short
(Anton, 28 Oct 2026): one line of at most 120 characters per entry, saying what changed for players, not how or why;
numbers, causes and details go in the PR, `docs/benchmarks.md` or `docs/compat.md`. Releases run from the manually
dispatched `release.yml` workflow (`./gradlew releaseMod`), only after the matching gate in `PLAN.md` has passed;
Anton runs it, never Claude Code. The checklist is `PLAN.md`'s *Release checklist*.
