package dev.nyon.mobium.config.screen

import com.mojang.serialization.Codec
import dev.nyon.mobium.config.CacheConfig
import dev.nyon.mobium.config.Config
import dev.nyon.mobium.config.LodPreset
import dev.nyon.mobium.config.applyConfig
import dev.nyon.mobium.config.config
import dev.nyon.mobium.core.ModuleStates
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.OptionsSubScreen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.reflect.KMutableProperty0

/**
 * Mobium's settings, built from vanilla's option widgets so it looks like the video settings. It edits a copy of the
 * config: Done saves it and applies it at once ([applyConfig] logs every module that changes state), Cancel and
 * Escape discard it. The same screen opens from Mod Menu (Fabric), the mod list (NeoForge) and the button on
 * Mobium's page in Sodium's video settings.
 */
class MobiumSettingsScreen(parent: Screen) :
    OptionsSubScreen(parent, Minecraft.getInstance().options, Component.translatable("mobium.settings.title")) {
    private val draft: Config = config.deepCopy()

    override fun addOptions() {
        val list = list ?: return
        list.addHeader(Component.translatable("mobium.settings.status"))
        for (status in ModuleStates.all()) {
            val key = status.reason?.translationKey ?: "mobium.settings.status.active"
            val state = Component.translatable(key, status.detail)
                .withStyle(if (status.active) ChatFormatting.GREEN else ChatFormatting.GRAY)
            val line = Component.translatable("mobium.settings.status.line", Component.translatable(status.module.translationKey), state)
            list.addBig(StringWidget(310, 20, line, font).also {
                it.setTooltip(Tooltip.create(Component.translatable("$key.tooltip", status.detail)))
            })
        }

        list.addHeader(Component.translatable("mobium.settings.general"))
        list.addBig(toggle("mobium.settings.enabled", draft::enabled))

        val cache = draft.cache
        list.addHeader(Component.translatable("mobium.module.static_cache"))
        list.addSmall(toggle("mobium.settings.module_enabled", cache::enabled), memoryBudget(cache))
        val types = cache.types
        val switches = types.switches().map { (key, property) -> toggle("mobium.settings.type.$key", property) }
        list.addSmall(*(switches + toggle("mobium.settings.prototype", cache::prototype)).toTypedArray())

        list.addHeader(Component.translatable("mobium.module.part_transform"))
        list.addBig(toggle("mobium.settings.module_enabled", draft.parts::enabled))

        list.addHeader(Component.translatable("mobium.module.animation_lod"))
        list.addBig(lodPreset(draft))
    }

    override fun addFooter() {
        val footer = layout.addToFooter(LinearLayout.horizontal().spacing(8))
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.build())
        footer.addChild(Button.builder(CommonComponents.GUI_DONE) {
            if (draft != config) applyConfig(draft)
            onClose()
        }.build())
    }

    override fun onClose() {
        minecraft.gui.setScreen(lastScreen)
    }

    /** A vanilla on/off button bound to a draft property; the tooltip key is the caption key plus `.tooltip`. */
    private fun toggle(key: String, property: KMutableProperty0<Boolean>): OptionInstance<Boolean> =
        OptionInstance.createBoolean(key, tooltip(key), property.get()) { property.set(it) }

    private fun memoryBudget(cache: CacheConfig): OptionInstance<Int> {
        val key = "mobium.settings.memory_budget"
        return OptionInstance(
            key,
            tooltip(key),
            { caption, steps -> Options.genericValueLabel(caption, Component.translatable("mobium.settings.megabytes", steps * MB_STEP)) },
            OptionInstance.IntRange(CacheConfig.MIN_MEMORY_BUDGET_MB / MB_STEP, CacheConfig.MAX_MEMORY_BUDGET_MB / MB_STEP),
            cache.memoryBudgetMb.coerceIn(CacheConfig.MIN_MEMORY_BUDGET_MB, CacheConfig.MAX_MEMORY_BUDGET_MB) / MB_STEP
        ) { cache.memoryBudgetMb = it * MB_STEP }
    }

    private fun lodPreset(draft: Config): OptionInstance<LodPreset> {
        val key = "mobium.settings.lod_preset"
        return OptionInstance(
            key,
            tooltip(key),
            { caption, preset -> Options.genericValueLabel(caption, Component.translatable(presetKey(preset))) },
            OptionInstance.Enum(LodPreset.entries.toList(), Codec.INT.xmap({ LodPreset.entries[it] }, { it.ordinal })),
            draft.lod.preset
        ) { draft.lod.preset = it }
    }

    private fun <T : Any> tooltip(key: String): OptionInstance.TooltipSupplier<T> =
        OptionInstance.cachedConstantTooltip(Component.translatable("$key.tooltip"))

    companion object {
        /** The memory budget slider moves in steps of this many megabytes. */
        const val MB_STEP = 16

        fun presetKey(preset: LodPreset) = "mobium.lod_preset.${preset.name.lowercase()}"
    }
}
