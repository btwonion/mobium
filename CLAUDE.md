# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Mobium is a client-side Minecraft performance mod (Kotlin, mixins in Java) that draws entities and block entities
from GPU buffers instead of rebuilding them on the CPU every frame. One source tree targets Fabric/Quilt and NeoForge
via [Stonecutter](https://stonecutter.kikugie.dev/) and [modstitch](https://github.com/isXander/modstitch); the
targets are in `settings.gradle.kts`. The structure follows `btwonion/autodrop`; check it first when unsure.

**Start with `PLAN.md`:** tick its checkboxes as tasks land, treat its gate as a hard stop, and keep its *Current
state* up to date at the end of every session. How Mobium works: `docs/architecture.md` (keep it true when the code
changes); every vanilla, Sodium, Iris and EMF detail the code relies on: `docs/vanilla-notes.md` (§10 the hook table);
benchmarks: `docs/benchmarks.md`; releases: `docs/releasing.md`.

## Commands

Requires JDK 25 (same as CI).

```bash
./gradlew build                            # all targets; what CI runs. Jars land in versions/<target>/build/libs/
./gradlew :26.3-fabric:test                # unit tests only (src/test, pure Kotlin)
./gradlew :26.3-fabric:runClient           # dev client, Fabric (NeoForge: :26.3-neoforge:runMainClient, game dir run/)
./gradlew :26.3-fabric:runClientGameTest   # Fabric client gametests
./gradlew "Set active project to 26.3-neoforge"   # switch the version the src/ comments are written for
python3 benchmark/self_check.py [fabric|neoforge] [--api vulkan] [--minecraft 26.4] [--mode …]   # in-game self-check
```

Run tasks take `-Pmobium.graphicsBackend=opengl|vulkan` and `-Pmobium.vulkanValidation`. Extend the self-check
(`debug/selfcheck/SelfCheck.kt`) for every rendering PR, run it on both loaders and read its screenshots
(`<game dir>/mobium-selfcheck/`) before asking the maintainer to look; `--mode gallery` and `--mode parts` are the
quick loops for the static cache and Module 2. On the Fabric target `build` runs the gametests, so a game window
opens and closes by itself. Self-check modes and tests: `docs/architecture.md` (*Tests*).

## Ground rules

- **The one rule:** Mobium may change how geometry reaches the GPU, never what ends up on screen.
- **You cannot see the game.** Every PR that touches rendering ends with a "How to verify" section for the maintainer:
  the Gradle task, the benchmark scene, the keys to press, and what correct and broken look like; plus the benchmark
  numbers if performance changed.
- **Never guess the Minecraft API.** 26.x is unobfuscated; read the decompiled source and record findings in
  `docs/vanilla-notes.md` (a new section at the end; the code references section numbers, so never renumber). Plan
  entries marked *(verify)* are expectations, not facts.
- **Current state only.** Docs, `PLAN.md` and comments say how things are now: no names, no who decided what or when,
  no dates, no history, no Mobium version numbers.
- **One place per fact.** Test results and measurements go in the PR (and `docs/compat.md` / `docs/benchmarks.md`),
  not in `vanilla-notes.md` or comments; elsewhere point to the one place instead of repeating it.
- **Comments** say why, not what: the invariant, the vanilla behaviour matched, the trap. Background longer than a few
  lines goes in the docs with a pointer (`docs/vanilla-notes.md` §n).
- **Vanilla first.** Every feature has a config switch, a per-type switch and a runtime fallback to the vanilla
  path. New hooks ship disabled until their screenshot tests pass.
- **Must-have compatibility:** Iris shader packs and EMF/ETF with Fresh Animations. Never bypass `setupAnim`.
- **Hot paths** (per frame, per entity, per part): no allocation, no boxing, no capturing lambdas, no Kotlin
  collection operators; use `@JvmField`, primitive arrays and LWJGL `MemoryUtil`. Render thread only.
- **Mixins** stay thin: inject and forward to Kotlin, handler methods prefixed `mobium$`, MixinExtras over
  `@Redirect`/`@Overwrite`, a comment naming the vanilla behaviour changed and its fallback.
- **Module 2** must write exactly the bytes the CPU path would: any change to `expand_parts.comp` or the part records
  keeps `--mode parts` at 0 differing vertices in both expand stages and on Vulkan.

## Stonecutter

- Version- and loader-specific code lives in comments such as `//? if >26.3` and
  `/*? if fabric {*/ … /*?} else {*/ … /*?}*/`. Code for inactive targets is commented out in `src/`.
- Each `//? if` directive must stay on its own line, directly above the line it guards. After touching directives,
  switch to another target and back and check `git diff` is empty.
- The repo is committed with `26.3-fabric` active (`stonecutter.gradle.kts`); switch back before committing.
- Edit only `src/`; `versions/*/build/generated/stonecutter` is generated.
- Shared mod properties are in `gradle.properties`; per-target Minecraft version, version range and dependency
  versions (`vers.deps.*`, `modstitch.platform`) in `versions/<target>/gradle.properties`.
- A class that only moved package between versions is swapped by a string replacement in `build.gradle.kts`
  (`stonecutter { replacements }`).

## Dev environment

- **Worktrees** lack the gitignored dev run folders. Copy `versions/26.3-fabric/run/` and `run/` (`options.txt`,
  `config`, `saves`, `shaderpacks`, `resourcepacks`), `versions/26.3-fabric/build/bench-world` and
  `versions/26.3-fabric/build/bench-packs` from the main checkout before running the self-check or the Prism script.
- **Test assets** (gitignored): `shaderpacks/ComplementaryReimagined_r5.9.3.zip` and
  `resourcepacks/FreshAnimations_v1.10.3.zip` in `versions/26.3-fabric/run/` and `run/`; the self-check uses them.
- **Vulkan:** `--api vulkan` needs Khronos' validation layer (without root: `VK_ADD_LAYER_PATH`, §21). Iris stays off
  the dev classpath on Vulkan chosen by launch argument; `--from-options` keeps it (§21).
- **Iris on NeoForge** crashes with Complementary Reimagined, Mobium or not (`docs/compat.md`); the self-check skips
  the pack there.
- **26.4 target** (`26.4-fabric`, Fabric only): builds against one snapshot and runs without the compat mods; name the
  API in every run, and lavapipe needs `-Pmobium.vulkanCpuDevice`. For a new snapshot follow §28 and bring §20 and §28
  up to it: they describe the snapshot the target builds against, never earlier ones.
- **Benchmark data** lives in `../mobium-benchmarks` (`btwonion/mobium-benchmarks`).

## Config and translations

Config is stored with konfig at `config/mobium.json` (`{"version", "config"}`). A format change must bump
`CONFIG_VERSION` and add a migration branch; unknown keys must keep failing (don't enable `ignoreUnknownKeys`).
`assets/mobium/lang/en_us.json` and `de_de.json` must keep the same keys.

## Pull requests and releases

One branch from `master` per piece of work, one PR against `master`; merging is the maintainer's call. PRs are
squash-merged: the PR title becomes the commit title on `master` and the branch's commit messages its body, so write
both for `master`'s history. A benchmark build id from a branch commit is not on `master` after the merge;
`git fetch origin pull/<n>/head` brings it back.

`changelog.md` holds only the upcoming release's notes: one line of at most 120 characters per entry, what changed for
players, not how or why. Releases run from `release.yml`, which only the maintainer runs, never Claude Code; the
version rules and the checklist are in `docs/releasing.md`.
