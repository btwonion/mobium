# Mobium — Plan

The current target: Mobium on Minecraft's Vulkan backend. Tick the checkboxes as tasks land and treat Gate D as a hard
stop. Keep *Current state* up to date at the end of every session.

How Mobium works is in `docs/architecture.md`, the Vulkan backend's details in `docs/vanilla-notes.md` §18–§23,
measurements in `docs/benchmarks.md`, compatibility in `docs/compat.md`.

---

## Current state

- Both modules run on Vulkan on `master`, Module 2 with doubles where the GPU has `shaderFloat64` and with integers
  elsewhere; the released mod has only the static cache there. The static cache stays off while Vitrail is installed.
- Module 2 on Vulkan writes 0 differing vertices (self-check on both loaders, gametests on lavapipe in CI).
- Module 2 for items (dropped items, items in hands, frames and displays) is on `master`, not released yet; its
  results are in `docs/benchmarks.md` (*Dropped items*, *Latest results*).
- `self_check.py --mode parts --api vulkan` passes on both loaders with items: 0 differing vertices, pictures
  identical, no validation messages from Mobium.
- A `26.4-fabric` target builds against `26.4-snapshot-8` and is not released (Fabric only: NeoForge has no 26.4
  build; nor do Sodium, Iris, EMF, ETF and Vitrail). Its gametests pass on OpenGL and on Vulkan: 0 differing
  vertices, pictures as on 26.3 (`docs/vanilla-notes.md` §28).
- Next: step 6, the benchmark round on Vulkan first.

---

## M7 · Vulkan (on 26.3) → Gate D

**Goal:** both modules on Minecraft's Vulkan backend, with the same guarantee as on OpenGL: the same pixels and, for
Module 2, the same vertex bytes as the game's own path.

1. **26.4.** `26.4-snapshot-8` still has no compute and no storage buffers in Renderpearl and drops push descriptors;
   everything Module 2's Vulkan path hooks or calls is as in 26.3. New around it: CPU devices such as lavapipe need
   `-Drenderpearl.vulkan.allowcpudevice`, and "Default" picks Vulkan or OpenGL at random at each start (§20). The
   `26.4-fabric` target follows the snapshots (§28); check every hook again with each new one.
2. **Tooling.** Done: dev clients, self-check (`--api vulkan`) and gametests on Vulkan with the validation layer,
   `ExpandProfile` on Renderpearl's timestamps (§21).
3. **Module 1 on Vulkan.** Done: pixel-identical on both loaders, no validation messages from Mobium (§22).
4. **Module 2's dispatch on Vulkan.** Done: `gpu/vk/`, `parts/VulkanExpand`, three checked `require = 0` mixins (§23).
5. **Checks on Vulkan.**
   - [x] `PartValidation` and `PartCheck` on Vulkan; self-check `--mode parts` on Vulkan, both loaders: 0 differing
         vertices, pictures identical, no validation messages from Mobium.
   - [x] CI: the Fabric gametests a second time on Mesa's lavapipe.
   - [x] Module 2 on Vulkan for players, after the visual check in game: `ModuleRules` lets it run there like the
         static cache; while an installed mod keeps it off, the device and its buffers stay vanilla's.
6. **Measure and document.**
   - [ ] Benchmark round on Vulkan in the Prism instances for setups A and C (the graphics API set in their options),
         plus `/mobium gatec` on Vulkan and OpenGL in one session each. B and D need Iris and stay on OpenGL
         (*Decisions*).
   - [x] `docs/compat.md` (Vulkan rows), README (*Does Mobium work with Vulkan?*, which still says Mobium stays off on
         Vulkan), `changelog.md` (no release notes have mentioned Vulkan yet).

**Gate D:** on Vulkan in 26.3, on both loaders, zero screenshot differences and 0 differing vertices (self-check,
gametests on lavapipe), no validation-layer messages from Mobium; `mob_pen_live` no slower with Module 2 than without
(at most 1.03×) in setups A and C; `control` with Mobium no slower than without (at most 1.03×). Then release. If the
gate fails, stop, write up why in the PR and agree on the next step first.

