# Vanilla notes (Minecraft 26.3)

How Minecraft 26.3 draws entities and block entities, where Sodium, Iris, EMF and ETF change that, and where Mobium
hooks in. Started in M1 from the decompiled sources and extended with every milestone (each section says when it was
read); every claim below names the class and method it comes from. How Mobium uses all this: `docs/architecture.md`.
Section numbers are referenced from the code; new findings get a new section at the end.

Sources read (19 Oct 2026):

| What | Version | How |
| --- | --- | --- |
| Minecraft client | 26.3 (unobfuscated) | Vineflower 1.12 on Loom's `minecraft-client.jar`; NeoForge patches from MDG's `minecraft-patched-26.3.0.94-sources.jar` |
| Sodium | `mc26.3-0.9.2-fabric` | Vineflower on the Modrinth jar |
| Iris | `1.11.7+26.3-fabric` | Vineflower on the Modrinth jar |
| Entity Model Features | `3.3.12-fabric-26.3` | Vineflower on the Modrinth jar |
| Entity Texture Features | `7.2.6-fabric-26.3` | Vineflower on the Modrinth jar |

Excerpts are shortened (`…`) and use the decompiler's names; local variable names may differ from Mojang's.

---

## 0. The frame at a glance

26.3 splits world-object rendering into four stages that run at different times and touch different data. The plan's
"pose" and "tessellate" steps are not separate: both happen inside the **prepare** stage, per submit, right before
the vertices are written.

| # | Stage | Entry point | What happens |
| --- | --- | --- | --- |
| 1 | Extract | `LevelExtractor.extract(DeltaTracker, Camera, float)` | Culls entities and block entities and builds one render state object per visible object (`EntityRenderState`, `BlockEntityRenderState`). |
| 2 | Submit | `LevelRenderer.render(…)` → `submitFeatures(LevelRenderState, SubmitNodeCollector, boolean)` | Each renderer turns its state into submit records (`ModelFeatureRenderer.Submit`, `ItemFeatureRenderer.Submit`, …) in `SubmitNodeStorage`. No vertices yet. |
| 3 | Prepare | `FeatureRenderDispatcher.prepareFrame(SubmitNodeStorage)` | Records are grouped by phase and feature type; each feature renderer writes vertices into **one shared `StagedVertexBuffer`** per frame. For models this is where `Model.setupAnim` and `ModelPart.render` run. Ends with one upload of all vertices to pooled GPU buffers. |
| 4 | Execute | `LevelRenderer.addMainPass(…)` → `PreparedFrame.executeSolid/executeTranslucent/executeOit/…` | Frame-graph passes draw the prepared groups through `PreparedRenderType.drawFromBuffer(ExecuteInfo, RenderPass)`. |

`LevelRenderer.render` (stages 2–4) in order: `submitFeatures` → `featureRenderDispatcher.prepareFrame(submitNodeStorage)`
→ build frame graph (`clear`, `sky`, `main` pass, entity-outline post chain) → `frame.execute(…)` →
`featureFrame.close()`.

Graphics layer: `com.mojang.renderpearl` with `api`, `backend` (`opengl`, `vulkan`, `common`, `api`) and `frontend`
exists as the plan says, but `com.mojang.blaze3d` is still there too (`RenderSystem`, `vertex`, `platform`,
`pipeline`, …). Windowing moved from GLFW to SDL (`SDLVideo` in `GlDevice`).

---

## 1. Block entities: iteration, culling, submit

### Extract (vanilla)

`LevelExtractor.extractVisibleBlockEntities(Camera camera, float deltaPartialTick, LevelRenderState levelRenderState)`
walks the visible sections and the globally rendered block entities:

```java
ObjectListIterator iterator = this.levelRenderer.visibleSections().iterator();
while (iterator.hasNext()) {
   SectionRenderDispatcher.RenderSection section = …;
   List<BlockEntity> renderableBlockEntities = section.getSectionMesh().getRenderableBlockEntities();
   if (!renderableBlockEntities.isEmpty() && !(section.getVisibility(Util.getMillis(), chunkFadeDuration) < 0.3F)) {
      for (BlockEntity blockEntity : renderableBlockEntities) {
         … breakProgress = new ModelFeatureRenderer.CrumblingOverlay(progresses.last().getProgress(), poseStack.last()) …
         BlockEntityRenderState state = this.levelRenderer.blockEntityRenderDispatcher()
            .tryExtractRenderState(blockEntity, deltaPartialTick, breakProgress, false);
         if (state != null) levelRenderState.blockEntityRenderStates.add(state);
      }
   }
}
// then level.getGloballyRenderedBlockEntities() with isGloballyRendered = true
```

Culling is therefore: section visibility (frustum + occlusion graph via `visibleSections()`), section fade-in
(`getVisibility < 0.3` skips the section), and per block entity
`BlockEntityRenderer.shouldRender(T, Vec3 cameraPosition)` (default: within `getViewDistance()`, 64 blocks).

`BlockEntityRenderDispatcher.tryExtractRenderState(E, float partialTicks, CrumblingOverlay, boolean isGloballyRendered)`
allocates a **new render state per block entity per frame**:

```java
S state = renderer.createRenderState();
renderer.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
```

`BlockEntityRenderState.extractBase` fills `blockPos`, `blockState`, `blockEntityType`, `lightCoords`
(`LightCoordsUtil.getLightCoords(level, pos)`) and `breakProgress`.

### Submit

`LevelRenderer.submitBlockEntities(PoseStack, LevelRenderState, SubmitNodeCollector)` (private) translates to the
block position relative to the camera and calls the public dispatcher:

```java
poseStack.translate(blockPos.getX() - camX, blockPos.getY() - camY, blockPos.getZ() - camZ);
this.blockEntityRenderDispatcher.submit(renderState, poseStack, submitNodeCollector, levelRenderState.cameraRenderState);
```

`BlockEntityRenderDispatcher.submit(S state, PoseStack, SubmitNodeCollector, CameraRenderState)` looks up the renderer
by `state.blockEntityType` and calls `BlockEntityRenderer.submit(…)` (wrapped in a crash report).

Example, `ChestRenderer.submit`: one `submitModel(model, open, poseStack, light, NO_OVERLAY, -1, spriteId, sprites, 0)`
using the atlas sprite as `UvMapping`, plus `order(1).submitCrumblingOverlay(…)` while breaking. `ChestRenderState.open`
comes from `ChestBlock.opennessCombiner(blockEntity)….get(partialTicks)` (`ChestLidController.getOpenness(float)`),
so a closed chest has `open == 0`. The chest model's state type is a boxed `Float`.

### What Sodium 0.9 replaces

`net.caffeinemc.mods.sodium.mixin.core.render.world.LevelExtractorMixin`:

- cancels `extractVisibleBlockEntities` at HEAD and calls
  `SodiumWorldRenderer.extractBlockEntities(Camera, float, Long2ObjectMap<SortedSet<BlockDestructionProgress>>, LevelRenderState)`,
  which iterates Sodium's own render lists (`RenderRegion.getCulledBlockEntities(sectionId)`, then
  `getGlobalBlockEntities`) and still calls vanilla `BlockEntityRenderDispatcher.tryExtractRenderState` per block entity;
- `@Overwrite`s `setBlocksDirty`, `setSectionDirtyWithNeighbors`, `setBlockDirty(BlockPos, boolean)` and
  `setSectionDirty(int, int, int, boolean)` to schedule its own rebuilds.

Submit is untouched: Sodium does not mixin `LevelRenderer.submitBlockEntities` or `BlockEntityRenderDispatcher`.
The Sodium 0.9.2 **NeoForge** jar additionally targets a NeoForge-patched
`extractVisibleBlockEntities(Camera, float, LevelRenderState, Frustum)` that NeoForge 26.3.0.94 no longer has (crash on
startup); 0.9.3-beta.1 dropped that mixin.

**Consequence for Module 1:** the per-object choke points shared by vanilla, Sodium and Iris's shadow pass are
`BlockEntityRenderDispatcher.tryExtractRenderState` (extract) and `BlockEntityRenderDispatcher.submit` (submit).

---

## 2. Item frames, armor stands, display entities

All entities go through `LevelExtractor.extractVisibleEntities(Camera, Frustum, DeltaTracker, LevelRenderState)`
(`EntityRenderDispatcher.shouldRender(…)` frustum/distance test plus `isSectionCompiledAndVisible`) →
`EntityRenderDispatcher.extractEntity(Entity, float)`, and are submitted by
`EntityRenderDispatcher.submit(S, CameraRenderState, double x, double y, double z, PoseStack, SubmitNodeCollector)`:
translate by camera-relative position plus `renderer.getRenderOffset(state)`, `renderer.submit(…)`, then
`submitFlame` (if burning) and `submitShadow` (if `shadowPieces` is not empty). `EntityRenderer.submit` adds leashes and
the name tag.

| Entity | Renderer and submits |
| --- | --- |
| Item frame | `ItemFrameRenderer.submit`: frame block model via `BlockModelRenderState.submitWithZOffset(…)` (render type `RenderTypes.entitySolidZOffsetForward(LOCATION_BLOCKS)`); a held item via `ItemStackRenderState.submit(…)` → `submitItem`; a map via `MapRenderer.render(…)` → `submitCustomGeometry(pose, RenderTypes.text(mapTexture), …)` per map and per decoration. Rotation is `state.rotation`, glow frames use full-bright light. |
| Armor stand | `ArmorStandRenderer.submit` picks small/big model, then `LivingEntityRenderer.submit` (below). |
| Block display | `DisplayRenderer.BlockDisplayRenderer.submitInner` → `state.blockModel.submit(…)`. |
| Item display | `DisplayRenderer.ItemDisplayRenderer.submitInner` → `state.item.submit(…)`. |
| Text display | `DisplayRenderer.TextDisplayRenderer.submitInner` → text submits. |

`DisplayRenderer.submit` applies `calculateOrientation(…)` from `billboardConstraints()`: `FIXED` uses only the entity
rotation, `HORIZONTAL`/`VERTICAL`/`CENTER` use the camera rotation, then `transformation().get(interpolationProgress)`.
Only `FIXED` displays with finished interpolation have a camera-independent pose.

`LivingEntityRenderer.submit` submits the body with
`submitModel(this.model, state, poseStack, renderType, light, overlay, tint, null, outlineColor)` and, if it has layers,
**calls `this.model.setupAnim(state)` itself** before `layer.submit(…)` so that layers can read the posed parts (held
items, armor). The body is posed again in prepare, so a living entity with layers runs `setupAnim` at least twice per
frame.

