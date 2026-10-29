# Mobium — Plan

The plan of the work ahead, for the coding agent (Claude Code) and for Anton, who runs the game. Work through the
milestones in order, tick their checkboxes as tasks land, and treat gates as hard stops. Keep *Current state* up to date
at the end of every session.

How Mobium works today is in `docs/architecture.md`, every vanilla, Sodium, Iris and EMF detail it relies on in
`docs/vanilla-notes.md`, measurements in `docs/benchmarks.md`, compatibility in `docs/compat.md`. Milestones M0–M6
(scaffold, vanilla recon, benchmark harness, chest prototype and Gate A, core, static cache and Gate B, GPU part
transform and Gate C) are done; their tasks and write-ups up to the 0.2.0 release are in `git show dd7253f:PLAN.md`.

---

## Current state (updated 29 Oct 2026)

- **0.2.0, the first stable release, is out** (28 Oct 2026, `release.yml` run 40754122974 on `main` at `dd7253f`;
  GitHub releases `v0.2.0-26.3+fabric` / `+neoforge`): the static cache and the GPU part transform, Module 2's check
  on the player's GPU, `/mobium benchmark` with its built-in data pack, Minecraft 26.3.x only (`~26.3` on Fabric,
  `[26.3,26.4-alpha)` on NeoForge). Releases go to Modrinth `mobium` (`8i5oedw8`) and CurseForge `mobium-perf`
  (`1759818`) through `release.yml`, which only Anton runs. `gradle.properties` still says 0.2.0 (`mod.beta=0`); the
  next version is Anton's call before the next release.
- **Gates passed:** A 1.53× (`storage_room`, chest prototype), B 1.17× (what the cached objects cost over `control`),
  C (D's entity CPU work 1.90× lower, frames 1.18× faster in D and 1.25× in C); `docs/benchmarks.md` *Gates*.
- **Tested by us on one machine:** Anton's ThinkPad X9-14 (Intel Arc 140V, Mesa's iris, Fedora 44) plus Mesa's
  llvmpipe in CI. **Players' results:** two Nvidia GPUs so far (RTX 4070 Ti, RTX 4070 Laptop GPU on Linux; #31 and
  #32, 28 Oct 2026, both without Sodium): `storage_room` 2.28× and 11.2×, `mob_pen_live` 2.42× and 3.02×, and Module
  2's check found no difference. Results from other GPUs come from players' `/mobium benchmark` files (README
  *Benchmarks wanted*, issue form `.github/ISSUE_TEMPLATE/benchmark.yml`, label `benchmark`) and from F3
  `mobium:modules` lines; add them to `docs/compat.md` (*On players' GPUs*), `docs/benchmarks.md` (*Players' results*)
  and the file to `players/` in `../mobium-benchmarks`. 0.2.0's file records neither the OS nor the driver; from the
  next release on it has `# os` and `# driver`, which the release PR adds to what README (*Benchmarks wanted*) and the
  issue form (`benchmark.yml`, *Benchmark file*) say the file names.
- **Modrinth:** the project was not public on 28 Oct 2026 (the API answered 404 for `8i5oedw8`); Anton checks.
- **CI:** green. The Fabric gametests run under `xvfb-run` with SDL on Mesa's EGL and software rendering
  (`.github/workflows/build-commit.yml`; `release.yml` does the same); llvmpipe has compute and double precision, so
  the gametests run Module 2 and its byte-for-byte validation too. If CI hangs or fails, read `gh run view
  --log-failed` first.
- **Next: M7, Vulkan.** Minecraft 26.4 makes Vulkan the default backend (`26.4-snapshot-1`, 22 Sep 2026: "Default"
  now behaves like "Prefer Vulkan"), and both modules are off on Vulkan. Vulkan already exists in 26.3 as an option,
  with every class M7 needs, so M7 is built and gated on 26.3; M8 then ports to 26.4.

---

## M7 · Vulkan (on 26.3) → Gate D

**Goal:** both modules on Minecraft's Vulkan backend, with the same guarantee as on OpenGL: the same pixels and, for
Module 2, the same vertex bytes as the game's own path.

