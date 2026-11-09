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
- CI is green on `master`.
- Module 2 for items (dropped items, items in hands, frames and displays) is built on the branch `module2-items`;
  see *Module 2 for items* below for what is left before it merges.
- Next: the last box of step 5 (Module 2 on Vulkan for players), then step 6.

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
   - [ ] Benchmark round A–D on Vulkan in the Prism instances (the graphics API set in their options), plus
         `/mobium gatec` on Vulkan and OpenGL in one session each. Setups whose mods do not run on Vulkan are reported
         as such (B and D need Iris, see below).
   - [ ] `docs/compat.md` (Vulkan rows, GPUs without `shaderFloat64`), README (*Does Mobium work with Vulkan?*),
         `changelog.md` (no release notes have mentioned Vulkan yet).

**Gate D:** on Vulkan in 26.3, on both loaders, zero screenshot differences and 0 differing vertices (self-check,
gametests on lavapipe), no validation-layer messages from Mobium; `mob_pen_live` no slower with Module 2 than without
(at most 1.03×) in every setup that runs on Vulkan; `control` with Mobium no slower than without (at most 1.03×). Then
release. If the gate fails, stop, write up why in the PR and agree on the next step first.

**Open questions:**
- Iris on Vulkan: Iris 1.11.7 stops the game at startup (§21), so setups B and D stay on OpenGL until Aperture (Iris's
  Vulkan successor) or an Iris that runs on Vulkan. Sodium 0.9.2, EMF 3.3.12 and ETF 7.2.6 run on Vulkan in 26.3.
- Vitrail (OptiFine-format shader packs on the Vulkan renderer, mod id `vitrail`): how it changes entity vertex
  formats and the shadow pass. It hooks the same places as Mobium (§22); until checked in game (its dev client with an
  OptiFine-format pack), both modules stay off while it is installed.
- Which GPUs lack `shaderFloat64` on Vulkan (Intel Arc A-series on Windows, Apple through MoltenVK, *(verify)*). If
  many do, Module 2 needs a shader without double precision: exact integer arithmetic (`umulExtended`) instead of the
  double residuals in `expand_parts.comp`, still 0 differing vertices in every setup.

---

## Module 2 for items

Dropped items cost frames because every visible item writes all of its baked quads on the CPU every frame (a bamboo
item has 56 to 66 quads, drawn up to five times per stack; `docs/vanilla-notes.md` §25). Module 2 now writes them on the
GPU like model parts (`docs/architecture.md`, *Items*).

- [x] Hook, item meshes and the shader's two item paths (vanilla's `putBakedQuad`, Sodium's `BakedModelEncoder`), config
      switch `parts.items`, exclusion by entity type.
- [x] `part_gallery` with a row of dropped items of every item path; the benchmark scene `item_drops`.
- [x] Self-check `--mode parts` on both loaders and on Vulkan, gametests: 0 differing vertices, pictures identical.
- [x] `item_drops` in the dev client, Module 2 off and on: frames 3.9× (Fabric) and 3.6× (NeoForge) faster
      (`docs/benchmarks.md`, *Dropped items*).
- [ ] `item_drops` in a benchmark round (setups A–D, 1920×1080), Module 2 off and on.
- [ ] The maintainer's visual check (*How to verify* of the PR).
