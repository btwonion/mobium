package dev.nyon.mobium.cache

import dev.nyon.mobium.config.CacheTypes
import dev.nyon.mobium.mixins.AbstractSignEditScreenAccessor
import dev.nyon.mobium.mixins.AbstractSignRendererInvoker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.blockentity.state.ShelfRenderState
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.entity.BlockEntityTypes
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity
import net.minecraft.world.level.block.entity.LidBlockEntity
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import java.util.IdentityHashMap
import java.util.Optional

/**
 * One row of Module 1's policy table (PLAN.md M5), for block entities ([CacheRule]) or entities ([EntityRule]).
 *
 * @param key names the per-type switch (`mobium.settings.type.<key>`) and the type in failure reports.
 */
abstract class TypeRule(@JvmField val key: String) {
    /** Set by [StaticCache.refreshTypes] from the per-type switch and the type's failures. */
    @JvmField
    var allowed = false

    /** The per-type switch in [CacheTypes]. */
    abstract fun enabled(types: CacheTypes): Boolean
}

/**
 * A block entity row of the policy table: which vanilla block entity types it covers, while which states they are drawn
 * from the cache, and which inputs besides block state and light their picture depends on. Rules are asked per object
 * per frame, so they must not allocate. Anything a rule does not accept takes the vanilla path for that frame.
 */
abstract class CacheRule(key: String) : TypeRule(key) {
    /** False while the object animates or is being edited; vanilla draws it this frame. */
    abstract fun isStatic(blockEntity: BlockEntity, level: Level, state: BlockState, partialTicks: Float): Boolean

    /** [isStatic] for an object with an entry whose block state is unchanged; may use what the entry knows. */
    open fun isStaticCached(entry: CacheEntry, blockEntity: BlockEntity, partialTicks: Float): Boolean {
        val level = blockEntity.level ?: return false
        return isStatic(blockEntity, level, entry.blockState, partialTicks)
    }

    /** Inputs of the picture besides block state and light, compared every frame with what [storeInputs] kept. */
    open fun sameInputs(entry: CacheEntry, blockEntity: BlockEntity): Boolean = true

    open fun storeInputs(entry: CacheEntry, blockEntity: BlockEntity) {}

    /**
     * Whether the captured picture may be cached at all: checked once per capture, after the inputs are stored. A
     * rejected object takes the vanilla path until its inputs change.
     */
    open fun canCache(entry: CacheEntry, blockEntity: BlockEntity): Boolean = true

    /**
     * True when the picture can only be told from the state vanilla extracts (items, whose model is resolved again
     * every frame): vanilla's extraction then runs every frame and only submit and tessellation are skipped while
     * [sameState] holds.
     */
    open val checksState: Boolean
        get() = false

    /** Keeps what [state] draws, when the object is first seen; false when it cannot be cached. */
    open fun storeState(entry: CacheEntry, state: BlockEntityRenderState): Boolean = true

    /** Compares this frame's extracted state with what [storeState] kept. */
    open fun sameState(entry: CacheEntry, state: BlockEntityRenderState): Boolean = true

    /** Points [out] at a second block whose light and state belong to this object's picture (double chests). */
    open fun neighbour(pos: BlockPos, state: BlockState, out: BlockPos.MutableBlockPos): Boolean = false
}

/** The per-type rules by vanilla block entity type; modded block entities, even subclasses, stay on vanilla. */
object CachePolicy {
    private val rules = IdentityHashMap<BlockEntityType<*>, CacheRule>()

    init {
        rules[BlockEntityTypes.CHEST] = Lids
        rules[BlockEntityTypes.TRAPPED_CHEST] = Lids
        rules[BlockEntityTypes.ENDER_CHEST] = Lids
        rules[BlockEntityTypes.SHULKER_BOX] = Lids
        rules[BlockEntityTypes.SIGN] = Signs
        rules[BlockEntityTypes.HANGING_SIGN] = Signs
        rules[BlockEntityTypes.SKULL] = Skulls
        rules[BlockEntityTypes.DECORATED_POT] = Pots
        rules[BlockEntityTypes.SHELF] = Shelves
    }

    /** All rules once each, block entities and entities, for the per-type switches and the F3 counts. */
    val all: List<TypeRule> = listOf(Lids, Signs, Skulls, Pots, Shelves, ItemFrames, ArmorStands, Displays)

    @JvmStatic
    fun ruleFor(blockEntity: BlockEntity): CacheRule? = rules[blockEntity.type]
}

/**
 * Chests (also trapped, copper and ender chests) and shulker boxes: cached while closed, vanilla while the lid opens,
 * is open or closes. A double chest is two block entities, each drawing its own half; the lid and the light of the
 * other half count too (`ChestRenderer.extractRenderState` combines both).
 */
object Lids : CacheRule("chests") {
    override fun enabled(types: CacheTypes) = types.chests