What the 26.3 Vulkan backend offers and lacks is in `docs/vanilla-notes.md` §18 (decompiled 28 Oct 2026). In short:
Module 1 uses nothing but Renderpearl and should work as it is; Module 2's expand pass needs its own Vulkan dispatch,
for which the backend has a public entry point (a command buffer of Mobium's own, executed in order inside the
frame's submission), and three blockers: vanilla's vertex buffers are not storage buffers, the device does not enable
64-bit floats, and the shader's GLSL is OpenGL's dialect.

1. **Check 26.4 first.**
   - [ ] Decompile the newest 26.4 snapshot's Renderpearl and diff it with 26.3 (`api`, `backend/vulkan`,
         `VulkanFeatureSets`, `VulkanCommandEncoder`, `VulkanConst.bufferUsageToVk`); record the result in
         `docs/vanilla-notes.md` §18. If Renderpearl gained compute pipelines or storage buffers, build on them and
         drop the steps below they replace. Also look at computelib (compute for Mojang's engine on OpenGL and Vulkan,
         experimental on 19 Oct 2026) before writing raw Vulkan.
2. **Tooling.**
   - [ ] `benchmark/self_check.py --api vulkan` (and the gametests' launch) start the dev clients on Vulkan
         (`forcedGraphicsApi` launch argument or the `PreferredGraphicsApi` option, *(verify)* their exact form).
   - [ ] Vulkan validation layers in the dev clients (Renderpearl's `GpuDebugOptions`, *(verify)*); the self-check
         fails on any validation message that names Mobium's objects or follows its calls.
   - [ ] `debug/ExpandProfile.kt` on Renderpearl's timestamps (`GpuDevice.createTimestampQueryPool`,
         `CommandEncoder.writeTimestamp`) instead of GL queries, so `--mode expand-cost` works on both backends.
3. **Module 1 on Vulkan.**
   - [ ] `ModuleRules`: the backend rule per module. The static cache runs on OpenGL and Vulkan; Module 2 needs
         `compute`, which `Capabilities` reports per backend; `OffReason.BACKEND` stays for other backends.
         `ModuleRulesTest` covers both.
   - [ ] Self-check on Vulkan, both loaders: `storage_room` from three views, the `cache_gallery` scene with every
         effect, `/mobium debug validate`; 0 changed pixels and no validation messages. This also answers the open
         point in `docs/vanilla-notes.md` §10 (`RenderType.prepare()` writing dynamic uniforms on Vulkan).
   - [ ] If Module 2 takes longer, Module 1 on Vulkan can be released first, with Gate D's conditions for Module 1
         alone and Module 2 still off on Vulkan (Anton's call).
4. **Module 2's dispatch on Vulkan** (`gpu/vk/`, behind one interface with `gpu/gl/GlCompute`).
   - [ ] Reach the backend: an accessor for `FrontendGpuDevice.backend` → `VulkanDevice` (`vkDevice()`, `vma()`,
         `createCommandEncoder()` → the one `VulkanCommandEncoder`); vanilla's vertex buffer is a `VulkanGpuBuffer`
         with `vkBuffer()`.
   - [ ] Storage buffers: a mixin on `VulkanConst.bufferUsageToVk` adds `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT` to the
         staged vertex pool's buffers (`COPY_DST | VERTEX`) and Mobium's own. Decide between all `VERTEX` buffers and
         only the ones Mobium marks (a wrap of `StagedVertexBuffer.GpuBufferPool`'s `createBuffer`); fallback if
         neither works: expand into a buffer of Mobium's own and `vkCmdCopyBuffer` the reserved ranges over.
   - [ ] Mobium's mesh and record buffers become Renderpearl buffers, uploaded with `writeToBuffer` and kept per frame
         in flight (`gpu/RingBuffer.kt`, unused so far: Vulkan has no orphaning); the same code can serve OpenGL.
   - [ ] 64-bit floats: a mixin on `VulkanFeatureSets.optionalFeatureSets()` adds an optional feature set with
         `shaderFloat64`, enabled only where the GPU has it. `Capabilities.compute` is true on Vulkan when it was
         enabled; otherwise Module 2 is off with `no_compute`.
   - [ ] The shader for Vulkan: compiled to SPIR-V with LWJGL's shaderc, which vanilla's `GlslCompiler` uses
         already; under `#ifdef VULKAN` push constants instead of `layout(location)` uniforms and `gl_VertexIndex`;
         where `VK_KHR_shader_float_controls`' properties allow, `DenormPreserve`, `SignedZeroInfNanPreserve` and
         `RoundingModeRTE` for 32-bit floats. `--mode parts` stays at 0 differing vertices on OpenGL.
   - [ ] The pass: a compute pipeline with a push-descriptor set layout (three storage buffers) and push constants; per
         staged buffer right after `upload()` a transient command buffer
         (`allocateAndBeginTransientCommandBuffer`) with the dispatch (split at the device's group limit) and a
         barrier from compute writes to vertex attribute reads, handed to `VulkanCommandEncoder.execute`. Compute
         only: the vertex stage was a workaround for Mesa's OpenGL batches and would also need
         `vertexPipelineStoresAndAtomics`; measure before adding it.
   - [ ] Every Vulkan mixin `require = 0` with a flag that it applied: a changed backend class leaves Module 2 off on
         Vulkan with a reason instead of crashing the game.
5. **Checks on Vulkan.**
   - [ ] `PartValidation`'s read-back through Renderpearl (`copyToBuffer` into a `MAP_READ` buffer, compared when its
         fence signals): Vulkan cannot wait inside a frame for commands it has not submitted.
   - [ ] `PartCheck` on Vulkan: the sampled parts' GPU bytes are copied out and then overwritten with the CPU's bytes
         in the same frame, so no sampled part is ever drawn from the GPU; the verdict arrives about two frames later
         and switches Module 2 off as on OpenGL.
   - [ ] Self-check `--mode parts` on Vulkan, both loaders: 0 differing vertices, pictures identical, no validation
         messages; `/mobium debug gpucheck` still repairs and switches off.
   - [ ] CI: the Fabric gametests a second time on Mesa's lavapipe (software Vulkan, with `shaderFloat64`).
6. **Measure and document.**
   - [ ] Benchmark round A–D on Vulkan in the Prism instances (the graphics API set in their options), plus
         `/mobium gatec` on Vulkan and OpenGL in one session each. Setups whose mods do not run on Vulkan are
         reported as such (open questions below).
   - [ ] `docs/compat.md` (Vulkan rows, GPUs without `shaderFloat64`), README (*Does Mobium work with Vulkan?*),
         `changelog.md`.

**Gate D** *(proposed; Anton decides before M7 starts)*: on Vulkan in 26.3, on both loaders, zero screenshot
differences and 0 differing vertices (self-check, gametests on lavapipe), no validation-layer messages from Mobium;
`mob_pen_live` no slower with Module 2 than without (at most 1.03×) in every setup that runs on Vulkan; `control`
with Mobium no slower than without (at most 1.03×). Then release.

**Open questions for M7:**
- Do Iris 1.11, Sodium 0.9 and EMF/ETF run on Vulkan in 26.3 and 26.4? If Iris does not, setups B and D stay on
  OpenGL until Aperture.
- Vitrail (OptiFine-format shader packs on the Vulkan renderer; 0.7 beta since Aug 2026, asked about on 26 Oct 2026):
  how it changes entity vertex formats and the shadow pass. Until checked, Module 2 stays off while it is installed.
- Which GPUs lack `shaderFloat64` on Vulkan (Intel Arc A-series on Windows, Apple through MoltenVK, *(verify)*); the
  answer decides how soon the shader without double precision (*Later*) is needed.

---

## M8 · Minecraft 26.4

- [ ] A branch tracking the newest 26.4 snapshot: `26.4-fabric` / `26.4-neoforge` Stonecutter targets
      (`settings.gradle.kts`, `versions/26.4-*/gradle.properties`), API changes recorded in `docs/vanilla-notes.md`
      (Vineflower on the new client jar, as for 26.3). Known so far: Vulkan by default, no automatic switch of the
      graphics API after a crash, the OIT shaders' depth bins instead of wavelets (`26.4-snapshot-1`).
- [ ] Compat stack for 26.4 (Sodium, Iris, EMF, ETF, Mod Menu) in the dev clients as it appears.
- [ ] Self-check and gametests on 26.4, on Vulkan and OpenGL; a benchmark round A–D.
- [ ] Version ranges per target (26.3.x and 26.4.x builds side by side); release once 26.4 is out.

---

## Later (not ordered)

- **Module 2 without double precision.** Replace the double residuals in `expand_parts.comp` (square roots,
  divisions, Iris's twice-rounded `rsqrt`) by exact integer arithmetic (`umulExtended`), so Module 2 runs on GPUs
  without `shaderFloat64` on Vulkan and without fp64 on OpenGL. Must keep 0 differing vertices in every setup.
- **Aperture**, Iris's Vulkan successor, once public; contact the Iris team before starting.
- **Module 3, animation LOD** (`lod/`): far or tiny-on-screen mobs reuse Module 2's last records while their root
  transform still updates every frame. Throttle by projected size on screen (a spyglass restores full rate); the hurt
  flash, death animation and the targeted mob always at full rate; presets Off / Subtle / Aggressive; per-type
  exclusions; EMF-animated models excluded until tested (EMF animations keep state between frames, and EMF has its own
  LOD, `animationLODDistance`). Off by default: it changes the picture, so it is presented as a trade-off.
- **Glint and outline** generated from Module 2's records.
- **Module 1:** cached entities drawn in Iris's shadow pass too (in `display_build` the shadow pass prepares the 500
  displays again, 1.9–2.0 ms; needs the shadow pass's vertex format and render types); armor stands with armor
  (keep the equipment layers' submit orders); Fabric renderer API item meshes (`ExtendedItemSubmit`).
- **Module 2 with shader packs in the vertex stage:** the compute dispatch's flushes submit the frame's first part
  early, which a shader pack's frame gains from; an asynchronous way to submit after the vertex stage's pass would
  give the vertex stage that too (`glFlush` waits for Mesa's driver thread: 2.5 ms per frame in D).
- **Sodium on NeoForge:** Fabric uses Sodium 0.9.2, NeoForge 0.9.3-beta.1 (0.9.2-neoforge crashes against NeoForge
  26.3.0.94); move Fabric to the same Sodium once 0.9.3 is released.

---

## Gates

A gate is a go/no-go decision before a release. If a gate fails, stop, write up why in the PR and agree on the next
step with Anton before continuing. Gates measure what Mobium can change: B and C were redefined with Anton when the
first targets turned out to depend on GPU work vanilla pays as well (`docs/benchmarks.md` *Gates*). Every gate also
requires zero screenshot differences (self-check and gametests, both loaders).

---

## Release checklist

Copy this list into the release's PR and tick it there. Version scheme: a new module is a new minor version (Anton,
26 Oct 2026), betas count up `mod.beta`, and `mod.beta=0` publishes as stable.

- [ ] The milestone's gate passed, results in `docs/benchmarks.md`, raw data in `btwonion/mobium-benchmarks`.
- [ ] `changelog.md` holds only this release's notes, one line of at most 120 characters per entry.
- [ ] `docs/compat.md` updated; `README.md` describes the release. README changes merge right before `release.yml`:
      `readme-modrinth-sync.yml` publishes every README change on `main` to Modrinth at once.
- [ ] Self-check on both loaders (regular, `--mode parts`, `--mode benchmark`) and the gametests; 0 changed pixels,
      0 differing vertices.
- [ ] A benchmark round A–D (`python3 benchmark/bench_round.py`) if rendering changed since the last round.
- [ ] Anton's visual checks of the release's rendering PRs (*How to verify*).
- [ ] The bug report template's version placeholder (`.github/ISSUE_TEMPLATE/bug_report.yml`).
- [ ] Anton's go, then `release.yml` on `main` (manual dispatch; Anton only, never `release.yml` or `releaseMod`
      from here).
- [ ] Afterwards a post-release PR: the next version in `gradle.properties`, `changelog.md` emptied, *Current state*.

---

## Testing and verification

| Check | Who / where | When |
| --- | --- | --- |
| `./gradlew build`, both targets | Claude Code and CI | Every PR |
| Unit tests (pure Kotlin: module rules, fallback ledger, ring arithmetic, memory budget, light stamps, path sampler, gate maths, config format, translation keys) | CI | Every PR |
| Fabric client gametests: scene smoke tests, screenshots, Module 2's byte-for-byte validation | CI under xvfb on Mesa's software OpenGL (and lavapipe from M7) | Every PR |
| In-game self-check on both dev clients (`benchmark/self_check.py`): the PR's *How to verify* as scripted checks, pixel comparisons and screenshots | Claude Code, locally | Every rendering PR |
| Visual check with shader packs and Fresh Animations | Anton | Every rendering PR |
| Benchmarks (four setups, `bench_round.py`, the gate commands) | Anton's machine, unattended | Gates and releases |
| Other GPUs and drivers | Players (`/mobium benchmark`, F3 `mobium:modules`) | Continuously |

Every rendering PR description ends with **How to verify** (task to run, scene, keys, expected result) and the
benchmark numbers if performance changed.

---

## Open questions

- The light shader pack of the test set (the heavy one is Complementary Reimagined r5.9.3).
- Who covers AMD and Nvidia beyond players' reports.
- Whether to reach out to the Sodium and Iris maintainers, now before M7 (Iris on Vulkan, Aperture).

---

## References

- House structure: `btwonion/autodrop`, `btwonion/better-boat-movement`, `btwonion/magnetic`
- Stonecutter: https://stonecutter.kikugie.dev/ · modstitch: https://github.com/isXander/modstitch
- Fabric client gametests: https://docs.fabricmc.net/develop/automatic-testing
- 26.2 → 26.3 rendering changes: https://docs.neoforged.net/primer/docs/26.3/
- 26.4 snapshots: https://minecraft.wiki/w/Java_Edition_26.4_Snapshot_1
- Prior art: Accelerated Rendering (https://github.com/Argon4W/AcceleratedRendering), Flywheel/Vanillin, Enhanced
  Block Entities, CrankShaft
