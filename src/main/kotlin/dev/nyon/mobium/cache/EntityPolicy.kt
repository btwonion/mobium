package dev.nyon.mobium.cache

import dev.nyon.mobium.config.CacheTypes
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState
import net.minecraft.client.renderer.entity.state.ArmorStandRenderState
import net.minecraft.client.renderer.entity.state.BlockDisplayEntityRenderState
import net.minecraft.client.renderer.entity.state.DisplayEntityRenderState
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState
import net.minecraft.core.Direction
import net.minecraft.core.Rotations
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.HumanoidArm
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import java.util.IdentityHashMap

/**
 * An entity row of Module 1's policy table (docs/architecture.md). Entities are extracted by vanilla every frame
 * (render states have no "unchanged" signal), so a rule compares the freshly extracted state with what it kept when the
 * entity was captured; while everything matches, the entity is drawn from the cache and vanilla's submit and
 * tessellation are skipped. Position (render state x/y/z, compared exactly) and light are compared by [StaticCache] for
 * every rule. Rules are asked per entity per frame, so [isStatic] and [same] must not allocate.
 */
abstract class EntityRule(key: String) : TypeRule(key) {
    /**
     * False while the entity's picture changes on its own (a hurt wobble, an interpolating display, a map): vanilla
     * draws it this frame and its entry is kept for when it is still again.
     */
    abstract fun isStatic(state: EntityRenderState): Boolean

    /** Keeps what [state] draws besides position and light; false when the cache cannot take it. Once per capture. */
    abstract fun store(entry: EntityEntry, state: EntityRenderState): Boolean

    /** Compares this frame's state with what [store] kept. */
    abstract fun same(entry: EntityEntry, state: EntityRenderState): Boolean
}

/** The entity rules by vanilla entity type; modded entities, even subclasses, stay on vanilla. */
object EntityPolicy {
    private val rules = IdentityHashMap<EntityType<*>, EntityRule>()

    init {
        rules[EntityTypes.ITEM_FRAME] = ItemFrames
        rules[EntityTypes.GLOW_ITEM_FRAME] = ItemFrames
        rules[EntityTypes.ARMOR_STAND] = ArmorStands
        rules[EntityTypes.BLOCK_DISPLAY] = Displays
        rules[EntityTypes.ITEM_DISPLAY] = Displays
    }

    @JvmStatic
    fun ruleFor(entity: Entity): EntityRule? = rules[entity.type]

    /**
     * What every rule leaves to vanilla: outlines (glowing; drawn in the outline pass), name tags (text facing the
     * camera), leashes and riding a minecart (an offset interpolated every frame).
     */
    @JvmStatic
    fun isPlain(state: EntityRenderState): Boolean = state.outlineColor == 0 && state.nameTag == null &&
        state.leashStates == null && state.passengerOffset == null
}

/**
 * Item frames and glow item frames: cached while the frame's item, rotation and direction stay the same. Frames
 * holding a map stay vanilla: maps are custom geometry with a translucent text render type, drawn in the translucent
 * phase (`ItemFrameRenderer.submit` → `MapRenderer.render`). An item that picks another model right after its capture
 * (a compass needle settling) pauses on vanilla, like on shelves.
 */
object ItemFrames : EntityRule("item_frames") {
    override fun enabled(types: CacheTypes) = types.itemFrames

    override fun isStatic(state: EntityRenderState): Boolean = (state as ItemFrameRenderState).mapId == null

    override fun store(entry: EntityEntry, state: EntityRenderState): Boolean {
        val frame = state as ItemFrameRenderState
        val inputs = FrameInputs()
        inputs.direction = frame.direction
        inputs.rotation = frame.rotation
        inputs.glow = frame.isGlowFrame
        inputs.invisible = frame.isInvisible
        entry.inputs = inputs
        return inputs.item.store(frame.item) && inputs.frame.store(frame.frameModel)
    }

    override fun same(entry: EntityEntry, state: EntityRenderState): Boolean {
        val frame = state as ItemFrameRenderState
        val inputs = entry.inputs as FrameInputs
        return frame.direction === inputs.direction && frame.rotation == inputs.rotation &&
            frame.isGlowFrame == inputs.glow && frame.isInvisible == inputs.invisible &&
            inputs.frame.matches(frame.frameModel) && inputs.item.matches(frame.item)
    }

    private class FrameInputs {
        var direction = Direction.NORTH
        var rotation = 0
        var glow = false
        var invisible = false
        val item = ItemFingerprint()
        val frame = BlockModelFingerprint()
    }
}

/**
 * Armor stands: cached while pose, held items, head item and everything else their renderer reads stay the same
 * (`ArmorStandRenderer`, `LivingEntityRenderer.submit`, its layers). Vanilla while they wobble after a hit
 * (`wiggle` < 5 ticks), while hurt (red overlay) and with a player head that has an owner (the skin loads later).
 * Stands wearing armor stay vanilla: `EquipmentLayerRenderer` submits each armor layer (dye, overlay, trim) in a submit
 * order of its own so that they are drawn one after another, and the cache does not keep that order.
 * `HumanoidModel.setupAnim` bobs the arms with `ageInTicks`, but `ArmorStandArmorModel.setupAnim` sets every arm, leg,
 * head and body rotation from the poses afterwards, so the picture does not depend on time.
 *
 * Armor is compared by the entity's own equipment stacks: the client replaces a stack when the server sends
 * equipment, and vanilla's render state only holds copies of them.
 */