---

## 3. The model feature renderer

### Submit record

`SubmitNodeCollection.submitModel(Model<? super S>, S state, PoseStack, RenderType, int light, int overlay, int tint,
@Nullable UvMapping, int outlineColor)` — the plan's "`submitModel` takes a `UvMapping`" is confirmed:

```java
PoseStack.Pose pose = poseStack.last().copy();
if (!renderType.isOutline()) {
   ModelFeatureRenderer.Submit<S> submit = new ModelFeatureRenderer.Submit<>(renderType, pose, model, state, lightCoords,
      overlayCoords, tintedColor, uvMapping, null);
   if (renderType == RenderTypes.waterMask()) this.waterMask.submit(submit);
   else if (!renderType.forceSolidModelPhase() && renderType.hasBlending()) this.translucentModels.submit(submit);
   else this.solid.submit(submit);
}
if (outlineColor != 0) { … this.outline.submit(new ModelFeatureRenderer.Submit<>(outlineRenderType, …)); }
```

The record keeps a **reference** to the shared `Model` and to the render state; posing is deferred. A glowing entity
is submitted twice (normal + outline) and therefore posed and tessellated twice.

### Prepare: `setupAnim` runs right before tessellation

`ModelFeatureRenderer.prepareModel(ModelFeatureRenderer.Submit<S>)`, called from `buildGroup` for every submit of a
group:

```java
this.poseStack.last().set(submit.pose());
VertexConsumer buffer = this.getVertexBuilder(submit.renderType());
if (submit.sheetedDecalPose() != null) buffer = new SheetedDecalTextureGenerator(buffer, submit.sheetedDecalPose(), 1.0F);
else if (submit.uvMapping() != null) buffer = submit.uvMapping().wrap(buffer);
Model<? super S> model = submit.model();
model.setupAnim(submit.state());
model.renderToBuffer(this.poseStack, buffer, submit.lightCoords(), submit.overlayCoords(), submit.tintedColor());
```

`Model.setupAnim(S)` defaults to `resetPose()` (every part back to its initial pose). `Model.renderToBuffer` is
`final` and only calls `root().render(poseStack, buffer, light, overlay, color)`.

NeoForge 26.3.0.94 patches `prepareModel`: between `setupAnim` and `renderToBuffer` it calls
`ClientHooks.updateModelVisibility(model, state)` (part visibility from `RegisterRenderStateModifiersEvent`) and
afterwards `ClientHooks.resetVisibility(previous)`. Wrapping the two vanilla calls works on both loaders.

### Part tree walk

`ModelPart.render(PoseStack, VertexConsumer, int light, int overlay, int color)`:

```java
if (this.visible) {
   if (!this.cubes.isEmpty() || !this.children.isEmpty()) {
      poseStack.pushPose();
      this.translateAndRotate(poseStack);
      if (!this.skipDraw) this.compile(poseStack.last(), buffer, lightCoords, overlayCoords, color);
      for (ModelPart child : this.children.values()) child.render(poseStack, buffer, lightCoords, overlayCoords, color);
      poseStack.popPose();
   }
}
```

- `visible == false` hides the part **and its children**; `skipDraw` hides only the part's own cubes.
- Parts with neither cubes nor children are skipped without touching the pose stack.
- Children are visited in `Map` iteration order of `children` (the map passed to the constructor).
- `translateAndRotate`: translate by `(x, y, z) / 16`, rotate by `Quaternionf().rotationZYX(zRot, yRot, xRot)` if any
  angle is non-zero, scale by `(xScale, yScale, zScale)` if not 1.
- `compile` (private) → `Cube.compile(PoseStack.Pose, VertexConsumer, light, overlay, color)` per cube, which transforms
  each polygon normal with `pose.transformNormal` and each vertex position (`Vertex.x/16` …) with `pose.pose()` and
  writes `addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz)`. A cube has one `Polygon` per visible face
  (`visibleFaces`), four vertices each; UVs are baked into `Vertex.u/v` at construction (mirroring reverses the vertex
  order).

### UvMapping

`net.minecraft.client.renderer.texture.UvMapping` has `getU(float)`, `getV(float)` and
`default VertexConsumer wrap(VertexConsumer)` → `new SpriteCoordinateExpander(buffer, this)`, which only rewrites
`setUv(u, v)` to `setUv(mapping.getU(u), mapping.getV(v))`. Atlas sprites implement it (the chest passes
`sprites.get(spriteId)`). It is applied per submit, before the tree walk.

### Vertex format

`DefaultVertexFormat.ENTITY` (36 bytes): `Position` RGB32_FLOAT, `Color` RGBA8_UNORM, `UV0` RG32_FLOAT, `UV1`
RG16_SINT (overlay), `UV2` RG16_SINT (light), `Normal` RGBA8_SNORM.

---

## 4. Prepared frame: order, grouping, OIT

### Phases and groups

`SubmitNodeCollection(boolean useImprovedTransparency, TranslucentFeatureRenderPhase seeThrough)` creates the phases
`solid`, `waterMask`, `outline`, `alwaysOnTopGizmos`, `oitTranslucent` and, without improved transparency, separate
`shadows`, `nameTags`, `texts`, `shapeOutlines`, `translucentBlocksAndItems`, `translucentModels`,
`translucentCustomGeometry`, `breakingOverlay`, `afterTerrain`, `translucentGizmos`. **With improved transparency (OIT)
all of the translucent ones are the same `oitTranslucent` phase.** `SubmitNodeStorage.getSubmitsPerOrder()` holds one
collection per `order(int)` (crumbling overlays use order 1).

`SimpleFeatureRenderPhase.sortInto` emits per feature type first the unbatched submits, then one group per
`BatchableSubmit.batchKey()` — for models that is the `RenderType`, so solid models are grouped by render type.
Translucent phases (`TranslucentFeatureRenderPhase`) sort by `distanceToCameraSq()`.

`FeatureRenderDispatcher.prepareFrameWithContext`: `drainPhases(phase.sortInto(PhaseSubmitGrouper))` →
`beginPrepare` on every feature renderer → `group.prepare(…)` per feature type → `finishPrepare` →
`stagedVertexBuffer.upload()`.

`RenderTypeFeatureRenderer.prepareGroup` (base of the model, item, block model, text and custom geometry renderers)
creates a `Group` that appends one `StagedVertexBuffer.Draw` per render type (`getOrAddDraw` reuses an existing draw
of the same `PreparedRenderType` when the group may reorder). `RenderType.prepare()` resolves textures and writes the
**`DynamicTransforms` uniform from `RenderSystem.getModelViewMatrixCopy()`** at prepare time.

`StagedVertexBuffer` (one per `RenderBuffers`, `RenderBuffers.stagedVertexBuffer()`, initial 4 MiB): CPU
`BufferBuilder`s over one `ByteBufferBuilder`; `upload()` lays out all draws, writes them into a mapped staging
`GpuBuffer`, sorts quads where required, and `copyToBuffer`s into pooled vertex and index buffers. Pools are recycled
in `endFrame()`.

### Execute order in the level

`FeatureRenderDispatcher.renderAllFeatures(RenderPass, PreparedFrame)` (solid → translucent → translucent after
terrain → see-through → always on top) is **not** used for the level; it is used by `GameRenderer` (hand),
`GuiItemAtlas`, `PictureInPictureRenderer` and Iris's shadow pass. The level calls the phases itself from the `main`
frame pass in `LevelRenderer.addMainPass`:

1. Render pass "Main" (classic) or "Solid" (OIT): `executeSolid` → opaque terrain
   (`ChunkSectionLayerGroup.OPAQUE`), then `featureFrame.executeSolid(renderPass)`.
2. Classic transparency (same pass): `featureFrame.executeTranslucent` (shadows, translucent models, name tags, texts,
   translucent custom geometry; then shape outlines, translucent gizmos; then translucent blocks and items, breaking
   overlay, water mask) → translucent terrain → `executeTranslucentAfterTerrain` → clouds, weather, world border.