**Decisions:**
- **Iris on Vulkan: not part of M7.** No Iris release draws on Vulkan: 1.11.7 is the newest for 26.3 (none for 26.4);
  with Vulkan chosen in `options.txt` it switches its own mixins off, and started on Vulkan by launch argument it
  stops the game (§21). Iris's branch `26.3-new` (1.12.0, unreleased) has a Vulkan backend and hooks
  classes Module 2 hooks or calls: `VulkanFeatureSets`, `VulkanDevice`, `VulkanCommandEncoder` (§21). Aperture has no
  public repository, release or date. Setups B and D are measured on OpenGL only. When an Iris release draws on Vulkan:
  the self-check with it on Vulkan, both modules, before B and D are measured there. With Iris 1.11.7 installed and
  Vulkan chosen in `options.txt`, Iris stays dormant and both modules draw as vanilla (`self_check.py --from-options`).
- **Vitrail: Module 2 runs beside it, the static cache stays off while it is installed.** Checked with 0.13.0-beta,
  its newest release for 26.3, on both loaders on Vulkan with Complementary Reimagined (§27): without a pack both
  modules match vanilla; with a pack Vitrail's own entity format keeps Module 2 on the CPU path by itself, while the
  cached objects vanish, because the pack's composites cover the level target the cache draws into. Supporting the
  cache there needs its buckets drawn through Vitrail's entity door (`RenderTypeFeatureRenderer.executeGroup`), which
  Vitrail offers no API for. Check again with each Vitrail release (`-Pmobium.vitrail`, `self_check.py --vitrail
  --vitrail-cache`).
- **GPUs without `shaderFloat64`: Module 2 computes in integers there.** Every Intel GPU from Ice Lake's Iris Plus
  through Iris Xe (11th to 14th gen Core) to Arrow Lake's Arc 140T, the Arc A-series, Snapdragon X and every Mac lack
  the feature (`docs/compat.md`, *Known differences and limits*). The expand pass's correctly rounded square roots and
  divisions use doubles where shaders have them and 32-bit integers elsewhere, with the same bytes (*Module 2 without
  64-bit floats*). On OpenGL the same GPUs report core 4.0 and emulate doubles in the driver; how much that costs
  Module 2 there is not measured *(verify)*.

---

## Module 2 without 64-bit floats

Module 2's expand pass used doubles for its correctly rounded square roots and divisions, so on Vulkan GPUs without
`shaderFloat64` (*Decisions*, third entry) Module 2 stayed off. The shader now has a second arithmetic that computes
them bit by bit in 32-bit integers and writes the same bytes (`docs/architecture.md`, *Two arithmetics, same bytes*;
`docs/vanilla-notes.md` §26). Doubles still run where shaders have them; integers elsewhere.

- [x] Integer square root, division and Iris's rsqrt in `expand_parts.comp` (`MOBIUM_INTEGER`), compared with Java for
      every float and 4 · 10⁹ divisions without a difference.
- [x] `ExpandPass.arithmetic` with `forcedArithmetic` for the checks and `-Dmobium.parts.arithmetic=integer`
      (self-check `--arithmetic integer`) for a session as on a GPU without doubles; on Vulkan `VulkanSupport` runs
      Module 2 without `shaderFloat64`; the GPU check samples each arithmetic as its own path.
- [x] Self-check `--mode parts` with both arithmetics in both stages, on OpenGL and Vulkan, both loaders, plus Vulkan
      sessions without `shaderFloat64`; the gametests with both arithmetics: 0 differing vertices, pictures identical,
      no validation messages from Mobium (`docs/vanilla-notes.md` §26, *Results*).
- [x] The maintainer's visual check (*How to verify* of the PR).
- [ ] On a real GPU without `shaderFloat64` (an Intel Iris Xe or Arc A-series laptop on Vulkan): the self-check's
      `--mode parts --api vulkan`, or in normal play F3 `mobium:modules` showing the GPU check without a difference.
- [ ] In a benchmark round: `expand-cost` and `mob_pen_live` with both arithmetics on GPUs with doubles (the Arc 140V,
      an Nvidia and an AMD card). If the integer arithmetic is no slower anywhere, it replaces the double one, and
      the `shaderFloat64` feature set and its mixin go.