object ArmorStands : EntityRule("armor_stands") {
    private val ARMOR_SLOTS = arrayOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /** `ArmorStandRenderer.setupRotations` wobbles the stand while `wiggle` is below this. */
    private const val WIGGLE_TICKS = 5.0f

    override fun enabled(types: CacheTypes) = types.armorStands

    override fun isStatic(state: EntityRenderState): Boolean {
        val stand = state as ArmorStandRenderState
        return stand.wiggle >= WIGGLE_TICKS && !stand.hasRedOverlay && stand.deathTime == 0f &&
            stand.wornHeadProfile == null
    }

    override fun store(entry: EntityEntry, state: EntityRenderState): Boolean {
        val stand = state as ArmorStandRenderState
        if (!stand.headEquipment.isEmpty || !stand.chestEquipment.isEmpty || !stand.legsEquipment.isEmpty ||
            !stand.feetEquipment.isEmpty
        ) {
            return false
        }
        val inputs = StandInputs()
        entry.inputs = inputs
        inputs.floats[0] = stand.yRot
        inputs.floats[1] = stand.bodyRot
        inputs.floats[2] = stand.xRot
        inputs.floats[3] = stand.scale
        inputs.floats[4] = stand.ageScale
        inputs.floats[5] = stand.walkAnimationPos
        inputs.floats[6] = stand.walkAnimationSpeed
        inputs.floats[7] = stand.wornHeadAnimationPos
        inputs.floats[8] = stand.swimAmount
        inputs.floats[9] = stand.speedValue
        inputs.floats[10] = stand.elytraRotX
        inputs.floats[11] = stand.elytraRotY
        inputs.floats[12] = stand.elytraRotZ
        inputs.floats[13] = stand.ticksUsingItem
        inputs.floats[14] = stand.swingAnimation
        inputs.floats[15] = stand.ticksSinceKineticHitFeedback
        inputs.flags = flags(stand)
        inputs.pose = stand.pose
        inputs.bedOrientation = stand.bedOrientation
        inputs.wornHeadType = stand.wornHeadType
        inputs.useItemHand = stand.useItemHand
        // ArmorStandRenderState hides ArmedEntityRenderState's arm poses with its own `Rotations` fields.
        val armed: ArmedEntityRenderState = stand
        inputs.mainArm = armed.mainArm
        inputs.rightArm = armed.rightArmPose
        inputs.leftArm = armed.leftArmPose
        inputs.swing = armed.currentSwing
        inputs.rotations[0] = stand.headPose
        inputs.rotations[1] = stand.bodyPose
        inputs.rotations[2] = stand.leftArmPose
        inputs.rotations[3] = stand.rightArmPose
        inputs.rotations[4] = stand.leftLegPose
        inputs.rotations[5] = stand.rightLegPose
        val entity = entry.entity as LivingEntity
        for (i in ARMOR_SLOTS.indices) inputs.armor[i] = entity.getItemBySlot(ARMOR_SLOTS[i])
        return inputs.head.store(stand.headItem) && inputs.rightHand.store(armed.rightHandItemState) &&
            inputs.leftHand.store(armed.leftHandItemState)
    }

    override fun same(entry: EntityEntry, state: EntityRenderState): Boolean {
        val stand = state as ArmorStandRenderState
        val armed: ArmedEntityRenderState = stand
        val inputs = entry.inputs as StandInputs
        val floats = inputs.floats
        if (stand.yRot != floats[0] || stand.bodyRot != floats[1] || stand.xRot != floats[2] ||
            stand.scale != floats[3] || stand.ageScale != floats[4] || stand.walkAnimationPos != floats[5] ||
            stand.walkAnimationSpeed != floats[6] || stand.wornHeadAnimationPos != floats[7] ||
            stand.swimAmount != floats[8] || stand.speedValue != floats[9] || stand.elytraRotX != floats[10] ||
            stand.elytraRotY != floats[11] || stand.elytraRotZ != floats[12] || stand.ticksUsingItem != floats[13] ||
            stand.swingAnimation != floats[14] || stand.ticksSinceKineticHitFeedback != floats[15]
        ) {
            return false
        }
        if (flags(stand) != inputs.flags || stand.pose !== inputs.pose ||
            stand.bedOrientation !== inputs.bedOrientation || stand.wornHeadType !== inputs.wornHeadType || stand.useItemHand !== inputs.useItemHand ||
            armed.mainArm !== inputs.mainArm || armed.rightArmPose !== inputs.rightArm ||
            armed.leftArmPose !== inputs.leftArm || armed.currentSwing !== inputs.swing
        ) {
            return false
        }
        val rotations = inputs.rotations
        if (stand.headPose != rotations[0] || stand.bodyPose != rotations[1] ||
            stand.leftArmPose != rotations[2] || stand.rightArmPose != rotations[3] ||
            stand.leftLegPose != rotations[4] || stand.rightLegPose != rotations[5]
        ) {
            return false
        }
        val entity = entry.entity as LivingEntity
        for (i in ARMOR_SLOTS.indices) if (entity.getItemBySlot(ARMOR_SLOTS[i]) !== inputs.armor[i]) return false
        return inputs.head.matches(stand.headItem) && inputs.rightHand.matches(armed.rightHandItemState) &&
            inputs.leftHand.matches(armed.leftHandItemState)
    }

