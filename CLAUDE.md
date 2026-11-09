# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Mobium is a client-side Minecraft performance mod (Kotlin, mixins in Java) that draws entities and block entities
from GPU buffers instead of rebuilding them on the CPU every frame. One source tree targets both loaders
(Fabric/Quilt and NeoForge) via [Stonecutter](https://stonecutter.kikugie.dev/); [modstitch](https://github.com/isXander/modstitch)
hides the Loom/ModDevGradle differences. Targets are declared in `settings.gradle.kts` (`26.3` × fabric/neoforge).
The structure follows `btwonion/autodrop`; check it first when unsure how something is done.

**Start with `PLAN.md`.** It holds the current target (Vulkan): tick its checkboxes as tasks land and treat its gate as
a hard stop. Its *Current state* section at the top says where the work stands and what to do next; keep it up to
date at the end of every session. How Mobium works is in `docs/architecture.md`; keep it true when the code changes.

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

Extend the self-check's scenario (`debug/selfcheck/SelfCheck.kt`) for every rendering PR, run it on both loaders, and
read its screenshots (`<game dir>/mobium-selfcheck/`) before asking the maintainer to look; `--mode gallery` and
`--mode parts` are the quick loops for the static cache and Module 2. On the Fabric target `build` runs the client
gametests, so a game window opens and closes by itself. The self-check's modes, the gametests and the unit tests:
`docs/architecture.md` (*Tests*); in-game commands, F3 entries and the A/B key: *Debug and measurement* there;
benchmark commands, protocol and results: `docs/benchmarks.md`.

## Ground rules

- **The one rule:** Mobium may change how geometry reaches the GPU, never what ends up on screen.
- **You cannot see the game.** Every PR that touches rendering ends with a "How to verify" section for the maintainer:
  the Gradle task, the benchmark scene, the keys to press, and what correct and broken look like; plus the benchmark
  numbers if performance changed.
- **Never guess the Minecraft API.** 26.x is unobfuscated; read the decompiled source and record findings in
  `docs/vanilla-notes.md` (a new section at the end; the code references its section numbers). Plan entries marked
  *(verify)* are expectations, not facts.
- **Current state only.** Docs, `PLAN.md` and comments say how things are now: no names, no who decided what or when,
  no dates, no history, and no Mobium version numbers (when a release is due, look up the latest with
  `gh release list`).
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
hook table, §18 the Vulkan backend). Every module asks `core/ModuleStates` whether it may run (resolved by the pure
`ModuleRules` from config, backend, compute, A/B key, overlapping mods and failures) and reports exceptions to
`core/Fallback`. Module 2's expand pass must write exactly the bytes the CPU path would: any change to
`expand_parts.comp` or the part records keeps `--mode parts` at 0 differing vertices in both expand stages and on
Vulkan.

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

One branch from `master` per piece of work, one PR against `master`; merging is the maintainer's call. PRs are
squash-merged: the PR title becomes the commit title on `master` and the branch's commit messages its body, so write
both for `master`'s history. A benchmark build id from a branch commit is not on `master` after the merge;
`git fetch origin pull/<n>/head` brings it back.

## Releases

`changelog.md` holds only the notes for the upcoming release; publishing and the Discord post read it. Keep it short:
one line of at most 120 characters per entry, saying what changed for players, not how or why;
numbers, causes and details go in the PR, `docs/benchmarks.md` or `docs/compat.md`. Releases run from the manually
dispatched `release.yml` workflow (`./gradlew releaseMod`), only after the matching gate in `PLAN.md` has passed;
the maintainer runs it, never Claude Code. Releases go to Modrinth `mobium` (`8i5oedw8`) and CurseForge `mobium-perf`
(`1759818`). The next version follows the latest release (`gh release list`): a new module is a new minor version,
betas count up `mod.beta`, `mod.beta=0` publishes as stable.

Release checklist (copy it into the release's PR and tick it there):

- [ ] The version in `gradle.properties` (`mod.major-version`, `mod.beta`): the next after the latest release.
- [ ] The gate passed, results in `docs/benchmarks.md`, raw data in `btwonion/mobium-benchmarks`.
- [ ] `changelog.md` holds only this release's notes.
- [ ] `docs/compat.md` updated; `README.md` describes the release. README changes merge right before `release.yml`:
      `readme-modrinth-sync.yml` publishes every README change on `master` to Modrinth at once.
- [ ] Self-check on both loaders (regular, `--mode parts`, `--mode benchmark`) and the gametests; 0 changed pixels,
      0 differing vertices.
- [ ] A benchmark round A–D (`python3 benchmark/bench_round.py`) if rendering changed since the last round.
- [ ] The maintainer's visual checks of the release's rendering PRs (*How to verify*).
- [ ] The maintainer's go, then `release.yml` on `master`.
- [ ] Afterwards a PR that empties `changelog.md` and updates `PLAN.md`'s *Current state*.