    override fun isStatic(blockEntity: BlockEntity, level: Level, state: BlockState, partialTicks: Float): Boolean {
        if (blockEntity is ShulkerBoxBlockEntity) return blockEntity.getProgress(partialTicks) == 0f
        if (blockEntity !is LidBlockEntity || blockEntity.getOpenNess(partialTicks) != 0f) return false
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return true
        val other = level.getBlockEntity(neighbourOf(blockEntity.blockPos, state)) as? LidBlockEntity ?: return true
        return other.getOpenNess(partialTicks) == 0f
    }

    override fun isStaticCached(entry: CacheEntry, blockEntity: BlockEntity, partialTicks: Float): Boolean {
        if (blockEntity is ShulkerBoxBlockEntity) return blockEntity.getProgress(partialTicks) == 0f
        if (blockEntity !is LidBlockEntity || blockEntity.getOpenNess(partialTicks) != 0f) return false
        // A missing other half (its chunk was not loaded yet) or a replaced one (its chunk reloaded) goes through the
        // full check, which looks it up again.
        val other = entry.neighbourEntity ?: return !entry.hasNeighbour
        return !other.isRemoved && (other as? LidBlockEntity)?.getOpenNess(partialTicks) == 0f
    }

    override fun neighbour(pos: BlockPos, state: BlockState, out: BlockPos.MutableBlockPos): Boolean {
        if (!state.hasProperty(ChestBlock.TYPE) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return false
        out.setWithOffset(pos, ChestBlock.getConnectedDirection(state))
        return true
    }

    private val scratch = BlockPos.MutableBlockPos()

    private fun neighbourOf(pos: BlockPos, state: BlockState): BlockPos =
        scratch.setWithOffset(pos, ChestBlock.getConnectedDirection(state))
}

/**
 * Signs and hanging signs. In 26.3 the board is part of the terrain mesh and the block entity only draws the text
 * (`AbstractSignRenderer.submit`), which vanilla puts into the solid phase: sign text is opaque and has no background
 * (`SubmitNodeCollection.canRenderAsSolid`). The picture depends on both sides' [SignText] (immutable, replaced on
 * every change), the text filter and, for glowing text, whether the camera is close enough for the outline
 * (`AbstractSignRenderer.isOutlineVisible`). Vanilla while the sign is being edited; obfuscated text, sprites and
 * player heads always stay vanilla.
 */
object Signs : CacheRule("signs") {
    private const val FILTERED = 1
    private const val OUTLINE = 2

    override fun enabled(types: CacheTypes) = types.signs

    override fun isStatic(blockEntity: BlockEntity, level: Level, state: BlockState, partialTicks: Float): Boolean {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractSignEditScreen ?: return true
        return (screen as AbstractSignEditScreenAccessor).`mobium$sign`() !== blockEntity
    }

    override fun sameInputs(entry: CacheEntry, blockEntity: BlockEntity): Boolean {
        val sign = blockEntity as SignBlockEntity
        val front = sign.getText(SignTextSlot.FRONT)
        val back = sign.getText(SignTextSlot.BACK)
        return entry.inputA === front && entry.inputB === back && entry.inputFlags == flags(sign, front, back)
    }

    override fun storeInputs(entry: CacheEntry, blockEntity: BlockEntity) {
        val sign = blockEntity as SignBlockEntity
        val front = sign.getText(SignTextSlot.FRONT)
        val back = sign.getText(SignTextSlot.BACK)
        entry.inputA = front
        entry.inputB = back
        entry.inputFlags = flags(sign, front, back)
    }

    override fun canCache(entry: CacheEntry, blockEntity: BlockEntity): Boolean {
        val filtered = entry.inputFlags and FILTERED != 0
        return isPlain(entry.inputA as SignText, filtered) && isPlain(entry.inputB as SignText, filtered)
    }

    private fun flags(sign: SignBlockEntity, front: SignText, back: SignText): Int {
        var flags = if (StaticCache.textFiltering) FILTERED else 0
        // The outline of glowing text depends on the camera; black glowing text always has one.
        if (needsOutlineCheck(front) || needsOutlineCheck(back)) {
            if (AbstractSignRendererInvoker.`mobium$isOutlineVisible`(sign.blockPos)) flags = flags or OUTLINE
        }
        return flags
    }

    private fun needsOutlineCheck(text: SignText) =
        text.hasGlowingText() && text.color.textColor != DyeColor.BLACK.textColor

    /**
     * No obfuscated glyphs (random every frame) and no sprite or player-head glyphs (`ObjectContents` switch the
     * style's font; skins load later). Visits the components instead of `getRenderMessages`, which caches the first
     * caller's line splitting.
     */
    private fun isPlain(text: SignText, filtered: Boolean): Boolean {
        for (message in text.getMessages(filtered)) {
            if (message.visit(PlainCheck, Style.EMPTY).isPresent) return false
        }
        return true
    }