    private fun flags(stand: ArmorStandRenderState): Int {
        var flags = 0
        if (stand.isMarker) flags = flags or 1
        if (stand.isSmall) flags = flags or 2
        if (stand.showArms) flags = flags or 4
        if (stand.showBasePlate) flags = flags or 8
        if (stand.isInvisible) flags = flags or 16
        if (stand.isInvisibleToPlayer) flags = flags or 32
        if (stand.isUpsideDown) flags = flags or 64
        if (stand.isBaby) flags = flags or 128
        if (stand.isFullyFrozen) flags = flags or 256
        if (stand.isCrouching) flags = flags or 512
        if (stand.isFallFlying) flags = flags or 1024
        if (stand.isVisuallySwimming) flags = flags or 2048
        if (stand.isPassenger) flags = flags or 4096
        if (stand.isUsingItem) flags = flags or 8192
        if (stand.isAutoSpinAttack) flags = flags or 16384
        if (stand.isInWater) flags = flags or 32768
        return flags
    }

    private class StandInputs {
        val floats = FloatArray(16)
        var flags = 0
        var pose: Pose? = null
        var bedOrientation: Direction? = null
        var wornHeadType: Any? = null
        var useItemHand: InteractionHand? = null
        var mainArm: HumanoidArm? = null
        var rightArm: HumanoidModel.ArmPose? = null
        var leftArm: HumanoidModel.ArmPose? = null
        var swing: Any? = null
        val rotations = arrayOfNulls<Rotations>(6)
        val armor = arrayOfNulls<ItemStack>(4)
        val head = ItemFingerprint()
        val rightHand = ItemFingerprint()
        val leftHand = ItemFingerprint()
    }
}

/**
 * Block and item display entities: cached while they use the `FIXED` billboard (the other modes turn with the camera),
 * their transformation has finished interpolating, and their render state (transformation, billboard, brightness,
 * shadow, glow colour; replaced as a whole when any of it changes), rotation and block or item stay the same. Moving
 * displays change their position every frame and take the vanilla path.
 *
 * A block display's block state is compared too: with Fabric's renderer API the block model is a mesh that
 * [BlockModelFingerprint] cannot see (the model parts stay empty), and the mesh only depends on the block state.
 */
object Displays : EntityRule("display_entities") {
    override fun enabled(types: CacheTypes) = types.displayEntities

    override fun isStatic(state: EntityRenderState): Boolean {
        val display = state as DisplayEntityRenderState
        val renderState = display.renderState ?: return false
        return renderState.billboardConstraints() == Display.BillboardConstraints.FIXED &&
            display.interpolationProgress == 1f && display.hasSubState()
    }

    override fun store(entry: EntityEntry, state: EntityRenderState): Boolean {
        val display = state as DisplayEntityRenderState
        val inputs = DisplayInputs()
        inputs.renderState = display.renderState
        inputs.yRot = display.entityYRot
        inputs.xRot = display.entityXRot
        inputs.blockState = blockState(entry.entity)
        entry.inputs = inputs
        return when (display) {
            is BlockDisplayEntityRenderState -> inputs.block.store(display.blockModel)
            is ItemDisplayEntityRenderState -> inputs.item.store(display.item)
            else -> false
        }
    }

    override fun same(entry: EntityEntry, state: EntityRenderState): Boolean {
        val display = state as DisplayEntityRenderState
        val inputs = entry.inputs as DisplayInputs
        if (display.renderState !== inputs.renderState || display.entityYRot != inputs.yRot ||
            display.entityXRot != inputs.xRot || blockState(entry.entity) !== inputs.blockState
        ) {
            return false
        }
        return when (display) {
            is BlockDisplayEntityRenderState -> inputs.block.matches(display.blockModel)
            is ItemDisplayEntityRenderState -> inputs.item.matches(display.item)
            else -> false
        }
    }

    private fun blockState(entity: Entity): BlockState? =
        (entity as? Display.BlockDisplay)?.blockRenderState()?.blockState()

    private class DisplayInputs {
        var renderState: Display.RenderState? = null
        var blockState: BlockState? = null
        var yRot = 0f
        var xRot = 0f
        val block = BlockModelFingerprint()
        val item = ItemFingerprint()
    }
}
