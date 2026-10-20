# Vanilla notes (Minecraft 26.3)

How Minecraft 26.3 draws entities and block entities, where Sodium, Iris, EMF and ETF change that, and where Mobium
hooks in. Written in M1 from the decompiled sources; every claim below names the class and method it comes from.

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
| Compute | **None in the API.** No compute pipeline, no dispatch, no storage buffers. `VulkanDevice` creates an internal `computeQueue` (falls back to the graphics queue) but does not expose compute. |
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
| Part records | `ModelPart.compile(PoseStack.Pose, VertexConsumer, int, int, int)` (private) inside `ModelFeatureRenderer.prepareModel` | `@WrapMethod` (emit a record instead of vertices when the consumer is the frame's staged builder for a known format; else original) | 2 |
| Expand pass | after `StagedVertexBuffer.upload()` in `FeatureRenderDispatcher.prepareFrameWithContext` | `@Inject` after the `upload` call; dispatch compute into the uploaded vertex buffer | 2 |
| Animation LOD | `ModelFeatureRenderer.prepareModel` around `Model.setupAnim`; `LivingEntityRenderer.submit` around its layer `setupAnim` | `@WrapOperation` | 3 |
| Debug timers | `LevelExtractor.extract`, `LevelRenderer.submitFeatures`, `FeatureRenderDispatcher.prepareFrame`, `ModelFeatureRenderer.prepareModel`, main frame pass | `@Inject` HEAD/RETURN | M2 instrumentation |

Open points for later milestones (not answered by reading code):

- Module 2 cannot leave the vertex bytes out of the CPU staging buffer without changing `StagedVertexBuffer.upload`:
  draw ranges are laid out from the bytes written. Either reserve the bytes (cheap `ByteBufferBuilder.reserve`, still
  copied) or patch the layout so compute writes the reserved ranges directly in the uploaded buffer (M6).
- Iris writes three entity ids per vertex; the part record (§5.3 of the plan) needs them, either per record or per
  submit (M6).
- `RenderType.prepare()` writes `DynamicTransforms` from the current model-view matrix. Calling it from the execute
  stage for section buckets needs a check that dynamic uniform writes are allowed there on Vulkan (M3/M5).