    private object PlainCheck : FormattedText.StyledContentConsumer<Unit> {
        override fun accept(style: Style, contents: String): Optional<Unit> =
            if (style.isObfuscated || style.font !is FontDescription.Resource) Optional.of(Unit) else Optional.empty()
    }
}

/**
 * Skulls and heads. The mouth of a powered dragon or piglin head moves every tick (`SkullBlockEntity.animation`), so
 * those take the vanilla path while they animate (`getAnimation` then depends on the partial tick), and the mouth
 * position they stopped at is an input. Player heads with an owner stay vanilla: their skin loads later and changes
 * the render type (`PlayerSkinRenderCache.getOrDefault`).
 */
object Skulls : CacheRule("skulls") {
    override fun enabled(types: CacheTypes) = types.skulls

    override fun isStatic(blockEntity: BlockEntity, level: Level, state: BlockState, partialTicks: Float): Boolean {
        val skull = blockEntity as SkullBlockEntity
        return skull.getAnimation(0f) == skull.getAnimation(1f)
    }

    override fun sameInputs(entry: CacheEntry, blockEntity: BlockEntity): Boolean {
        val skull = blockEntity as SkullBlockEntity
        return entry.inputA === skull.ownerProfile && entry.inputFlags == skull.getAnimation(0f).toRawBits()
    }

    override fun storeInputs(entry: CacheEntry, blockEntity: BlockEntity) {
        val skull = blockEntity as SkullBlockEntity
        entry.inputA = skull.ownerProfile
        entry.inputFlags = skull.getAnimation(0f).toRawBits()
    }

    override fun canCache(entry: CacheEntry, blockEntity: BlockEntity): Boolean = entry.inputA == null
}

/**
 * Decorated pots: vanilla while they wobble after being hit (`DecoratedPotRenderer.extractRenderState`: progress
 * from `wobbleStartedAtTick` over the style's duration). The sherds ([DecoratedPotBlockEntity.getDecorations], replaced
 * when the block entity's data is loaded) are an input.
 */
object Pots : CacheRule("decorated_pots") {
    override fun enabled(types: CacheTypes) = types.decoratedPots

    override fun isStatic(blockEntity: BlockEntity, level: Level, state: BlockState, partialTicks: Float): Boolean {
        val pot = blockEntity as DecoratedPotBlockEntity
        val style = pot.lastWobbleStyle ?: return true
        val progress = ((level.gameTime - pot.wobbleStartedAtTick).toFloat() + partialTicks) / style.duration
        return progress < 0f || progress > 1f
    }

    override fun sameInputs(entry: CacheEntry, blockEntity: BlockEntity): Boolean =
        entry.inputA === (blockEntity as DecoratedPotBlockEntity).decorations

    override fun storeInputs(entry: CacheEntry, blockEntity: BlockEntity) {
        entry.inputA = (blockEntity as DecoratedPotBlockEntity).decorations
    }
}

/**
 * Shelves and the items on them. The items are resolved again every frame (`ShelfRenderer.extractRenderState`), and an
 * item may pick another model each time (a compass), so vanilla extracts every frame and the shelf is drawn from the
 * cache while every item still draws the same ([ItemFingerprint]); a change is captured again. Items with translucent
 * quads (glass) or a special renderer (heads, banners) keep the shelf on vanilla.
 */
object Shelves : CacheRule("shelves") {
    override fun enabled(types: CacheTypes) = types.shelves

    override fun isStatic(blockEntity: BlockEntity, level: Level, state: BlockState, partialTicks: Float) = true

    override val checksState: Boolean
        get() = true

    override fun storeState(entry: CacheEntry, state: BlockEntityRenderState): Boolean {
        val shelf = state as ShelfRenderState
        val prints = arrayOfNulls<ItemFingerprint>(shelf.items.size)
        for (i in shelf.items.indices) {
            val item = shelf.items[i] ?: continue
            val print = ItemFingerprint()
            if (!print.store(item)) return false
            prints[i] = print
        }
        entry.inputA = prints
        entry.inputFlags = if (shelf.alignToBottom) 1 else 0
        return true
    }

    override fun sameState(entry: CacheEntry, state: BlockEntityRenderState): Boolean {
        val shelf = state as ShelfRenderState
        @Suppress("UNCHECKED_CAST")
        val prints = entry.inputA as Array<ItemFingerprint?>
        if ((entry.inputFlags == 1) != shelf.alignToBottom || prints.size != shelf.items.size) return false
        for (i in shelf.items.indices) {
            val item = shelf.items[i]
            val print = prints[i]
            if (item == null || print == null) {
                if (item != null || print != null) return false
            } else if (!print.matches(item)) {
                return false
            }
        }
        return true
    }
}