3. OIT (`GameRenderer.useImprovedTransparency()`): `executeOit` loops `OitStage.values()` = `DEPTH_BOUNDS`,
   `TRANSMITTANCE`, `ACCUMULATE`. Per stage it renders OIT terrain, then opens a pass from
   `OitRenderPassProvider.createRenderPass(stage, …)` and calls `featureFrame.executeOit(stage, renderPass)`, which
   draws every `oitTranslucent` group with `PreparedRenderType.drawFromBufferOit(info, stage, renderPass)` (the render
   type's `OitPipelineSet.getPipeline(stage)`). **Every translucent feature is drawn three times**; vertices are
   uploaded once. After `DEPTH_BOUNDS` a depth-bounds cull pass runs; the stages end in an "OIT Composite" pass.
4. "Outline" pass into the entity outline target (`executeOutline`), "See through features", "Always on top features".

Each draw is `PreparedRenderType.drawFromBuffer(StagedVertexBuffer.ExecuteInfo, RenderPass)`:

```java
renderPass.setPipeline(RenderSystem.getCompiledPipeline(renderPipeline));
RenderSystem.bindDefaultUniforms(renderPass);
renderPass.setUniform("DynamicTransforms", this.dynamicTransforms);
renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
for (PreparedRenderType.Texture texture : this.textures) renderPass.setUniform(texture.name, texture.textureView, texture.sampler);
renderPass.setIndexBuffer(indexBuffer, info.indexType());
renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
```

`ExecuteInfo(GpuBuffer vertexBuffer, @Nullable GpuBuffer customIndexBuffer, IndexType, int baseVertex, int firstIndex,
int indexCount, PrimitiveTopology)` is a public record, so Mobium can draw its own vertex buffers through exactly this
path (with `RenderSystem.getSequentialBuffer(topology)` as index buffer for unsorted quads).

---

## 5. Renderpearl

| Topic | Finding |
| --- | --- |
| Device | `RenderSystem.getDevice()` / `tryGetDevice()` → `GpuDevice` (`FrontendGpuDevice` around a `GpuDeviceBackend`). `getDeviceInfo()` → `DeviceInfo(name, vendorName, driverInfo, isZZeroToOne, backendName, timestampPeriod, DeviceLimits, DeviceFeatures, underlyingExtensions, HintsAndWorkarounds, DeviceType)`. `backendName` is `"OpenGL"` (`GlHeuristics.createDeviceInfo`) or `"Vulkan"` (`VulkanDevice`). |
| Features | `DeviceFeatures(wireframeFillMode, shaderDrawParameters, multiDrawDirectInterleaved, multiDrawDirectSeparate, multiDrawIndirect, drawIndirect, nonZeroFirstInstance, persistentMapping)`. On GL, `underlyingExtensions` lists only the extensions Mojang enabled, not all of them. |
| Buffers | `GpuDevice.createBuffer(Supplier<String> label, @Usage int usage, long size)` or `(…, ByteBuffer data)`. Usages: `MAP_READ 1`, `MAP_WRITE 2`, `HINT_CLIENT_STORAGE 4`, `COPY_DST 8`, `COPY_SRC 16`, `VERTEX 32`, `INDEX 64`, `UNIFORM 128`, `UNIFORM_TEXEL_BUFFER 256`, `INDIRECT_PARAMETERS 512`. **No storage-buffer usage.** |
| Upload | `CommandEncoder.writeToBuffer(GpuBufferSlice, ByteBuffer)`, `copyToBuffer(GpuBufferSlice, GpuBufferSlice)`, `GpuBuffer.map(offset, length, read, write)` → `GpuBufferSlice.MappedView`; `createFence()`. Vanilla stages through a mapped buffer and copies (`StagedVertexBuffer.uploadDrawsToBuffers`). |
| Drawing | `CommandEncoder.createRenderPass(RenderPassDescriptor)`; `RenderPass.setPipeline(CompiledRenderPipeline)`, `setUniform(name, GpuBufferSlice / GpuTextureView + GpuSampler)`, `setVertexBuffer(slot, slice)`, `setIndexBuffer(GpuBuffer, IndexType)`, `drawIndexed`, `multiDrawIndexed`, `drawIndexedIndirect`, `pushConstants`. Pipelines come from `RenderSystem.getCompiledPipeline(RenderPipeline)`. |
| Compute | **None in the API.** No compute pipeline, no dispatch, no storage buffers. `VulkanDevice` creates an internal `computeQueue` (falls back to the graphics queue) but does not expose compute. The Vulkan backend in detail: §18. |
| GL interop | Buffers from the GL backend are `com.mojang.renderpearl.backend.opengl.GlBuffer` (returned unwrapped by `FrontendGpuDevice.createBuffer`) with public `int handle()`. LWJGL capabilities are created on the render thread in the `GlDevice` constructor (`GL.createCapabilities()`), so `GL.getCapabilities()` works there. Mobium's GL compute path therefore uses raw GL 4.3 on a Renderpearl-created buffer's handle plus `glMemoryBarrier`. |
| Backend selection | `Minecraft` constructor: `PreferredGraphicsApi` (`DEFAULT`, `OPENGL`, `VULKAN`) from options or the `forcedGraphicsApi` launch argument; `getBackendsToTry()` is `{gl, vulkan}` for `DEFAULT`/`OPENGL` and `{vulkan, gl}` for `VULKAN`. OpenGL is the 26.3 default (confirmed). After an unclean start the option falls back to `DEFAULT`/`OPENGL`. |

---

## 6. Change signals

| Signal | Where it surfaces on the client | Notes |
| --- | --- | --- |
| Block entity data | `ClientPacketListener.handleBlockEntityData(ClientboundBlockEntityDataPacket)` → `BlockEntity.loadWithComponents(…)` | No render notification: vanilla re-extracts every frame. |
| Block events (chest lid, …) | `Level.blockEvent(BlockPos, Block, int, int)` → `BlockState.triggerEvent(…)`; lids animate through `ChestLidController.getOpenness(float)` | The render state's `open` is non-zero while opening/closing. |
| Block state change | `ClientLevel.sendBlockUpdated(BlockPos, BlockState, BlockState, int)` → `LevelExtractor.blockChanged`; `ClientLevel.setBlocksDirty(BlockPos, BlockState, BlockState)` → `LevelExtractor.setBlockDirty` (only if `ModelManager.requiresRender(old, new)`) | Both mark the 3×3×3 neighbourhood of sections dirty. |
| Light change | `ClientChunkCache.onLightUpdate(LightLayer, SectionPos)` → `levelExtractor.setSectionDirty(x, y, z)`; light packets: `ClientPacketListener.readSectionList(…)` → `ClientLevel.setSectionDirtyWithNeighbors` | Block entity light is read in `extractBase` every frame. |
| Section dirty (all of the above) | `LevelExtractor.setSectionDirty(int, int, int, boolean)` → `SectionUpdateTracker.setDirty` | **Sodium `@Overwrite`s this and its siblings**; hook the `ClientLevel`/`ClientChunkCache` callers instead (Sodium only touches `ClientLevel.<init>`/`unload`, `ClientChunkCache.drop`/`replaceWithPacketData`). |
| Resource reload | `Minecraft` registers `ResourceManagerReloadListener`s in order; `EntityRenderDispatcher` (~l. 623) and `BlockEntityRenderDispatcher` (l. 633, rebuilds all renderers and models) come before `LevelExtractor` (l. 655) | Register Mobium's listener through the loader APIs; it runs after the vanilla ones. |
| Render distance | `LevelExtractor.extract` compares `options.getEffectiveRenderDistance()` with `lastViewDistance` → `allChanged()` | |
| Level change | `LevelExtractor.setLevel(@Nullable ClientLevel)` → `allChanged()` / reset; Fabric: `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE` (`Minecraft.updateLevelInEngines`) | Sodium injects at `setLevel` RETURN. |

---

## 7. Iris 1.11.7

### Extended vertex formats

`net.irisshaders.iris.vertices.IrisVertexFormats.ENTITY` (56 bytes):

| Offset | Attribute | Format |
| --- | --- | --- |
| 0 | `Position` | RGB32_FLOAT |
| 12 | `Color` | RGBA8_UNORM |
| 16 | `UV0` | RG32_FLOAT |
| 24 | `UV1` | RG16_SINT |
| 28 | `UV2` | RG16_SINT |
| 32 | `Normal` | RGBA8_SNORM |
| 36 | `iris_Entity` | RGBA16_UINT: (entity id, block entity id, item id, 0) |
| 44 | `mc_midTexCoord` | RG32_FLOAT |
| 52 | `at_tangent` | RGBA8_SNORM |

`ENTITY_GLINT_SPECIAL` is the same plus `UV3`; `TERRAIN` and `GLYPH` are the block and text equivalents.

### How the extra attributes are written

`net.irisshaders.iris.mixin.vertices.MixinBufferBuilder` swaps the format **when the `BufferBuilder` is constructed**
(`@ModifyVariable` on `<init>`): `DefaultVertexFormat.ENTITY` → `IrisVertexFormats.ENTITY` if
`!ImmediateState.skipExtension.get() && ImmediateState.isRenderingLevel && Iris.isPackInUseQuick()`. Then:

- `addVertex(FFF)` RETURN writes `iris_Entity` from `CapturedRenderingState.INSTANCE.getCurrentRenderedEntity()`,
  `…BlockEntity()`, `…Item()` — **per vertex**.
- `endLastVertex` HEAD collects four vertices of a quad and calls `fillExtendedData(4)`:
  - `midTexCoord` = mean of the four UVs;
  - while rendering the level, the vertex **normal is replaced** by the face normal
    `NormalHelper.computeFaceNormal` (normalised cross product of the diagonals `v2 − v0` and `v3 − v1`);
  - `at_tangent` = `NormalHelper.computeTangent(nx, ny, nz, polygon)` from vertices 0–2 (positions projected on the
    normal plane, UV deltas, `w` = handedness sign), packed with `NormI8.pack`.
  Triangles use `computeTangentSmooth` with the per-vertex normal instead.

### Entity ids in 26.3

`MixinEntityRenderDispatcher` / `MixinBlockEntityRenderDispatcher` set `CapturedRenderingState` around `submit`
(`blockStateIds.applyAsInt(state.blockState)` for block entities). `MixinModelSubmit` stores the ids **in each
`ModelFeatureRenderer.Submit`** at construction (`iris$capture`), and `MixinModelFeatureRenderer`
`@WrapMethod`s `prepareModel` to restore them (`iris$set`) while the vertices are written, then resets them to 0.
Item, block model, text and custom geometry submits have equivalent mixins.

### Shader-pack reload and shadow pass

- No reload event in the API (`net.irisshaders.iris.api.v0.IrisApi`: `isShaderPackInUse()`,
  `isRenderingShadowPass()`, `assignPipeline(RenderPipeline, IrisProgram)`, `registerShadowRenderCallback(…)`, …).
  Reloads go through `Iris.reload()` → `destroyEverything()` → `loadShaderpack()`; detect a change by polling
  `isShaderPackInUse()` (and the active pipeline) per frame or with a compat mixin on `Iris.reload`.
- `ShadowRenderer` owns its **own** `SubmitNodeStorage` and `FeatureRenderDispatcher` (with its own `RenderBuffers`).
  Per shadow pass it extracts entities against a shadow frustum (`extractVisibleEntities`), block entities through
  `SodiumWorldRenderer.extractBlockEntities`, submits them through the vanilla `EntityRenderDispatcher.submit` and
  `BlockEntityRenderDispatcher.submit`, runs `prepareFrame` and draws with `FeatureRenderDispatcher.renderAllFeatures`.
  So every Mobium hook on submit/prepare also fires in the shadow pass, with a different staged buffer and model-view.
- Read again for the beta2 shadow timers (23 Oct 2026; `ShadowRenderer`, identical signatures in the Fabric and
  NeoForge jars): the pass is `renderShadows(LevelRendererAccessor, Camera, CameraRenderState)`, called by Iris's
  `MixinLevelRenderer.iris$renderTerrainShadows` right before `LevelRenderer.addMainPass`, i.e. inside
  `LevelRenderer.render` after the level's own `prepareFrame` (`RenderSystem.isRenderingLevel` is true, so a timer on
  `PreparedFrame.executePhase` sees the shadow draws too). Entity extraction is Iris's own private
  `extractVisibleEntities(Camera, Frustum, DeltaTracker, LevelRenderState)` (a copy of vanilla's loop calling
  `EntityRenderDispatcher.extractEntity`), not `LevelExtractor`'s; block entities come from the private
  `extractVisibleBlockEntities(SodiumWorldRenderer, float, Camera, LevelRenderState, boolean)`. Submits run in the
  private `renderEntities(…)` and `renderBlockEntities(…)` (both return the count), then
  `featureRenderDispatcher.prepareFrame(submitNodeStorage)` and `FeatureRenderDispatcher.renderAllFeatures`. The
  shadow renderer's `levelRenderState` is reset at the start of each pass's entity part only, so its entity render
  states live until the next shadow pass (vanilla clears the level's list right after `submitEntities`).

---

## 8. EMF and ETF

### EMF 3.3.12

- Replaces model roots with its own part classes (`traben.entity_model_features.models.parts.EMFModelPartRoot`,
  `EMFModelPartVanilla`, `EMFModelPartCustom`, all extending `EMFModelPart extends ModelPart`). **`EMFModelPart`
  overrides `ModelPart.render`**: `renderWithTextureOverride` can switch to another render type per part
  (`provider.getBuffer(layer)` on an ETF provider, or a fresh `stagedVertexBuffer().appendDraw(…)`), and
  `renderLikeVanilla` repeats the vanilla walk (`translateAndRotate`, `compile`, children) on the possibly different
  consumer.
- Animation runs inside `setupAnim` of the replaced roots; `Mixin_ModelRenderer` (priority 900) injects into
  `ModelFeatureRenderer.prepareModel` **after `Model.setupAnim`** to apply biped armour poses.
- Also hooks `LivingEntityRenderer`, `EntityRenderDispatcher`, `ItemFrameRenderer`, `BannerRenderer`, block entity
  renderer factories (`MixinBlockEntityRendererFactories`: custom block entity models, e.g. animated chests),
  `ModelPart` (`render` HEAD), `Cube`, `Model`.
- **EMF already throttles animations**: `utils.EMFLODHandler.isLODSkippingThisFrame(String modelId)` with
  `EMFConfig.animationLODDistance = 20` (on by default; scales with FOV, optionally FPS and mob size; villagers, horses
  and blazes are exempt) and `animationFrameSkipDuringIrisShadowPass = true`.

### ETF 7.2.6

- `MixinVertexConsumerProvider$Immediate` targets `RenderTypeFeatureRenderer.getVertexBuilder` (priority 800):
  `@ModifyVariable` HEAD swaps the render type (random/variant textures) and records it at RETURN.
- `MixinModelPart` (priority 2000) on `ModelPart.render` HEAD/RETURN; together with `ETFVertexConsumer` on
  `BufferBuilder` it knows the texture of the consumer being drawn.
- Emissive layers: `ETFUtils2.renderEmissive(ETFTexture, URenderTypeToVertexConsumer provider, RenderMethodForOverlay)`
  takes a new consumer for `RenderTypes.beaconBeam(emissive, true)` (bright mode) or
  `RenderTypes.entityTranslucent(emissive)` from the same group and **re-renders the part tree** into it with light
  `15728882`; `renderEnchanted` does the same with `armorCutoutNoCullGlint`. Both run at the root (part depth 1) after
  the normal walk. Also hooks `BlockEntityRenderDispatcher.submit` HEAD/RETURN, `ChestSpecialRenderer`, `SpriteId`,
  `SpriteCoordinateExpander`, `SheetedDecalTextureGenerator`.

**Consequence for Module 2:** walking EMF trees ourselves would skip EMF's per-part render-type switches and ETF's
overlay passes. The safe interception point is below them: `ModelPart.compile(PoseStack.Pose, VertexConsumer, int,
int, int)` (per part, all cubes, one pose) or `Cube.compile`, where the pose, the target consumer (= render type /
staged draw) and light/overlay/colour are all final. EMF's `renderLikeVanilla` reaches the same `compile`.

---

## 9. Sodium 0.9

- **Model-part fast path still exists**: `mixin.features.render.entity.CubeMixin` builds a `ModelCuboid` per
  `ModelPart.Cube` and cancels `Cube.compile` (inject before `pose.pose()`) when the consumer converts to Sodium's
  `VertexBufferWriter`, writing the cube with `client.render.immediate.model.EntityRenderer.renderCuboid(…)`;
  `ModelPartMixin` `@Overwrite`s `ModelPart.translateAndRotate`. A Mobium hook on `ModelPart.compile` runs before
  Sodium's, so handled parts never reach it and fallback parts keep Sodium's path.
- Mods can switch Sodium mixins off statically with the `"sodium:options"` custom value in `fabric.mod.json`
  (Iris uses `"mixin.features.render.world.sky": false`).
- Entity culling: `features.render.entity.cull.EntityRendererMixin`; shadows: `ShadowFeatureRendererMixin`.
- Config API: `net.caffeinemc:sodium-fabric-api` / `sodium-neoforge-api` (`0.9.2+mc26.3`, `0.9.3-beta.1+mc26.3`) on
  `https://maven.caffeinemc.net/releases`, classes `net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint` and
  `ConfigEntryPointForge` (both jars). Iris registers through the `sodium:config_api_user` entrypoint
  (`net.irisshaders.iris.compat.sodium.config.IrisConfig`).
- Iris 1.11.7 on Fabric depends on `sodium: 0.9.x`, on NeoForge on `[0.6,)`.

---

## 10. Hook list

| Hook | Target class / method | Injection | Module |
| --- | --- | --- | --- |
| Block entity submit | `BlockEntityRenderDispatcher.submit(BlockEntityRenderState, PoseStack, SubmitNodeCollector, CameraRenderState)` | `@WrapMethod` (skip the vanilla submit for cached objects; fallback: call original) | 1 |
| Block entity extract | `BlockEntityRenderDispatcher.tryExtractRenderState(BlockEntity, float, CrumblingOverlay, boolean)` | `@WrapMethod` (skip extraction for cached, unchanged objects; reached by vanilla, Sodium and Iris shadow) | 1 |
| Static entity submit | `EntityRenderDispatcher.submit(EntityRenderState, CameraRenderState, double, double, double, PoseStack, SubmitNodeCollector)` | `@WrapMethod`, item frames / armor stands / `FIXED` displays only | 1 |
| Cached geometry draw | `FeatureRenderDispatcher.PreparedFrame.executeSolid(RenderPass)` (level `main` pass, after vanilla solid features) | `@Inject` TAIL; must ignore the hand/GUI/PiP callers of `renderAllFeatures`; draws via `PreparedRenderType.drawFromBuffer` | 1 |
| Invalidation: blocks | `ClientLevel.sendBlockUpdated`, `ClientLevel.setBlocksDirty`, `ClientLevel.setSectionDirtyWithNeighbors`, `ClientLevel.setSectionRangeDirty` | `@Inject` HEAD | 1 |
| Invalidation: light | `ClientChunkCache.onLightUpdate(LightLayer, SectionPos)` | `@Inject` HEAD | 1 |
| Invalidation: level / distance | `LevelExtractor.setLevel(ClientLevel)`, `LevelExtractor.allChanged()` | `@Inject` RETURN | 1 |
| Invalidation: reload | loader reload listener (Fabric resource loader / NeoForge `AddClientReloadListenersEvent`) | event | 1, 2 |
| Part records (shipped, M6) | `ModelPart.compile(PoseStack.Pose, VertexConsumer, int, int, int)` (private; vanilla parts) and EMF's `EMFModelPart.compile` override (`compat.emf.EmfModelPartMixin`, `@Pseudo`, optional) | `@WrapMethod`: reserve the vertices and write records when the consumer is a staged builder (directly or through an atlas sprite) of an unsorted entity-format draw and every cube is a vanilla cube or an `EMFCube`; else original | 2 |
| Staged builders and upload (shipped, M6) | `StagedVertexBuffer.getVertexBuilder` (`NEW BufferBuilder`: tag it with its draw), `uploadDrawsToBuffers` (`ByteBuffer.put`: leave the reserved ranges out), `upload` RETURN (expand pass), `endDraw` HEAD (drop leftovers); `BufferBuilder` (reservation), `StagedVertexBuffer.Draw` (layout, slot) | `@ModifyExpressionValue`, `@WrapOperation`, `@Inject`, fields and accessors | 2 |
| Entity exclusions (shipped, M6) | `EntityRenderDispatcher.submit` around `EntityRenderer.submit` (mark), `ModelFeatureRenderer.Submit.<init>` (carry), `prepareModel`'s `renderToBuffer` (read) | the existing `@WrapOperation`s and `@Inject` | 2 |
| Animation LOD | `ModelFeatureRenderer.prepareModel` around `Model.setupAnim`; `LivingEntityRenderer.submit` around its layer `setupAnim` | `@WrapOperation` | 3 |
| Debug timers (M2, shipped) | `LevelExtractor.extractVisibleEntities` / `extractVisibleBlockEntities`, `LevelRenderer.submitEntities` / `submitBlockEntities`, the `prepareFrame` call in `LevelRenderer.render`, `Model.setupAnim` / `Model.renderToBuffer` in `ModelFeatureRenderer.prepareModel`, `FeatureRenderDispatcher.PreparedFrame.executePhase(…, OitStage, RenderPass)`, `Minecraft.runTick` HEAD; `ModelFeatureRenderer.Submit` gets a field marking block entity submits | `@WrapMethod`, `@WrapOperation`, `@Inject` | M2 instrumentation |
| Shadow pass timers (beta2) | Iris `ShadowRenderer.renderShadows`, `extractVisibleEntities` / `extractVisibleBlockEntities`, `renderEntities` / `renderBlockEntities`, the `prepareFrame` call in `renderShadows` (`compat.iris.ShadowRendererMixin`, `@Pseudo`) | `@Inject` HEAD/RETURN, `@WrapOperation`, all `require = 0` | M6 measurement basis |

Open points for later milestones (not answered by reading code):

- *Answered in M6 (§16):* Module 2 reserves the vertex bytes in the builder and leaves them out of the upload's copy;
  Iris's three entity ids travel per part record (they are the same for all vertices of one part).
- `RenderType.prepare()` writes `DynamicTransforms` from the current model-view matrix. The static cache calls it in
  the prepare stage, after the level's own prepare; that this is allowed on Vulkan is checked in game in M7
  (`PLAN.md`, Module 1 on Vulkan).

---

## 11. Data and command formats (found while writing the benchmark datapack)

- Block states in NBT (e.g. `block_display`'s `block_state`) are an id string (`"minecraft:stone"`) or a compound with
  **`id`** and properties; the old `{Name: …}` form fails with "No key id" and leaves the display empty.
- Item frames and paintings (`BlockAttachedEntity`) read `block_pos` and reject it unless it lies within 16 blocks of
  the entity's position at load time; `/summon` sets the position after loading, so the NBT needs `Pos` as well.
- Gamerules are `snake_case` registry ids (`advance_time`, `spawn_mobs`, `random_tick_speed`,
  `max_block_modifications`, …).
- Loot table entries use `modifier` (not `functions`); `exploration_map` creates real map data
  (`MapItem.applyNewSavedData` + `renderBiomePreviewMap`) but finds nothing when structures are disabled
  (`ChunkGenerator.findNearestMapStructure`).
- Item frames only take the map path (`ItemFrameRenderState.mapId`) when the client has the map's data
  (`ItemFrameRenderer.extractRenderState` → `level.getMapData`); otherwise the map renders as an item.
- `pack.mcmeta` for 26.3 data packs: `"min_format": 121, "max_format": 121`.

---

## 12. Frame-rate limits that distort measurements

- `Options.framerateLimit()` defaults to 120; `Options.UNLIMITED_FRAMERATE_CUTOFF` (260) means unlimited.
- `FramerateLimitTracker.getThrottleReason()`: with `inactivityFpsLimit` = AFK (default) the limit drops to 30 FPS after
  60 s without input and to 10 FPS after 10 min (`onInputReceived()` resets it); an iconified window, or an unfocused
  exclusive-fullscreen one, runs at 10 FPS. `/mobium bench` resets the idle timer every frame while it records.

## 13. Capturing an object through vanilla (M3)

- `SubmitNodeStorage` can be instantiated and used as a private collector; `drainPhases` hands over each non-empty
  phase and `FeatureRenderPhase.sortInto(Output)` empties it into any `FeatureRenderPhase.Output`.
- `RenderTypeFeatureRenderer.buildGroup(FeatureFrameContext, List)` (protected, reached with an `@Invoker`) runs a
  feature renderer's own vertex writing; `ModelFeatureRenderer.buildGroup` ignores the context and calls
  `prepareModel` per submit. Redirecting `getVertexBuilder` at its head hands it any `VertexConsumer`
  (`new BufferBuilder(ByteBufferBuilder, PrimitiveTopology, VertexFormat)` → `build()` → `MeshData`).
- NeoForge adds `tryExtractRenderState(…, Frustum)` and deprecates the vanilla overload, which forwards to it; vanilla's
  `LevelExtractor` calls the new one (culling by `getRenderBoundingBox`), Sodium the old one.
- Light reaching a block entity: `ClientChunkCache.onLightUpdate` fires for sections the client light engine changed
  (`LayerLightSectionStorage.swapSectionMap`), not for section data the server sends (`queueSectionData`); those
  only mark sections dirty for a rebuild, and only when the packet asks for it.

## 14. Settings, toasts and loader hooks (M4)

Looked up on 20 Oct 2026 for the settings screens, the fallback toast and the benchmark's environment columns.

| Topic | Finding |
| --- | --- |
| Options screens | `net.minecraft.client.gui.screens.options.OptionsSubScreen(Screen lastScreen, Options, Component title)`: `addOptions()` fills `list` (`OptionsList`: `addHeader(Component)`, `addBig(OptionInstance / AbstractWidget)`, `addSmall(OptionInstance...)`), `addFooter()` puts a Done button into `layout` (`HeaderAndFooterLayout`), `onClose()` applies the list and returns to `lastScreen`, `removed()` saves `options.txt`. Two footer buttons: `layout.addToFooter(LinearLayout.horizontal().spacing(8))` as in `WorldOptionsScreen`. |
| Option widgets | `OptionInstance.createBoolean(captionKey, TooltipSupplier, initial, ValueUpdateListener)`; generic `OptionInstance(captionKey, tooltip, CaptionBasedToString, ValueSet, initial, listener)` with `OptionInstance.IntRange(min, max)` (slider, applied immediately) or `OptionInstance.Enum(values, codec)` (cycle button). `Options.genericValueLabel(caption, Component)` formats "Caption: value". The listener only fires while the game is running. |
| Toasts | `SystemToast.add(ToastManager, SystemToast.SystemToastId, Component title, Component message)`; the manager is `minecraft.gui.toastManager()`, `ToastManager.getToast(Class, token)` finds a shown toast by its id. Screens open with `minecraft.gui.setScreen(…)`. |
| Monitor and window | `Window.findBestMonitor()` → `Monitor` record (`name()`, `currentMode()`); `Window.getActiveVideoMode()` (render thread); `Window.getWidth()/getHeight()` are the framebuffer size. The game logs "Exclusive target … on monitor …" whenever the fullscreen window moves. |
| Dev environment | Fabric `FabricLoader.isDevelopmentEnvironment`; NeoForge (FML 12) `FMLLoader.getCurrent().isProduction()`. |
| NeoForge config button | `ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory::class.java) { IConfigScreenFactory { container, parent -> screen } }` from the `@Mod` object's constructor (same as autodrop). |
| Sodium Config API (0.9.2 Fabric, 0.9.3-beta.1 NeoForge) | `ConfigEntryPoint.registerConfigLate(ConfigBuilder)`; Fabric entrypoint `sodium:config_api_user`, NeoForge class annotation `@ConfigEntryPointForge("<modid>")` (the annotation is in both API jars). `builder.registerModOptions(modId).addPage(builder.createOptionPage().setName(…).addOptionGroup(…))`; options from `createBooleanOption / createIntegerOption / createEnumOption(Identifier, Class) / createExternalButtonOption(Identifier)` with `setName`, `setTooltip`, `setDefaultValue`, `setBinding(Consumer, Supplier)`, `setStorageHandler(StorageEventHandler)` (called once after Apply), `setRange(min, max, step)`, `setValueFormatter`, `setElementNameProvider`, `setScreenConsumer(Consumer<Screen>)`. |
| Overlapping mods | See `docs/compat.md`. |
| Driving the client in-process (self-check) | `KeyMapping.click(InputConstants.Key)` counts a press for every mapping on that key (the keyboard handler's path). `Screenshot.takeScreenshot(RenderTarget, Consumer<NativeImage>)` / `Screenshot.grab(dir, name, target, 1, callback)` read the last frame asynchronously (`gameRenderer.mainRenderTarget()`). `minecraft.createWorldOpenFlows().openWorld(levelId, onCancel)` opens a save; `disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)` returns to the title screen like "Save and Quit" (`disconnectWithSavingScreen` alone stays on "Saving world"). `ClientPacketListener.sendUnattendedCommand` runs client commands on both loaders (NeoForge patches it through `ClientCommandHandler`). Changing `options.cloudStatus()` switches `graphicsPreset` to custom, and `OptionsSubScreen.removed()` saves `options.txt`. |
| NeoForge mod list (26.3.0.94) | `net.neoforged.neoforge.client.gui.modlist.ModListScreen.create(lastScreen)`; its Config button's message is `neoforge.screen.mods.button.config`, active when `IConfigScreenFactory.getForMod` finds a factory. Startup warnings show `LoadingErrorScreen` ("Proceed" runs its `nextScreenTask`); `logoFile` in `neoforge.mods.toml` is deprecated (`iconFile` for square icons, `bannerFile`). |
| Sodium internals (both loaders) | `ConfigManager.CONFIG` (`client.config.structure.Config`): `getModOptions()` (records with `configId()`, `pages()`), `getOption(Identifier)`, `applyAllOptions()`; `StatefulOption.modifyValue(v)` is what a control does; `VideoSettingsScreen.createScreen(parent, OptionPage)` opens on a page. The NeoForge jar nests these classes, so Mobium reaches them by reflection (dev self-check only). |

## 15. Static cache findings (M5)

Looked up on 20 Oct 2026 for Module 1; the self-check compares every finding below against vanilla pixel by pixel.

| Topic | Finding |
| --- | --- |
| Signs | The board is part of the terrain mesh; `AbstractSignRenderer.submit` only submits the text, one `submitText(…, Font.DisplayMode.POLYGON_OFFSET, light, color, 0, outlineColor)` per line and side. Glowing text uses light `15728880`; glowing text gets the 8× outline (`Font.prepare8xTextOutline`, drawn in `DisplayMode.NORMAL` before the text) when its colour is black or `isOutlineVisible(pos)`: the camera entity is within 16 blocks of the sign's centre, or the first-person player is scoping. `HangingSignRenderer` is the same with other transformations. |
| Sign text phase | `SubmitNodeCollection.submitTextPart` puts text into the **solid** phase when `canRenderAsSolid`: content is `Content.Text`, `alpha(color) == 255`, `alpha(backgroundColor) == 0` and `RenderSystem.isRenderingLevel`. Sign text always qualifies (`DyeColor` stores `ARGB.opaque(textColor)`, `ARGB.scaleRGB` keeps alpha), so it is drawn in `executeSolid`, not with the translucent features. |
| Text pipelines | `RenderPipelines.TEXT` / `TEXT_POLYGON_OFFSET`: blend `TRANSLUCENT`, depth test `GREATER_THAN_OR_EQUAL` with depth write (polygon offset bias `1, 10` for the latter); `text.fsh` discards `alpha < 0.1`. Text render types do not `sortOnUpload`, so quad order is submission order. |
| Text input | `SignText` is immutable (every `with…` returns a new object) except `getRenderMessages`, which **caches the first caller's line splitting**; read styles through `getMessages(filtered)` and `Component.visit` instead. Obfuscated styles pick a random glyph on every `Font.getGlyph`; `ObjectContents` (sprites, player heads) switch the style's font to `FontDescription.AtlasSprite` / `PlayerSprite` (`ObjectContents.visit`). |
| Beds | No block entity any more: `BlockEntityTypes` has no `BED` and there is no `BedRenderer`; beds are terrain like sign boards. |
| Chests | `ChestRenderer` handles chests, trapped, copper and ender chests (`LidBlockEntity.getOpenNess`); a double chest is two block entities, each drawing its half (`DOUBLE_CHEST_LEFT/RIGHT`), with the lid openness and `BrightnessCombiner` light of both. Shulker boxes: `ShulkerBoxBlockEntity.getProgress(partialTicks)`. |
| **Fog uses the raw position** | `entity.vsh` and `text.vsh` compute `fog_spherical_distance(Position)` and `fog_cylindrical_distance(Position)` from the vertex attribute, not from `ModelViewMat * Position` (`block.vsh` adds `ModelOffset` first; the entity and text shaders ignore it). Vertices drawn in section-local space with the offset in the model-view matrix (the M3 prototype) therefore get the fog of a closer object. Overworld environmental fog runs from 0 to 1024 blocks (`EnvironmentAttributes.FOG_START_DISTANCE` default 0, `FOG_END_DISTANCE` 1024), so the error is everywhere; render-distance fog (`FogRenderer.setupFog`: last `clamp(renderDistance·16/10, 4, 64)` blocks) and rain (`-160` on the start) make it large. Module 1 therefore uploads camera-relative positions. |
| Capturing inside the level render | `LevelExtractor.extract` runs from `GameRenderer` **before** `LevelRenderer.render`, which sets `RenderSystem.isRenderingLevel` (and Iris's `ImmediateState.isRenderingLevel`) for its whole body. Captures run in Module 1's prepare step, after `prepareFrame` and before the frame graph executes; `CommandEncoder.writeToBuffer` refuses to run inside an open render pass, which is not the case there. |
| GPU writes | `FrontendCommandEncoder.writeToBuffer` needs `USAGE_COPY_DST`; on GL it is `bufferSubData` on the buffer's handle. |
| Removed block entities | `LevelChunk.removeBlockEntity` / `clearAllBlockEntities` call `BlockEntity.setRemoved` on the client too, but a removed block entity can still reach `tryExtractRenderState` for a while (seen with Sodium: a section's block entity list is replaced only with its mesh). |
| Lightmap flicker | `LightmapRenderStateExtractor` changes `blockLightFlicker` randomly on every client tick (`/tick freeze` does not stop it), so two pictures of block-lit surfaces from different ticks differ by a level or so; compare frames of the same tick. |
| Item states | `ItemStackRenderState.isAnimated` only means glint or animated textures (it exists for the GUI item atlas). Items whose model is chosen per frame (compass, clock: `RangeSelectItemModel` / `SelectItemModel`) are not flagged; `appendModelIdentityElement` is a no-op except in the GUI atlas's tracking subclass. `ItemFeatureRenderer` derives the `SPECIAL` glint's UVs from the submit's (camera-relative) pose (`computeFoilDecalPose`). |
| Key bindings | `KeyMapping` stores SDL **scancodes** (`InputConstants.KEY_W = 26`, `KEY_RBRACKET = 48`), i.e. physical key positions; `InputConstants.Key.getDisplayName` names them through the current layout (`SDL_GetKeyFromScancode` → `SDL_GetKeyName`). A default of `KEY_RBRACKET` is the key right of P: `]` on US, `+` on German keyboards, where `]` itself (AltGr+9) never matches. Show `KeyMapping.getTranslatedKeyMessage()` instead of naming the key in texts. |
| Entity submit | `EntityRenderDispatcher.submit` translates by the camera-relative position plus `getRenderOffset`, calls `renderer.submit`, then submits the flame and the shadow itself; name tags and leashes come from `EntityRenderer.submit`. Entities are always extracted (`LevelExtractor.extractVisibleEntities` adds every state). |
| Entity render states | `EntityRenderer.createRenderState(entity, partialTicks)` makes a **new** state object every frame and the state has no reference to its entity, so Module 1 adds one (`EntityRenderStateMixin`, set after `EntityRenderDispatcher.extractEntity`). Shadows go into `SubmitNodeCollection.shadows`, flames into the solid phase. Who keeps states past the frame: `LevelRenderer.submitFeatures` clears the level's list right after `submitEntities`; `PlayerRenderState.avatarRenderState` lives until the next extraction (`GameRenderer` resets it when no level is drawn); `ItemPickupParticle` keeps the picked-up item's state for its few ticks (`ClientPacketListener`); Iris's shadow renderer keeps its pass's states until the next pass. Since beta2 only the cached types' states outside the shadow pass get the reference. |
| Iris entity ids | Iris's `entity_render_context.MixinEntityRenderDispatcher` sets `CapturedRenderingState`'s current entity right after `pushPose` in `EntityRenderDispatcher.submit` and clears it before `popPose`; model, block-model and item submits copy the id when they are created (`iris$capture`) and restore it in `buildGroup`. Entity captures therefore submit through `EntityRenderDispatcher.submit` (at 0, 0, 0), not `renderer.submit`. |
| Item frames | `ItemFrameRenderer.getRenderOffset` is (`direction` × 0.3, −0.25, …), and `submit` translates it back first, so the picture is relative to the state's x/y/z. Frame: `BlockModelRenderState.submitWithZOffset` → `submitBlockModel` with `entitySolidZOffsetForward` (solid phase; `BlockModelFeatureRenderer.buildGroup` does not read the frame context). A map: `MapRenderer.render` → custom geometry with `RenderTypes.text(…)`, which blends (translucent phase). NeoForge posts `RenderItemInFrameEvent` around the item and map. Glow frames: block light at least 5 for the frame, full bright for the item. |
| Armor stands | `ArmorStandRenderState` declares `leftArmPose`/`rightArmPose` (`Rotations`), hiding `ArmedEntityRenderState`'s `ArmPose` fields of the same names. `setupRotations` wobbles while `wiggle` (game time since the last hit + partial tick) is below 5. `HumanoidModel.setupAnim` bobs the arms with `ageInTicks`, but `ArmorStandArmorModel.setupAnim` then sets every head, body, arm and leg rotation from the poses, so a still stand's picture does not depend on time. Armor is drawn from copies of the equipment stacks (`HumanoidMobRenderer.getEquipmentIfRenderable`); a skull on the head becomes `wornHeadType`/`wornHeadProfile`. |
| Display entities | `Display.calculateInterpolationProgress` returns exactly 1.0 once an interpolation has finished (or when there is none). `Display.renderState()` (transformation, billboard, brightness, shadow, glow colour) is replaced as a whole whenever one of them changes. `DisplayRenderer.calculateOrientation`: only `FIXED` ignores the camera. Block displays: `BlockModelResolver.update` refills the state every frame with the baked model's parts (same objects until a reload); special renderers (chests, heads) go through `submitSpecialRenderer`. |
| Submit orders | `SubmitNodeCollector.order(n)` gives each order its own `SubmitNodeCollection`; `FeatureRenderDispatcher` prepares and draws the orders one after another. `EquipmentLayerRenderer` puts each armor layer (dye, overlay, trim) into the next order so they draw over each other at equal depth; eyes, energy swirls and the breeze's wind use order 1. Module 1 only takes order 0, so armor and those layers stay vanilla. |
| Fabric renderer API (Fabric) | `fabric-renderer-api-v1`'s `BlockModelRenderStateMixin` fills a mesh (`setupMesh`) instead of `modelParts` for models that emit through its `QuadEmitter` (vanilla's own models included), and `submitModel` then submits an `ExtendedBlockModelSubmit` (`SubmitNodeCollectionMixin`; `SubmitNodeStorageMixin` forwards to order 0), drawn by the renderer implementation's `ExtendedBlockModelFeatureRenderer` (Sodium's in the dev client, Indigo's without Sodium), a `RenderTypeFeatureRenderer` whose `buildGroup` writes through `getVertexBuilder` and ignores the context. Items with a mesh become `ExtendedItemSubmit` the same way. The mesh is not visible in `BlockModelRenderState`'s vanilla fields, so a cached block model must be compared by its inputs (the block state). |

### Iris 1.11.7 with a shader pack

- `MixinRenderType` makes `RenderType.format()` return `IrisVertexFormats.ENTITY` / `GLYPH` / `TERRAIN` only while
  `ImmediateState.isRenderingLevel` and a pack is in use, so a capture outside `LevelRenderer.render` would write the
  vanilla format.
- `MixinBlockEntityRenderDispatcher` sets `ImmediateState.isRenderingBEs` and the block entity id
  (`WorldRenderingSettings.getBlockStateIds()`, only while rendering the level) around `submit`; while it is set,
  `MixinModelStorageTrigger` wraps model and custom geometry render types and `MixinGlyphRenderType` glyph render types
  in `OuterWrappedRenderType("iris:block_entity", …, BlockEntityRenderStateShard)`. A capture through
  `BlockEntityRenderDispatcher.submit` inside the level render gets the same wrapped render types and ids.

## 16. Module 2 findings (M6)

Read on 25 Oct 2026 for Module 2 (Vineflower 1.12 on the 26.3 client jar, Sodium `mc26.3-0.9.2-fabric`, Iris
`1.11.7+26.3-fabric`, EMF `3.3.12-fabric-26.3`). Module 2 has to write exactly the vertices the path it replaces would
have written, so this section names, per path, the order of the float operations as well.

| Topic | Finding |
| --- | --- |
| Who compiles a part | Three paths reach the GPU, depending on the part's class and the mods: (1) vanilla `ModelPart.compile` → `Cube.compile` per cube; (2) with Sodium, `CubeMixin` cancels `Cube.compile` before `pose.pose()` whenever the consumer converts to `VertexBufferWriter` (`BufferBuilder`, `SpriteCoordinateExpander` and `SheetedDecalTextureGenerator` over one) and writes the cube with `EntityRenderer.renderCuboid`; (3) EMF's parts. **EMF makes `ModelPart` and its private `compile` extendable** (access widener: `extendable class ModelPart`, `extendable method ModelPart compile`, `extendable class ModelPart$Cube`, `accessible field … cubes / children / polygons`), and `EMFModelPart.compile` overrides it (loops the cubes, catches `IllegalStateException`). Fresh Animations' parts are `EMFModelPartCustom` with `EMFModelPartCustom$EMFCube extends Cube`, whose `compile` override writes vanilla-style per vertex, so Sodium's path never sees them. `EMFModelPartVanilla` (EMF's wrapper of vanilla parts) keeps vanilla `Cube`s, which take Sodium's path. A hook on `ModelPart.compile` alone therefore misses every EMF part; `EMFModelPart.compile` needs its own (compat) hook. |
| Vanilla cube math | `Cube.compile`: per polygon `pose.transformNormal(polygon.normal, v)` (`Matrix3f.transform` then normalise when the pose's normals are not trusted), per vertex `pose.pose().transformPosition(x/16, y/16, z/16)` (JOML) and `addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz)`; `BufferBuilder.normalIntValue` packs `(int)(clamp(c, -1, 1) · 127) & 0xFF` (truncation). Polygon order DOWN, UP, WEST, NORTH, EAST, SOUTH (only `visibleFaces`), UVs baked into `Vertex.u/v`; a mirrored cube swaps min/max x, mirrors the X faces' normals and reverses each polygon's vertex order. EMF's `EMFCube.compile` is the same math. |
| Sodium cube math | `ModelCuboid` precomputes the corner origin `/16`, the sizes and the UVs (`scaleU · (u + …)` with `scaleU = 1/textureWidth`, so the UVs can differ from vanilla's `u/xTexSize` in the last bit). `prepareVertices`: corner 000 = `MatrixHelper.transformPosition*` (`m00·x + (m10·y + (m20·z + m30))`, no fused multiply-add), the three axis vectors `m0j · size`, the other seven corners by additions in a fixed chain (`c100 = c000 + vx`, `c110 = c100 + vy`, `c010 = c000 + vy`, `c001 = c000 + vz`, `c101 = c100 + vz`, `c111 = c110 + vz`, `c011 = c010 + vz`). Normals once per normal matrix for six directions (`MatrixHelper.transformNormal(normal, trustedNormals, dir)`: ± a column, normalised with `Math.invsqrt(fma(x, x, fma(y, y, z·z)))`, `NormI8.pack` truncating like vanilla). Face order DOWN, UP, EAST, NORTH, WEST, SOUTH (not vanilla's), one `VertexBufferWriter.push` of 4 × faces vertices per cube. |
| Sodium push | `BufferBuilderMixin.push(stack, src, count, format)`: `buffer.reserve(count · vertexSize)`, a copy (same format) or a `VertexSerializer` (other format), `vertices += count`, `vertexPointer` = last vertex, `elementsToFill = 0`; it does **not** call `endLastVertex`. `SpriteCoordinateExpanderMixin.push` rewrites the UVs with `UvMapping.getU/getV` first. A sprite's `getU(u)` is `u0 + (u1 − u0) · u`. |
| Iris on vertices | Two paths with a shader pack: vertices written one by one (vanilla and EMF cubes) go through `MixinBufferBuilder`: `endLastVertex` HEAD counts vertices and every fourth runs `fillExtendedData` — mid UV (`sum / 4`), **the normal replaced by the face normal** (`computeFaceNormalManual`: cross product of the diagonals `v2 − v0`, `v3 − v1`, JOML `normalize`), tangent from that normal (`computeTangent`). Pushed vertices (Sodium's cubes) go through Iris's `ModelToEntityVertexSerializer` (ENTITY → Iris ENTITY): the **normal stays**, the tangent comes from the unpacked normal (`NormI8.unpack* = byte · 0.007874016`), mid UV `(u0 + u1 + u2 + u3) · 0.25`, ids from `CapturedRenderingState` per push. Iris's `push` TAIL sets `skipEndVertexOnce`, so the next `endLastVertex` does not count the pushed vertex. `NormalHelper.rsqrt(x)` is `(float) (1.0 / Math.sqrt(x))` in double, `x == 0 → 1`. |
| Builders and draws | `StagedVertexBuffer.getVertexBuilder(draw)` returns the open `BufferBuilder` while the draw stays the same, otherwise finishes it (`build()` → `MeshData`, appended to its draw) and opens a new one over the shared `ByteBufferBuilder`. A draw's bytes are its builders' slices in order; `upload()` lays the draws out back to back (`vertexOffset` rounded to the vertex size), copies all slices into a mapped staging buffer and `copyToBuffer`s it into one pooled vertex buffer (usage `COPY_DST | VERTEX`). Only sorted draws (`quadSorting != null`: `sortOnUpload` render types without OIT) read the CPU vertices again (`decodeSortingPoints`). So a vertex's final place is `draw.vertexOffset + (bytes of the draw's earlier builders) + (its offset in its builder)`, known after `upload()`. |
| GL state | Renderpearl's GL backend does not cache the bound program (`GlStateManager._glUseProgram` calls through); `GlCommandEncoder` caches the last pipeline only within a render pass (reset in `createRenderPass`) and binds UBOs with `glBindBufferRange`. `copyToBuffer` is an immediate `glCopyBufferSubData`. Buffers are created with `glBufferStorage`/`glNamedBufferStorage` or `glBufferData`; GL lets any buffer object be bound as a shader storage buffer, so compute can write the pooled vertex buffer through its handle between `upload()` and the first draw, followed by `glMemoryBarrier(GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT)`. |
| Where EMF animates | Fresh Animations' animations are evaluated inside `ModelPart.render` (`EMFModelPartRoot.animate` from `EMFModelPartWithState.render`), not in `Model.setupAnim`, so they are part of `tessellate_entities` and stay on the CPU with Module 2. Their time is EMF's `frame_time` (`EMFMath.getFrameTime`), which is 0 only while the game is paused: with `/tick freeze` two vanilla frames still differ in about 600 pixels of `mob_pen_live`, behind a pause screen in none. |
| Bytes the CPU never writes | `BufferBuilder` writes three bytes of the entity format's normal (byte 35 is left as the memory was); Iris's `ModelToEntityVertexSerializer` (Sodium's pushed cubes) writes three of the four shorts of `iris_Entity` (bytes 42–43 stay), its per-vertex path writes 0 there. Module 2 writes 0 into all of them; nothing reads them (two vanilla frames show no noise from them). |
| GPU rounding | Mesa on the Intel Arc 140V does not round `sqrt(double)` and double division correctly: Iris's face normal of an axis-aligned face came out as 0.99999994 instead of 1.0 (packed 126 instead of 127) in 24k of 3.3M vertices. The expand pass therefore corrects its square roots and divisions to the neighbouring float with exact double residuals (`expand_parts.comp`); double multiplication and addition are correctly rounded. |
| Iris's per-vertex counter | Iris counts each vertex written one by one at the next `endLastVertex`, except right after a Sodium push (`skipEndVertexOnce`). Module 2 ends the vertex in progress (`endLastVertex`), reserves, and then pushes zero vertices through Sodium's `VertexBufferWriter`, which leaves Iris's state as after Sodium's own cube push. A Sodium push right after vertices written one by one loses the last of them in Iris's count (an Iris quirk); with Module 2 taking both kinds of cube there are no vertices written one by one left to mix in. |
| Upload of reserved bytes | `StagedVertexBuffer.uploadDrawsToBuffers` copies every builder slice with `ByteBuffer.put(slice.byteBuffer())` into the mapped staging buffer; Module 2 wraps that call and leaves out its reserved ranges (8 % of the render thread's samples in setup D went to that copy). The GPU copy from staging to the vertex buffer still copies the whole range; the expand pass overwrites the reserved part after it. |
| Compute on Mesa's iris | Measured on 27 Oct 2026 (Mesa 26.2.5, Arc 140V, `INTEL_DEBUG=submit` in the dev client): iris records `glDispatchCompute` in a compute batch of its own, apart from the render batch that holds the draws and vanilla's `copyToBuffer` from the staging buffer. The expand pass writes the vertex buffer the render batch just copied into, and the render batch's draws read it, so every pass flushes both batches in the middle of the frame (`iris_batch.c:361`, a cross-batch dependency) and the GPU waits for each: three submissions per frame instead of the frame's own one (`iris_fence.c:247`), 0.22 ms more GPU time per frame (timestamps from frame start to submit) in `display_build` with 17 shields as in `mob_pen_live` with 500 mobs. The same shader compiled as a vertex shader and drawn as `GL_POINTS` with `GL_RASTERIZER_DISCARD`, writing the storage buffers from the vertex stage (`GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS` is 16 there; OpenGL allows 0), stays in the render batch: one submission per frame, as with Module 2 off. iris runs Gallium's threaded context (a driver thread `gdrv0`), not glthread, so `glGetInteger` does not wait for the driver thread. |
| Sodium on NeoForge | Sodium 0.9.3-beta.1 for NeoForge is a jar in a jar (`META-INF/jarjar/…-mod.jar`), so only its API is on Mobium's compile classpath; its cube path (`CubeMixin`, `EntityRenderer`, `ModelCuboid`, `MatrixHelper`, `BufferBuilderMixin`, `SpriteCoordinateExpanderMixin`, `NormI8`) is line for line the same as 0.9.2's for Fabric. Module 2 reads `ModelCuboid` by reflection (`parts/SodiumCubes.kt`), finding Sodium's field on `ModelPart.Cube` by its type. Sodium caches the six packed normals per normal matrix (`prevNormalMatrix`) without the trusted-normals flag; two consecutive poses with the same normal matrix but different flags would reuse normals computed with the other flag. Module 2 computes them per part and so differs from Sodium only in that case. |

## 17. Built-in data packs and world checks (`/mobium benchmark`)

Read on 28 Oct 2026 for the players' benchmark (Vineflower 1.12 on Loom's 26.3 `minecraft-merged.jar`, Fabric API
`0.164.0+26.3` with `fabric-resource-loader-v1` `3.1.0+5af7744b5d`, NeoForge `26.3.0.94` and MDG's
`minecraft-patched-26.3.0.94-sources.jar`). The expectation in the task, Fabric's `ResourceManagerHelper` and
NeoForge's `AddPackFindersEvent`, was half right: Fabric API for 26.3 has no `fabric-resource-loader-v0` any more.

| Topic | Finding |
| --- | --- |
| New packs in a world | `MinecraftServer.configurePackRepository(repository, config, initMode, safeMode)`: the world's enabled packs, then every available pack that is in neither list and whose `PackSource.shouldAddAutomatically()` is true ("Found new data pack …, loading it automatically"), in new worlds and in existing ones alike. `PackSource.BUILT_IN`, `DEFAULT`, `WORLD` and `SERVER` add themselves, `FEATURE` does not; `PackSource.create(UnaryOperator<Component> decorator, boolean addAutomatically)` makes others. Create New World starts from `WorldDataConfiguration.DEFAULT` (only `vanilla`) and `tryApplyNewDataPacks` writes the selection as `DataPackConfig(enabled, disabled = every other available id)`. |
| Fabric built-in packs | `net.fabricmc.fabric.api.resource.v1.ResourceLoader.registerBuiltinPack(Identifier id, ModContainer, Component displayName, PackActivationType)` serves `resourcepacks/<id path>` of the mod's root paths as a resource pack and/or a data pack (whichever of `assets/`, `data/` exists), pack id `id.toString()`, source `BuiltinModPackSource` (`shouldAddAutomatically` true, shown as "built-in: <mod name>"). `PackActivationType.NORMAL` keeps it off anyway: `ModPackResourcesUtil.createDefaultDataConfiguration()` (Create New World's start) puts it into `disabled`, and `MinecraftServerMixin` redirects the `getDisabled().contains(id)` check of `configurePackRepository` to report it as disabled unless the activation type is `DEFAULT_ENABLED`/`ALWAYS_ENABLED`. `CreateWorldScreenMixin` adds `ModResourcePackCreator(SERVER_DATA)` to the screen's repositories, so the pack is listed under More → Data Packs. Namespaces must match `[a-z0-9-_.]+`. |
| NeoForge built-in packs | `AddPackFindersEvent` (mod bus) is posted by `ResourcePackLoader.populatePackRepository` for `ServerPacksSource.createPackRepository` (every world), `createVanillaTrustedRepository`, and Create New World's two repositories (patched into `CreateWorldScreen.openCreateWorldScreen` and `getDataPackSelectionSettings`). `addPackFinders(Identifier packLocation, PackType, Component name, PackSource, boolean alwaysActive, Pack.Position)` reads `packLocation.path` inside the jar of the mod named by its namespace, pack id `"mod/" + packLocation`, `PackSelectionConfig(alwaysActive, position, false)`. Whether a world picks it up by itself is the `PackSource`'s `shouldAddAutomatically()` (above), so Mobium passes its own source with `false`. |
| Pack descriptions | `pack.mcmeta`'s `description` is a text component: `{"translate": …, "fallback": …}` shows in the client's language. |
| Superflat | `FlatLevelSource.settings()` → `FlatLevelGeneratorSettings.getLayers()`: one block state per layer height (`updateLayers`); the default (Classic) preset is bedrock 1, dirt 2, grass block 1, so the first air block is `minY + 4` = −60, the scenes' `GROUND`. `WorldData.isFlatWorld()` is only the `SpecialWorldProperty`. The generator is `server.overworld().chunkSource.generator`. |
| Seeds | `WorldOptions.parseSeed`: a number as is, any other text as `String.hashCode()` (so "mobium" is `"mobium".hashCode().toLong()`); `ServerLevel.getSeed()`. |
| Graphics preset | `Options.renderDistance`, `simulationDistance`, `entityDistanceScaling`, `entityShadows` and other preset options call `setGraphicsPresetToCustom()` on change unless `isApplyingGraphicsPreset` is set; `GraphicsPreset.apply` (on setting the preset) sets all of them, FANCY with render distance 16, although a fresh `options.txt` has FANCY with render distance 12. Setting the old preset back after a change can therefore change the render distance. `OptionInstance.set` calls its listener only when the value changes and the game is running. `fov` (`IntRange(30, 110)`) and `framerateLimit` / `enableVsync` do not touch the preset. |
| Integrated server distances | `IntegratedServer.tickServer` copies `options.renderDistance` and `simulationDistance` to the player list every tick. |
| Chat links | `ClickEvent.OpenFile(Path)` and `ClickEvent.OpenUrl(URI)`; `Screen.defaultHandleClickEvent` opens files and folders with `Blaze3D.openPath`, URLs through `ConfirmLinkScreen`. Vanilla's screenshot message uses `OpenFile` on a client system message. |
| CPU name | `DebugEntrySystemSpecs.getCpuInfo()` (public, cached): OSHI's `"<logical cores>x <processor name>"`, as F3 shows it. |
| Saving options | `Options.save()` runs when an options screen closes and on F11 (`Minecraft.toggleFullscreen`), not when the game quits. |

---

## 18. Vulkan backend (26.3)

Read on 28 Oct 2026 for M7 (Vineflower 1.12 on Loom's 26.3 `minecraft-client.jar`: `com.mojang.renderpearl.backend.vulkan`,
`frontend`, `api`, and `StagedVertexBuffer`). 26.4's backend is not read yet (`PLAN.md`, M7 step 1). Vulkan is an
option in 26.3; `26.4-snapshot-1` (22 Sep 2026) makes "Default" behave like "Prefer Vulkan" and no longer switches the
graphics API by itself after a startup crash (snapshot changelog, not read in code).

| Topic | Finding |
| --- | --- |
| Reaching the backend | `RenderSystem.getDevice()` is a `FrontendGpuDevice` around a private `GpuDeviceBackend backend`; on Vulkan that is a `VulkanDevice` with public `vkDevice()`, `vma()` (the VMA allocator), `instance()`, `graphicsQueue()`, `computeQueue()` and `transferQueue()` (own queue families where the GPU has them, else the graphics queue), and `createCommandEncoder()`, which returns its one `VulkanCommandEncoder` (the frontend wraps it in `FrontendCommandEncoder`). `VulkanBackend` requires a queue family that does graphics, compute and present together, so compute work can always go on the graphics queue. |
| Buffers | `VulkanGpuBuffer.Direct`, allocated through VMA, with public `vkBuffer()`. `VulkanConst.bufferUsageToVk` maps Renderpearl's usages one to one: `COPY_DST` → `TRANSFER_DST`, `COPY_SRC` → `TRANSFER_SRC`, `VERTEX` → `VERTEX_BUFFER`, `INDEX` → `INDEX_BUFFER`, `UNIFORM` → `UNIFORM_BUFFER`, `UNIFORM_TEXEL_BUFFER` → `UNIFORM_TEXEL_BUFFER`, `INDIRECT_PARAMETERS` → `INDIRECT_BUFFER`. **No buffer is ever a storage buffer** (`VK_BUFFER_USAGE_STORAGE_BUFFER_BIT`), so binding one to a shader's storage block is invalid. `MAP_READ` / `MAP_WRITE` ask for host-visible, coherent memory. The transient memory blocks (`VulkanTransientMemory`, behind `CommandEncoder.transientMemory()`) are created with a fixed usage (471: transfer source and target, uniform texel, uniform, index, vertex, indirect), no storage either. |
| Staged vertex buffer | `StagedVertexBuffer`'s three pools: staging usage 22 (`MAP_WRITE`, `HINT_CLIENT_STORAGE`, `COPY_SRC`), vertex 40 (`COPY_DST`, `VERTEX`), index 72 (`COPY_DST`, `INDEX`). `upload()` maps a staging buffer and `copyToBuffer`s it into a pooled vertex buffer, as on OpenGL (§16). |
| Recording and submission | One `VulkanCommandEncoder`. Commands go into a current command buffer, begun on demand (`commandBuffer()` throws while a render pass is open), object-initialisation commands into a second one. `submit()` ends both, adds them to one `VulkanQueue.Submission` on the graphics queue, signals a timeline semaphore, waits for the submit two frames back (`MAX_SUBMITS_IN_FLIGHT = 2`, 5 s timeout) and resets that frame's command pool. Public for other code: `allocateAndBeginTransientCommandBuffer()` (a one-time-submit buffer from the current frame's pool), `execute(VkCommandBuffer)` (ends the current command buffer and appends the given one to the same submission, so it runs in order; throws inside a render pass), `waitSemaphore` / `signalSemaphore`, and the static `memoryBarrier(VkCommandBuffer, MemoryStack)`. |
| Ordering | `writeToBuffer` (transient staging plus `vkCmdCopyBuffer`) and `copyToBuffer` (`vkCmdCopyBuffer`) record into the frame's command buffer, each followed by a full barrier (`vkCmdPipelineBarrier2KHR`, all commands to all commands, memory read and write). Anything executed after `StagedVertexBuffer.upload()` therefore sees the uploaded vertices. |
| Device features | Required (`VulkanFeatureSets.REQUIRED_FEATURESET`): `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`, `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain`; `multiDrawIndirect`, `drawIndirectFirstInstance`, `samplerAnisotropy`, `shaderDrawParameters`, `timelineSemaphore`, `hostQueryReset`, `synchronization2`, `dynamicRendering`, `vertexAttributeInstanceRateDivisor`. Optional where supported (`optionalFeatureSets()`): calibrated timestamps, `fillModeNonSolid`, NV diagnostic checkpoints or the AMD buffer marker, `VK_EXT_multi_draw`; the portability subset where present. **Not enabled: `shaderFloat64` and `vertexPipelineStoresAndAtomics`.** A feature has to be enabled when the device is created; the sets come from the static `requiredFeatureSets()` and `optionalFeatureSets()`. |
| Shaders and pipelines | GLSL is compiled to SPIR-V with LWJGL's shaderc (`frontend.shaders.GlslCompiler`, `SpvModule` with SPIR-V reflection). `VulkanRenderPipeline` builds graphics pipelines whose descriptors are uniform buffers, combined image samplers and texel buffers; push descriptors (required extension) bind them. No compute pipelines. |
| Timestamps | `GpuDevice.createTimestampQueryPool(int)` and `CommandEncoder.writeTimestamp(GpuQueryPool, int)`, on both backends. |

What it means for Mobium: the static cache uses only Renderpearl's API and should work unchanged. Module 2's expand
pass can run as a command buffer of its own, recorded with `allocateAndBeginTransientCommandBuffer` and handed to
`execute` right after `upload()`, but needs storage usage on vanilla's vertex buffers and its own, an optional device
feature set for `shaderFloat64`, and the shader in Vulkan's GLSL dialect (`PLAN.md`, M7).

## 19. GPU driver string (`/mobium benchmark`)

Read on 29 Oct 2026 (Vineflower 1.12 on Loom's 26.3 `minecraft-client.jar`: `com.mojang.renderpearl.api.device.DeviceInfo`,
`backend.opengl.GlHeuristics`, `backend.vulkan.VulkanDevice` and `VulkanPhysicalDevice`,
`net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs`), so the benchmark file can name the driver.

| Topic | Finding |
| --- | --- |
| Where it is | `RenderSystem.getDevice().getDeviceInfo().driverInfo()` (record component of `DeviceInfo`, §5); Mobium keeps it as `Capabilities.driverInfo`. There is no separate driver version field. |
| OpenGL | `GlHeuristics.createDeviceInfo` passes `GlStateManager._getString(7938)`, i.e. `glGetString(GL_VERSION)`. What follows the context version is the driver's own text (not in the source; drivers put their version there, e.g. Mesa `4.6 (Core Profile) Mesa <version>`, Nvidia `4.6.0 NVIDIA <version>`). `name()` is `GL_RENDERER` (7937), `vendorName()` `GL_VENDOR` (7936). |
| Vulkan | `VulkanDevice` passes `VulkanPhysicalDevice.driverInfo()`: `String.format(Locale.ROOT, "%s %s %s")` of the device's `apiVersion` (decoded as `major.minor.patch`), `VkPhysicalDeviceDriverProperties.driverNameString()` and `.driverInfoString()`. |
| Line breaks | F3's system entry shows `backendName() + " " + firstLine(driverInfo())` (`DebugEntrySystemSpecs.display`), so the string may span lines; the CPU line there (`getCpuInfo`) collapses whitespace with `replaceAll("\\s+", " ")`. Mobium does the same with the driver string before it writes it to a CSV line (`BenchEnvironment.snapshot`). |
