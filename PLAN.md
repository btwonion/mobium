# Mobium — Plan

The current target: Mobium on Minecraft's Vulkan backend. Tick the checkboxes as tasks land and treat the release bar
(`docs/releasing.md`) as a hard stop. Keep *Current state* up to date at the end of every session. § numbers are
sections of `docs/vanilla-notes.md`.

---

## Current state

- Both modules run on Vulkan on `master`, vertex offload with doubles where the GPU has `shaderFloat64` and with
  integers elsewhere; the released mod has only the static cache there. The static cache stays off while Vitrail is
  installed.
- Vertex offload on Vulkan writes 0 differing vertices (self-check on both loaders, gametests on lavapipe in CI).
- Vertex offload for items (dropped items, items in hands, frames and displays) is on `master`, not released yet
  (`docs/benchmarks.md`, *Dropped items*, *Latest results*).
- The `26.4-fabric` target builds against `26.4-snapshot-10` with Fabric API `0.166.3+26.4`; gametests and self-check
  pass there on OpenGL and Vulkan (`docs/vanilla-notes.md` §28).
- Next: step 6, `python3 benchmark/bench_full.py` on `master`: the round on Vulkan (setups A and C) and a round A–D
  on OpenGL in one unattended run, with the release bar's benchmark numbers at the end.
- The config format is 1 on `master`: settings files of released versions do not decode and reset to the defaults
  once.

---

## Vulkan (on 26.3) → release

**Goal:** both modules on Minecraft's Vulkan backend, with the same guarantee as on OpenGL: the same pixels and, for
vertex offload, the same vertex bytes as the game's own path.

1. **26.4.** The `26.4-fabric` target follows the snapshots; check every hook again with each new one (§20, §28).
2. **Tooling.** Done (§21).
3. **The static cache on Vulkan.** Done (§22).
4. **Vertex offload's dispatch on Vulkan.** Done (§23).
5. **Checks on Vulkan.** Done: self-check `--mode offload` on both loaders, the gametests on lavapipe in CI, vertex
   offload allowed on Vulkan by `ModuleRules`.
6. **Measure and document.**
   - [ ] Benchmark rounds in the Prism instances on Vulkan for setups A and C and on OpenGL for A–D
         (`bench_full.py` runs both). B and D need Iris and stay on OpenGL.
   - [x] `docs/compat.md`, README, `changelog.md`.

**Release:** when the release bar (`docs/releasing.md`) holds on Vulkan in 26.3 as on OpenGL. If it does not, stop,
write up why in the PR and agree on the next step first.

**Decisions:**
- **Iris on Vulkan is not part of this target:** no Iris release draws on Vulkan, so setups B and D are measured on
  OpenGL only; with Iris installed and Vulkan chosen, both modules draw as without Iris (§21, `docs/compat.md`). When an
  Iris release draws on Vulkan: the self-check with it on Vulkan, both modules, before B and D are measured there.
- **Vitrail:** Vertex offload runs beside it; the static cache stays off while it is installed, since under a pack its
  buckets would need Vitrail's entity draw, which has no API (§27). Check again with each Vitrail release
  (`-Pmobium.vitrail`, `self_check.py --vitrail --vitrail-cache`).
- **GPUs without `shaderFloat64`:** Vertex offload computes its square roots and divisions in 32-bit integers there,
  with the same bytes (§26; which GPUs: `docs/compat.md`, *Known differences and limits*). On OpenGL those GPUs emulate
  doubles in the driver; the cost there is not measured *(verify)*.

---

## Vertex offload without 64-bit floats

Done except two checks on hardware Mobium has not run on (§26; `docs/architecture.md`, *Two arithmetics, same bytes*).

- [ ] On a real GPU without `shaderFloat64` (an Intel Iris Xe or Arc A-series laptop on Vulkan): the self-check's
      `--mode offload --api vulkan`, or in normal play F3 `mobium:modules` showing the GPU check without a difference.
- [ ] In a benchmark round: `expand-cost` and `mob_pen_live` with both arithmetics on GPUs with doubles (the Arc 140V,
      an Nvidia and an AMD card). If the integer arithmetic is no slower anywhere, it replaces the double one, and
      the `shaderFloat64` feature set and its mixin go.
