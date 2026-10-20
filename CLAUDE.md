# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Mobium is a client-side Minecraft performance mod (Kotlin, mixins in Java) that draws entities and block entities
from GPU buffers instead of rebuilding them on the CPU every frame. One source tree targets both loaders
(Fabric/Quilt and NeoForge) via [Stonecutter](https://stonecutter.kikugie.dev/); [modstitch](https://github.com/isXander/modstitch)
hides the Loom/ModDevGradle differences. Targets are declared in `settings.gradle.kts` (`26.3` × fabric/neoforge).
The structure follows `btwonion/autodrop`; check it first when unsure how something is done.

**Start with `PLAN.md`.** It is the build plan: work through its milestones in order, tick its checkboxes as tasks
land, and treat its gates as hard stops. Its *Current state* section at the top says where the work stands and what to
do next; keep it up to date at the end of every session.

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
python3 scripts/self_check.py [fabric|neoforge]   # in-game self-check of a PR's "How to verify" on the dev clients
```

`scripts/self_check.py` starts each dev client with a request file; `debug/selfcheck/SelfCheck.kt` (dev environments
only) then walks through the checks inside the game: screens opened the loader's way, both languages, the Sodium page,
and in the benchmark world key presses, commands and pixel comparisons against vanilla. It writes
`<game dir>/mobium-selfcheck/report.txt` plus screenshots, restores every option it touched, and closes the game.
Extend the scenario for every rendering PR, run it on both loaders, and read the screenshots before asking Anton.
`--mode gallery` runs only the `cache_gallery` part (every cached type, effects, shader pack, Fresh Animations) and is
the quick loop while working on the static cache; failed count checks name why an object was not cached.

In game: `/mobium bench <scene> <seconds> [setup]` records a benchmark, `/mobium suite` runs every scene of a setup
and `/mobium gatea` runs Gate A unattended (`/mobium stop` aborts), scenes come from the
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
- **Never guess the 26.3 API.** 26.x is unobfuscated; read the decompiled source and record findings in
  `docs/vanilla-notes.md`. Plan entries marked *(verify)* are expectations, not facts.
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

See `PLAN.md` §5 (hook points, package map, part-record layout) and §6 (runtime fallback and compat behaviour).
Replace this paragraph with a short description of the real architecture once Modules 1 and 2 exist. Until then:
every module asks `core/ModuleStates` whether it may run (resolved by the pure `ModuleRules` from config, backend,
shader pack, A/B key, overlapping mods and failures), reports exceptions to `core/Fallback`, and allocates GPU memory
through `gpu/Gpu.backend`.

## Config and translations

Config is stored with konfig at `config/mobium.json` (`{"version", "config"}`). A format change must bump
`CONFIG_VERSION` and add a migration branch; unknown keys must keep failing (don't enable `ignoreUnknownKeys`).
`assets/mobium/lang/en_us.json` and `de_de.json` must keep the same keys.

## Releases

`changelog.md` holds only the notes for the upcoming release; publishing and the Discord post read it. Releases
run from the manually dispatched `release.yml` workflow (`./gradlew releaseMod`), only after the matching gate in
`PLAN.md` has passed.
