# Compatibility

What Mobium does next to other mods, and what was tested with which versions.

## Mods that do the same job

While one of these is installed, the Mobium modules it overlaps stay on the vanilla path and say why (F3
`mobium:modules`, log). Mod ids as the mods' own sources declare them.

| Mod | Mod id | Overlaps | Notes |
| --- | --- | --- | --- |
| Enhanced Block Entities, Enhanced Block Entities Reloaded | `enhancedblockentities` | Static cache | Reloaded (Modrinth `ebe-reloaded`) has 26.3 builds and keeps the original id |
| Vanillate (part of CrankShaft, a Flywheel port) | `vanillate` | Static cache, vertex offload | CrankShaft itself (`crankshaft`) is only the engine and does not count |
| Accelerated Rendering | `acceleratedrendering` | Both | No 26.x build yet; detected in case one appears |

## Mods Mobium works with

| Mod | Status |
| --- | --- |
| Sodium 0.9 | Mobium adds a page to its video settings (Sodium Config API). Sodium's own block entity extraction calls `tryExtractRenderState`, so the static cache works the same with it. Sodium draws vanilla cubes with its own cube path (`ModelCuboid`, corners by a chain of additions, its own face order); vertex offload reproduces that path for vanilla cubes when Sodium is installed, reading `ModelCuboid` by reflection (on NeoForge Sodium is a nested jar) |
| Iris 1.11 | The static cache captures in Iris's extended vertex formats while a shader pack is on and starts over when Iris switches its pipeline (reload, another pack, pack options). Iris's shadow pass draws cached objects through vanilla. An optional mixin into Iris's `ShadowRenderer` times that pass for `/mobium bench` and F3 `mobium:timings` (measurement only; every injection is optional, so another Iris version only loses these timers). Vertex offload expands into Iris's 56-byte entity format in the level's and the shadow pass's staged buffers, with Iris's per-quad extension done on the GPU as Iris does it on the CPU: face normal, mid UV and tangent for vertices written one by one, normal kept and tangent from it for Sodium's pushed cubes (`ModelToEntityVertexSerializer`); the entity ids come from Iris's `CapturedRenderingState` per part |
| Entity Model Features, Entity Texture Features | Run unchanged on every path Mobium uses: the cache captures through vanilla's own submit and prepare, so EMF's replaced models and ETF's emissive layers are captured as drawn. Models EMF animates (`containsCustomAnims`) stay on vanilla. Vertex offload hooks below EMF: its part walk, animations (evaluated inside `ModelPart.render`), per-part texture overrides and ETF's emissive re-renders run as before, and only each part's `compile` is replaced (an optional mixin into EMF's `EMFModelPart.compile`, which overrides vanilla's; Fresh Animations' cubes are EMF's `EMFCube`). Emissive passes into sorted (translucent) draws stay vanilla |
| Fabric API renderer API (Fabric) | Block models (item frames' frames, block displays) arrive as `ExtendedBlockModelSubmit`; the cache lets the feature renderer registered for them (Sodium's or Indigo's) write the captured vertices, like vanilla's own. Items whose model is a renderer API mesh (`ExtendedItemSubmit`, e.g. lanterns and flower pots) stay vanilla: the cache's item comparison cannot see a mesh |
| Entity Culling | Detected; no interaction expected (it decides what is extracted, Mobium how it is drawn) |
| Mod Menu | Opens Mobium's settings |

## Minecraft's Vulkan renderer

Minecraft 26.3 draws with OpenGL by default and with Vulkan as an option (`docs/vanilla-notes.md` §18, §21).
Everything else in this file describes OpenGL unless a row names Vulkan; this table says what is the same and what
differs there, on Fabric and on NeoForge.

| On Vulkan | Status |
| --- | --- |
| Static cache | Runs as on OpenGL: it draws through the game's own GPU layer, which covers both renderers (§22) |
| Vertex offload | Runs as a compute dispatch: there is no vertex stage variant on Vulkan (§23). Where the GPU has 64-bit floats in shaders (`shaderFloat64`) its square roots and divisions use doubles, on the GPUs without them (*Known differences and limits*) integers, with the same bytes (§26); the log line "Mobium vertex offload on Vulkan" says which |
| Sodium 0.9.2 (Fabric), 0.9.3-beta.1 (NeoForge) | Runs on Vulkan with draw paths of its own; both dev clients run with it there (§21) |
| Entity Model Features 3.3.12, Entity Texture Features 7.2.6, Fresh Animations | Run on Vulkan; tested with both modules, see the tables below (§21–§23) |
| Iris 1.11.7 | Does not draw shader packs on Vulkan. With Vulkan chosen in the video settings (`preferredGraphicsBackend` in `options.txt`) Iris switches itself off: the game starts without shaders and both modules run as without Iris, identical to vanilla on both loaders (tables below; F3 `mobium:modules`: `static_cache active \| vertex_offload active`). Only a start on Vulkan by the launch argument `--graphicsBackend vulkan` while `options.txt` names another renderer, as the dev runs do, makes Iris stop the game before the title screen, with no Mobium code involved (seen on Fabric). No Iris release draws on Vulkan, so shader packs are untested there (§21) |
| Vitrail 0.13.0-beta (OptiFine-format shader packs on the Vulkan renderer, mod id `vitrail`) | Vertex offload works beside it: without a shader pack as usual, with one it leaves models to the game by itself. The static cache stays off while it is installed (F3: `untested: Vitrail`): under a pack its objects would be missing or lit wrongly (§27) |
| Enhanced Block Entities, Vanillate, Accelerated Rendering | As on OpenGL: the overlapping modules stay off; not tried on Vulkan |
| Entity Culling | Not tested |

Tested on Vulkan on one GPU: an Intel Arc 140V on Linux (Fedora 44, Mesa 26.2's ANV), plus Mesa's lavapipe (software
Vulkan) in CI. The dev runs use Khronos' validation layer and fail on any message that Mobium's objects or calls
raise.

## Tested: static cache

How: the dev self-check (`benchmark/self_check.py`) compares Mobium's picture with vanilla's from two consecutive
frames of one client tick, in `storage_room` (1,000 chests, 300 signs) and in the `cache_gallery` scene (every cached
type, including item frames, armor stands and display entities, and the variants that stay vanilla), after changes the
cache must notice (lid opened and closed, breaking overlay, light, sign text, block state, an item frame turned and its
item swapped, a display moved, an armor stand's pose changed) and from a moved camera and inside the render-distance
fog. "Identical" means 0 changed pixels. Dev clients run the compat stack of `versions/<target>/gradle.properties`.

| Setup | Fabric | NeoForge |
| --- | --- | --- |
| Sodium 0.9.2 (Fabric) / 0.9.3-beta.1 (NeoForge), Iris 1.11.7 without pack, EMF 3.3.12, ETF 7.2.6 | Identical in every comparison | Identical in every comparison |
| + Complementary Reimagined r5.9.3 (TAA and cloud movement off for the comparison) | Captures in Iris's formats (56-byte entity, 52-byte glyph vertices); the picture matches vanilla's up to the pack's own frame-to-frame noise, which is on terrain too. The shadow-pass timers read while the pack is on and 0 without it | Not tested: Iris 1.11.7 on NeoForge crashes with this pack in `CenterDepthSampler` ("Missing uniform InSampler"), with Mobium switched off as well (`self_check.py neoforge --mode iris-without-mobium`) |
| + Fresh Animations (1.10.3 in the dev clients, 1.11.1 in the benchmark instances) | Identical in every comparison of the gallery; EMF replaces the head models with the pack's, and the one head Fresh Animations animates stays vanilla (`containsCustomAnims`) | Identical in every comparison of the gallery, as on Fabric |
| Entity Culling | Not tested (not in the dev environment); expected to work, see above | Not tested |
| On Vulkan by launch argument (`self_check.py --api vulkan`): Sodium, EMF, ETF, without Iris, with and without Fresh Animations | Identical in every comparison; no validation-layer messages from Mobium | Identical in every comparison; no validation-layer messages from Mobium |
| On Vulkan from `options.txt`, as a player chooses it (`--api vulkan --from-options`): the same with Iris 1.11.7 installed, switched off by itself | Identical in every comparison; no validation-layer messages from Mobium | Identical in every comparison; no validation-layer messages from Mobium |

The Fabric gametests draw their scenes with the cache on OpenGL and a second time on Vulkan (Mesa's lavapipe, CI);
the pictures stay within the gametests' tolerance (0.1 % of the pixels) on both (§22, §23).

## Tested: vertex offload

How: the dev self-check `--mode offload` (`python3 benchmark/self_check.py <loader> --mode offload`) builds
`mob_pen_live` (500 cows, villagers and zombies walking in place) and `part_gallery` (banners, a bell, a conduit, a
lectern, an enchanting table, armour with a trim, a saddle, wolf armour, a trident and a shield) with the models of the
four benchmark setups. In each it compiles every part vertex offload takes over on the CPU as well, reads the GPU's
vertices back and compares them byte for byte (`offload/OffloadValidation.kt`; only bytes the CPU path never writes are
left out), then compares Mobium's picture with vanilla's behind a pause screen (EMF's animations stand still only while
the game is paused). The Fabric gametests run the same validation on Mesa's software renderers (CI): llvmpipe on OpenGL
and lavapipe on Vulkan.

| Setup | Fabric | NeoForge |
| --- | --- | --- |
| Vanilla models (Sodium's cube path), Iris without pack | 0 differing vertices, pictures identical | 0 differing vertices, pictures identical |
| + Complementary Reimagined r5.9.3 | 0 differing vertices in the pens and the gallery, main and shadow pass; pictures identical in the pens, within the pack's own frame-to-frame noise in the gallery (the translucent conduit) | Not tested (Iris crashes with this pack on NeoForge, see above) |
| + Fresh Animations 1.10.3 | 0 differing vertices, pictures identical | 0 differing vertices, pictures identical |
| Fresh Animations and Complementary | 0 differing vertices, pictures as with the pack alone | Not tested |
| On Vulkan by launch argument (`--mode offload --api vulkan`): vanilla models (Sodium's cube path), without Iris | 0 differing vertices, pictures identical, no validation-layer messages from Mobium | 0 differing vertices, pictures identical, no validation-layer messages from Mobium |
| On Vulkan + Fresh Animations | 0 differing vertices, pictures identical | 0 differing vertices, pictures identical |
| On Vulkan from `options.txt` (`--mode offload --api vulkan --from-options`): vanilla models and Fresh Animations, Iris 1.11.7 installed, switched off by itself | 0 differing vertices, pictures identical, no validation-layer messages from Mobium | 0 differing vertices, pictures identical, no validation-layer messages from Mobium |
| On Vulkan as on a GPU without `shaderFloat64` (`--mode offload --api vulkan --arithmetic integer`): vanilla models and Fresh Animations, the integer arithmetic only | 0 differing vertices, pictures identical, no validation-layer messages from Mobium | 0 differing vertices, pictures identical, no validation-layer messages from Mobium |

On OpenGL the expand pass runs as a compute dispatch or in the vertex stage (`offload/ExpandPass.kt`), and `--mode
offload` validates both in every setup; on Vulkan it is always a compute dispatch (`offload/VulkanExpand.kt`). Where
shaders have doubles, every stage is validated with the integer arithmetic as well (`docs/vanilla-notes.md` §26).

### On players' GPUs

All of the above ran on one GPU: an Intel Arc 140V on Fedora, with Mesa's iris on OpenGL and Mesa's ANV on Vulkan, plus
Mesa's software renderers in CI. The shader is written to round as Java does (`precise` operations, square roots and
divisions corrected in double precision, or computed bit by bit in integers where shaders lack doubles), but another
driver may still round or fuse an operation differently, and the dev validation never runs on players' machines. So
vertex offload checks itself in normal play (`offload/OffloadCheck.kt`): for each path through the shader (the part's
flags: matrix kind, sprite, Iris's format and face normal, normals from the CPU or trusted; Sodium's cube layout or
vanilla's; compute or vertex stage; doubles or integers) the first 64 parts, at most 8 per frame, are compiled on the
CPU as well and compared byte for byte after the expand pass, as in the dev validation. Frames with such parts wait for
the GPU once per staged buffer, which happens only in the first frames each path appears in. On a difference the
compared parts get the CPU's bytes in that frame (the frame's other parts keep the GPU's, for that one frame), and
vertex offload switches off until restart with one toast; the log names the GPU, its driver and the first differing
vertices. F3 `mobium:modules` shows how many parts the check compared (`Mobium GPU check: … parts in … paths compared,
no difference`). `/mobium debug gpucheck` (dev only) makes the next comparison find a difference; `--mode offload` uses
it to check the repair and the switch-off. On Vulkan, which cannot wait for the GPU inside a frame, the compared parts
always get the CPU's bytes in their frame and the comparison follows a few frames later (`docs/vanilla-notes.md` §23).

| GPU and driver | OS | Result |
| --- | --- | --- |
| Intel Arc 140V, Mesa iris (OpenGL) | Fedora 44 | No difference (dev validation and the check, both stages) |
| Intel Arc 140V, Mesa ANV (Vulkan) | Fedora 44 | No difference (dev validation and the check, both loaders) |
| Mesa llvmpipe (OpenGL) | CI (Ubuntu) | No difference (gametests) |
| Mesa lavapipe (Vulkan) | CI (Ubuntu) | No difference (gametests) |
| Nvidia GeForce RTX 4070 Ti, driver not recorded | Not recorded | No difference: both runs with Mobium on in each scene of `/mobium benchmark` recorded `+parts-vertex`, so the check switched nothing off (vertex stage, vanilla cubes, no shader pack; [#32](https://github.com/btwonion/mobium/issues/32)) |
| Nvidia GeForce RTX 4070 Laptop GPU, driver not recorded | Linux | No difference, as above ([#31](https://github.com/btwonion/mobium/issues/31)) |
| AMD, Intel on Windows, others; Nvidia with Sodium or a shader pack; on Vulkan every GPU but the Arc 140V | | Not tested yet; F3 `mobium:modules` and the log tell |

## Known differences and limits

- GPUs without `shaderFloat64` on Vulkan, where vertex offload computes its square roots and divisions with integers
  (same bytes; checked on an Arc 140V with the feature switched off, not on such a GPU yet), from the reports in the
  Vulkan Hardware Database (vulkan.gpuinfo.org, *shaderFloat64*, devices not supporting it per OS): Intel from Ice
  Lake's Iris Plus (Gen11) up to Arrow Lake (Iris Xe and UHD 710–770, Iris Xe MAX, Arc A-series and Arc Pro A, Meteor
  Lake's Arc graphics, Arc 130T/140T) and the Atom-class HD 500/505 and UHD 600/605, on Windows and on Linux (Mesa's
  ANV), which Intel documents as by design (support article 000089817); Qualcomm Snapdragon X on Windows on ARM; every
  Mac (MoltenVK). Nvidia's and AMD's GPUs on Windows and Linux, Intel's Xe2 (Lunar Lake's Arc 130V/140V, Arc B-series)
  and, in most reports, Intel's Gen9 (HD and UHD 5xx–6xx of 6th to 10th gen Core) have it.
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
- Vertex offload only takes over parts drawn into the frame's staged buffers in the entity format (or Iris's) with
  unsorted quads, directly or through an atlas sprite: translucent models without improved transparency (sorted on
  upload), the breaking overlay, glint and outline passes, other vertex formats and other cube classes keep vanilla's
  CPU path.
- Vertex offload's fixed cost on the GPU (`docs/benchmarks.md`, *Vertex offload's fixed cost*): Mesa's iris driver
  records a compute dispatch in a batch of its own and flushes both batches around it, about 0.2 ms of GPU time per
  frame however few parts there are (`docs/vanilla-notes.md` §16). Without a shader pack the expand pass therefore runs
  as a vertex shader drawn as points, where the GPU lets vertex shaders write storage buffers
  (`GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS` ≥ 3; F3 `mobium:modules` names the stage): about 0.1 ms per frame remain in
  `display_build`, where vertex offload expands only 17 shields (dev client). With a shader pack it stays a compute
  dispatch: faster there in the dev client, equal at 1920×1080. `-Dmobium.offload.stage=compute` or `vertex` in the Java
  arguments picks one for comparisons and bug reports; both write the same bytes (`--mode offload` validates both).
  Other GPUs and drivers are not measured yet; on them the GPU check above compares the vertices. On Vulkan the pass is
  always a compute dispatch, and what it costs there is not measured yet.
- Iris counts vertices written one by one per quad; a Sodium push right after such vertices loses one from its count
  and extends the following quads from the wrong vertices (vanilla with Iris and Sodium, not Mobium). With vertex
  offload both kinds of cube are expanded on the GPU, so that mix does not happen and the picture is correct. Not seen
  in the tested scenes.
- Sodium reuses its six packed normals while the normal matrix stays the same, without looking at whether the pose's
  normals are trusted; vertex offload computes them per part, so the two differ only for two consecutive poses with the
  same normal matrix and a different trusted flag.
