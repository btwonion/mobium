# Compatibility

What Mobium does next to other mods, and what was tested with which versions.

## Mods that do the same job

While one of these is installed, the Mobium modules it overlaps stay on the vanilla path and say why (settings screen,
F3 `mobium:modules`, log). Mod ids from the mods' own sources, 20 Oct 2026.

| Mod | Mod id | Overlaps | Notes |
| --- | --- | --- | --- |
| Enhanced Block Entities, Enhanced Block Entities Reloaded | `enhancedblockentities` | Static cache | Reloaded (Modrinth `ebe-reloaded`) has 26.3 builds and keeps the original id |
| Vanillate (part of CrankShaft, a Flywheel port) | `vanillate` | Static cache, GPU part transform | CrankShaft itself (`crankshaft`) is only the engine and does not count |
| Accelerated Rendering | `acceleratedrendering` | All three | No 26.x build yet; detected in case one appears |

## Mods Mobium works with

| Mod | Status |
| --- | --- |
| Sodium 0.9 | Mobium adds a page to its video settings (Sodium Config API). Sodium's own block entity extraction calls `tryExtractRenderState`, so the static cache works the same with it. Sodium draws vanilla cubes with its own cube path (`ModelCuboid`, corners by a chain of additions, its own face order); Module 2 reproduces that path for vanilla cubes when Sodium is installed, reading `ModelCuboid` by reflection (on NeoForge Sodium is a nested jar) |
| Iris 1.11 | The static cache captures in Iris's extended vertex formats while a shader pack is on and starts over when Iris switches its pipeline (reload, another pack, pack options). Iris's shadow pass draws cached objects through vanilla. Since 0.1.0-beta2 an optional mixin into Iris's `ShadowRenderer` times that pass for `/mobium bench` and F3 `mobium:timings` (measurement only; every injection is optional, so another Iris version only loses these timers). Module 2 expands into Iris's 56-byte entity format in the level's and the shadow pass's staged buffers, with Iris's per-quad extension done on the GPU as Iris does it on the CPU: face normal, mid UV and tangent for vertices written one by one, normal kept and tangent from it for Sodium's pushed cubes (`ModelToEntityVertexSerializer`); the entity ids come from Iris's `CapturedRenderingState` per part |
| Entity Model Features, Entity Texture Features | Run unchanged on every path Mobium uses: the cache captures through vanilla's own submit and prepare, so EMF's replaced models and ETF's emissive layers are captured as drawn. Models EMF animates (`containsCustomAnims`) stay on vanilla. Module 2 hooks below EMF: its part walk, animations (evaluated inside `ModelPart.render`), per-part texture overrides and ETF's emissive re-renders run as before, and only each part's `compile` is replaced (an optional mixin into EMF's `EMFModelPart.compile`, which overrides vanilla's; Fresh Animations' cubes are EMF's `EMFCube`). Emissive passes into sorted (translucent) draws stay vanilla |
| Fabric API renderer API (Fabric) | Block models (item frames' frames, block displays) arrive as `ExtendedBlockModelSubmit`; the cache lets the feature renderer registered for them (Sodium's or Indigo's) write the captured vertices, like vanilla's own. Items whose model is a renderer API mesh (`ExtendedItemSubmit`, e.g. lanterns and flower pots) stay vanilla: the cache's item comparison cannot see a mesh |
| Entity Culling | Detected; no interaction expected (it decides what is extracted, Mobium how it is drawn) |
| Mod Menu | Opens Mobium's settings |

## Tested (M5, static cache)

How: the dev self-check (`benchmark/self_check.py`) compares Mobium's picture with vanilla's from two consecutive
frames of one client tick, in `storage_room` (1,000 chests, 300 signs) and in the `cache_gallery` scene (every cached
type, including item frames, armor stands and display entities, and the variants that stay vanilla), after changes the
cache must notice (lid opened and closed, breaking overlay, light, sign text, block state, an item frame turned and its
item swapped, a display moved, an armor stand's pose changed) and from a moved camera and inside the render-distance
fog. "Identical" means 0 changed pixels. Dev clients run the compat stack of `versions/<target>/gradle.properties`.

| Setup | Fabric | NeoForge |
| --- | --- | --- |
| Sodium 0.9.2 (Fabric) / 0.9.3-beta.1 (NeoForge), Iris 1.11.7 without pack, EMF 3.3.12, ETF 7.2.6 | Identical in every comparison (22 Oct 2026; again on 23 Oct for 0.1.0-beta2) | Identical in every comparison (22 Oct 2026; again on 23 Oct for 0.1.0-beta2) |
| + Complementary Reimagined r5.9.3 (TAA and cloud movement off for the comparison) | Captures in Iris's formats (56-byte entity, 52-byte glyph vertices); the picture matches vanilla's up to the pack's own frame-to-frame noise, which is on terrain too. The shadow-pass timers read while the pack is on and 0 without it (23 Oct 2026) | Not tested: Iris 1.11.7 on NeoForge crashes with this pack in `CenterDepthSampler` ("Missing uniform InSampler"), with Mobium switched off as well (`self_check.py neoforge --mode iris-without-mobium`) |
| + Fresh Animations (1.10.3 in the dev clients, 1.11.1 in the benchmark instances) | Identical in every comparison of the gallery (22 Oct 2026); EMF replaces the head models with the pack's, and the one head Fresh Animations animates stays vanilla (`containsCustomAnims`) | Identical in every comparison of the gallery (22 Oct 2026), as on Fabric |
| Entity Culling | Not tested (not in the dev environment); expected to work, see above | Not tested |

## Tested (M6, GPU part transform)

How: the dev self-check `--mode parts` (`python3 benchmark/self_check.py <loader> --mode parts`) builds `mob_pen_live`
(500 cows, villagers and zombies walking in place) and `part_gallery` (banners, a bell, a conduit, a lectern, an
enchanting table, armour with a trim, a saddle, wolf armour, a trident and a shield) with the models of the four
benchmark setups. In each it compiles every part Module 2 takes over on the CPU as well, reads the GPU's vertices back
and compares them byte for byte (`parts/PartValidation.kt`; only bytes the CPU path never writes are left out), then
compares Mobium's picture with vanilla's behind a pause screen (EMF's animations stand still only while the game is
paused). The Fabric gametests run the same validation on Mesa's software renderer (CI).

| Setup | Fabric | NeoForge |
| --- | --- | --- |
| Vanilla models (Sodium's cube path), Iris without pack | 0 differing vertices (11.4M compared in the pens), pictures identical (26 Oct 2026) | 0 differing vertices (13.6M in the pens, 2.4M in the gallery), pictures identical (26 Oct 2026) |
| + Complementary Reimagined r5.9.3 | 0 differing vertices in the pens and the gallery, main and shadow pass; pictures identical in the pens, within the pack's own frame-to-frame noise in the gallery (the translucent conduit) | Not tested (Iris crashes with this pack on NeoForge, see above) |
| + Fresh Animations 1.10.3 | 0 differing vertices (6.8M), pictures identical | 0 differing vertices (9.3M in the pens, 2.6M in the gallery), pictures identical |
| Fresh Animations and Complementary | 0 differing vertices, pictures as with the pack alone | Not tested |

Since 0.2.0-beta2 the expand pass runs as a compute dispatch or in the vertex stage (`parts/ExpandPass.kt`), and
`--mode parts` validates both in every setup. 27 Oct 2026, both stages: Fabric 33/33 checks (16 validations, 0
differing vertices, 0.26–9.5M vertices compared each), NeoForge 17/17 (8 validations, 0 differing); pictures identical
as above.

### On players' GPUs (since 0.2.0-beta3)

All of the above ran on one GPU and driver: an Intel Arc 140V on Mesa's iris (Fedora), plus Mesa's software renderer
in CI. The shader is written to round as Java does (`precise` operations, square roots and divisions corrected in
double precision), but another driver may still round or fuse an operation differently, and the dev validation never
runs on players' machines. So Module 2 checks itself in normal play (`parts/PartCheck.kt`): for each path through the
shader (the part's flags: matrix kind, sprite, Iris's format and face normal, normals from the CPU or trusted; Sodium's
cube layout or vanilla's; compute or vertex stage) the first 64 parts, at most 8 per frame, are compiled on the CPU as
well and compared byte for byte after the expand pass, as in the dev validation. Frames with such parts wait for the
GPU once per staged buffer, which happens only in the first frames each path appears in. On a difference the compared
parts get the CPU's bytes in that frame (the frame's other parts keep the GPU's, for that one frame), and Module 2
switches off until restart with one toast; the log names the GPU, its driver and the first differing vertices. F3 `mobium:modules` shows how many parts the check compared
(`Mobium GPU check: … parts in … paths compared, no difference`). `/mobium debug gpucheck` (dev only) makes the next
comparison find a difference; `--mode parts` uses it to check the repair and the switch-off.

| GPU and driver | OS | Result |
| --- | --- | --- |
| Intel Arc 140V, Mesa iris | Fedora 44 | No difference (dev validation and the check, both stages; 28 Oct 2026: 980 parts in 21 paths in `--mode parts` on Fabric, 542 in 13 on NeoForge) |
| Mesa llvmpipe | CI (Ubuntu) | No difference (gametests) |
| Nvidia, AMD, Intel on Windows, others | | Not tested yet; F3 `mobium:modules` and the log tell |

## Known differences and limits

- Minecraft's Vulkan renderer (also with Vitrail, which runs OptiFine-format shader packs on it): both modules need the
  OpenGL backend and stay off (F3 `mobium:modules`: `backend`), so the game draws everything itself. The rule is
  unit-tested (`ModuleRulesTest`); Mobium has not been started on Vulkan in game yet. Vulkan support is M7.
- Text drawn with a resource pack's antialiased (TrueType) font: vanilla draws a sign's glyphs in submission order
  among all solid-phase text; overlapping half-transparent glyph edges of the 8× glow-ink outline could blend in a
  different order. Not seen with the default font, whose glyphs are fully opaque or fully transparent.
- Entities are compared with what was captured through their render state (and, for armor and block displays, the
  entity's equipment and block state). Something a mod draws for an entity from data outside those inputs would keep
  its captured picture while the entity stands still: an ETF random or emissive texture whose rule depends on time or
  weather, or a NeoForge mod that draws its own item in a frame through `RenderItemInFrameEvent`. Not seen with Fresh
  Animations; the per-type switches (item frames, armor stands, display entities) turn such cases off.
- Armor stands wearing armor are not cached: their armor layers are drawn in submit orders one after another
  (`EquipmentLayerRenderer`), which the cache does not keep.
- Cached objects are drawn from camera-relative vertices, which the CPU rewrites for the visible buckets whenever the
  camera moves; with a still camera nothing is uploaded (F3 `mobium:modules` shows the bytes per frame).
- Module 2 only takes over parts drawn into the frame's staged buffers in the entity format (or Iris's) with unsorted
  quads, directly or through an atlas sprite: translucent models without improved transparency (sorted on upload), the
  breaking overlay, glint and outline passes, other vertex formats and other cube classes keep vanilla's CPU path.
- Module 2's cost on the GPU (`docs/benchmarks.md`): in the 0.2.0-beta1 release round on the Intel Arc 140V without a
  shader pack, `display_build`, where it expands only 17 shields, got 0.15–0.3 ms per frame slower. That was a fixed
  cost of the compute dispatch on Mesa's iris driver, which records it in a batch of its own and flushes both batches
  around it (docs/vanilla-notes.md §16). Since 0.2.0-beta2 the expand pass runs as a vertex shader drawn as points
  without a shader pack, where the GPU lets vertex shaders write storage buffers (`GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS`
  ≥ 3; F3 `mobium:modules` names the stage): 0.03 instead of 0.24 ms of GPU time per frame in `display_build` in the
  dev client (*27 Oct 2026, Module 2's fixed cost*), and at 1920×1080 frames 2–7 % faster than with the dispatch in A
  and C (*0.2.0-beta2 round*); about 0.1 ms per frame remain in `display_build` (dev client). The mob pens with vanilla
  models, which looked 1.07–1.14× slower across sessions in the beta1 round, are 1.19× faster with Module 2 in the same
  session. With a shader pack it stays a compute dispatch: faster there in the dev client, equal at 1920×1080.
  `-Dmobium.parts.stage=compute` or `vertex` in the Java arguments picks one for comparisons and bug reports; both
  write the same bytes (`--mode parts` validates both). Other GPUs and drivers are not measured yet; on them the
  GPU check above compares the vertices.
- Iris counts vertices written one by one per quad; a Sodium push right after such vertices loses one from its count
  and extends the following quads from the wrong vertices (vanilla with Iris and Sodium, not Mobium). With Module 2
  both kinds of cube are expanded on the GPU, so that mix no longer happens; where it happened before, the picture
  would now be correct instead. Not seen in the tested scenes.
- Sodium reuses its six packed normals while the normal matrix stays the same, without looking at whether the pose's
  normals are trusted; Module 2 computes them per part, so the two differ only for two consecutive poses with the same
  normal matrix and a different trusted flag.
