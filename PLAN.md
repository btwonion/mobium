# Mobium — Plan

The current target: Mobium on Minecraft's Vulkan backend. Tick the checkboxes as tasks land and treat Gate D as a hard
stop. Keep *Current state* up to date at the end of every session.

How Mobium works is in `docs/architecture.md`, the Vulkan backend's details in `docs/vanilla-notes.md` §18–§23,
measurements in `docs/benchmarks.md`, compatibility in `docs/compat.md`.

---

## Current state

- The static cache runs on Vulkan in the released mod; both modules stay off while Vitrail is installed.
- Module 2 runs on Vulkan with 0 differing vertices (self-check on both loaders, gametests on lavapipe in CI), but only
  with `-Dmobium.parts.vulkan`, which dev runs on Vulkan set.
- Module 2 for items (dropped items, items in hands, frames and displays) is on `master`, not released yet; its
  results are in `docs/benchmarks.md` (*Dropped items*, *Latest results*).
- Next: the maintainer's visual check of Module 2 on Vulkan (*How to verify* of #39), then the last box of step 5
  (Module 2 on Vulkan for players), then step 6.

---

## M7 · Vulkan (on 26.3) → Gate D

**Goal:** both modules on Minecraft's Vulkan backend, with the same guarantee as on OpenGL: the same pixels and, for
Module 2, the same vertex bytes as the game's own path.

1. **26.4.** `26.4-snapshot-6` still has no compute and no storage buffers in Renderpearl and drops push descriptors
   (§20). Check the newest snapshot again before porting to 26.4.
2. **Tooling.** Done: dev clients, self-check (`--api vulkan`) and gametests on Vulkan with the validation layer,
   `ExpandProfile` on Renderpearl's timestamps (§21).
3. **Module 1 on Vulkan.** Done: pixel-identical on both loaders, no validation messages from Mobium (§22).
4. **Module 2's dispatch on Vulkan.** Done: `gpu/vk/`, `parts/VulkanExpand`, three checked `require = 0` mixins (§23).
5. **Checks on Vulkan.**
   - [x] `PartValidation` and `PartCheck` on Vulkan; self-check `--mode parts` on Vulkan, both loaders: 0 differing
         vertices, pictures identical, no validation messages from Mobium.
   - [x] CI: the Fabric gametests a second time on Mesa's lavapipe.
   - [ ] Module 2 on Vulkan for players: `ModuleRules` without `-Dmobium.parts.vulkan`, the property and its wiring
         removed (`build.gradle.kts`, `VulkanSupport.allowed`, `ModuleEnvironment.vulkanParts`), after the visual check
         in game (*How to verify* of #39).
6. **Measure and document.**
   - [ ] Benchmark round on Vulkan in the Prism instances for setups A and C (the graphics API set in their options),
         plus `/mobium gatec` on Vulkan and OpenGL in one session each. B and D need Iris and stay on OpenGL
         (*Decisions*).
   - [ ] `docs/compat.md` (Vulkan rows), README (*Does Mobium work with Vulkan?*, which still says Mobium stays off on
         Vulkan), `changelog.md` (no release notes have mentioned Vulkan yet).

**Gate D:** on Vulkan in 26.3, on both loaders, zero screenshot differences and 0 differing vertices (self-check,
gametests on lavapipe), no validation-layer messages from Mobium; `mob_pen_live` no slower with Module 2 than without
(at most 1.03×) in setups A and C; `control` with Mobium no slower than without (at most 1.03×). Then release. If the
gate fails, stop, write up why in the PR and agree on the next step first.

**Decisions:**
- **Iris on Vulkan: not part of M7.** No Iris release runs on Vulkan (1.11.7 is the newest for 26.3 and stops the game
  there, §21; the request is open in Iris's tracker, with no Vulkan branch), and Aperture, its Vulkan successor, has
  no release or date. Setups B and D are measured on OpenGL only. Check again when an Iris or Aperture release runs on
  Vulkan.
- **Vitrail: both modules stay off while it is installed.** It is a beta (0.13.0-beta for 26.2 and 26.3, Fabric and
  NeoForge) whose open work rewrites how it writes entity vertices, where Mobium hooks too (§22). Checked after
  Gate D against its then newest release, as its own piece of work: its dev client with an OptiFine-format pack, the
  self-check's gallery and `--mode parts` on Vulkan; identical pictures and 0 differing vertices lift
  `OffReason.UNTESTED`.
- **GPUs without `shaderFloat64`: Module 2 gets a shader without double precision, the next target after Gate D.**
  Every Intel GPU from Ice Lake's Iris Plus through Iris Xe (11th to 14th gen Core) to Arrow Lake's Arc 140T, the Arc
  A-series, Snapdragon X and every Mac lack the feature (`docs/compat.md`, *Known differences and limits*), so Module 2 stays off on Vulkan there; 26.4
  makes Vulkan the default. The expand pass uses doubles for correctly rounded square roots and divisions
  (`Capabilities.glCompute`); exact integer arithmetic (`umulExtended`) instead, still 0 differing vertices in every
  setup and on both backends. On OpenGL the same GPUs report core 4.0 and emulate doubles in the driver; how much
  that costs Module 2 there is not measured *(verify)*.
