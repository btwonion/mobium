package dev.nyon.mobium.config

import dev.nyon.mobium.Mobium
import dev.nyon.mobium.config.screen.MobiumSettingsScreen
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPointForge
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Mobium's page in Sodium's video settings (Sodium Config API), with the same options as [MobiumSettingsScreen],
 * which a button on the page opens for the module status. Found through the `sodium:config_api_user` entrypoint on
 * Fabric and the annotation on NeoForge; only Sodium loads this class. The bindings read and write the live config,
 * and Sodium's Apply saves it and re-resolves the modules once.
 */
@ConfigEntryPointForge(Mobium.MOD_ID)
class SodiumOptions : ConfigEntryPoint {
    private val save = StorageEventHandler { applyConfig(config) }
    private val defaults = Config()

    override fun registerConfigLate(builder: ConfigBuilder) {
        val general = builder.createOptionGroup()
            .setName(Component.translatable("mobium.settings.general"))
            .addOption(
                builder.createBooleanOption(id("enabled"))
                    .setName(Component.translatable("mobium.settings.enabled"))
                    .setTooltip(Component.translatable("mobium.settings.enabled.tooltip"))
                    .setStorageHandler(save)
                    .setDefaultValue(defaults.enabled)
                    .setBinding({ config.enabled = it }, { config.enabled })
            )
            .addOption(
                builder.createExternalButtonOption(id("all_settings"))
                    .setName(Component.translatable("mobium.sodium.all_settings"))
                    .setTooltip(Component.translatable("mobium.sodium.all_settings.tooltip"))
                    .setScreenConsumer { parent -> Minecraft.getInstance().gui.setScreen(MobiumSettingsScreen(parent)) }
            )

        val cache = builder.createOptionGroup()
            .setName(Component.translatable("mobium.module.static_cache"))
            .addOption(
                builder.createBooleanOption(id("cache_enabled"))
                    .setName(Component.translatable("mobium.settings.module_enabled"))
                    .setTooltip(Component.translatable("mobium.settings.module_enabled.tooltip"))
                    .setStorageHandler(save)
                    .setDefaultValue(defaults.cache.enabled)
                    .setBinding({ config.cache.enabled = it }, { config.cache.enabled })
            )
            .addOption(
                builder.createIntegerOption(id("memory_budget"))
                    .setName(Component.translatable("mobium.settings.memory_budget"))
                    .setTooltip(Component.translatable("mobium.settings.memory_budget.tooltip"))
                    .setStorageHandler(save)
                    .setRange(CacheConfig.MIN_MEMORY_BUDGET_MB, CacheConfig.MAX_MEMORY_BUDGET_MB, MobiumSettingsScreen.MB_STEP)
                    .setValueFormatter { Component.translatable("mobium.settings.megabytes", it) }
                    .setDefaultValue(defaults.cache.memoryBudgetMb)
                    .setBinding({ config.cache.memoryBudgetMb = it }, { config.cache.memoryBudgetMb })
            )
        addTypeSwitches(builder, cache)

        val parts = builder.createOptionGroup()
            .setName(Component.translatable("mobium.module.part_transform"))
            .addOption(
                builder.createBooleanOption(id("parts_enabled"))
                    .setName(Component.translatable("mobium.settings.module_enabled"))
                    .setTooltip(Component.translatable("mobium.settings.module_enabled.tooltip"))
                    .setStorageHandler(save)
                    .setDefaultValue(defaults.parts.enabled)
                    .setBinding({ config.parts.enabled = it }, { config.parts.enabled })
            )

        val lod = builder.createOptionGroup()
            .setName(Component.translatable("mobium.module.animation_lod"))
            .addOption(
                builder.createEnumOption(id("lod_preset"), LodPreset::class.java)
                    .setName(Component.translatable("mobium.settings.lod_preset"))
                    .setTooltip(Component.translatable("mobium.settings.lod_preset.tooltip"))
                    .setStorageHandler(save)
                    .setElementNameProvider { Component.translatable(MobiumSettingsScreen.presetKey(it)) }
                    .setDefaultValue(defaults.lod.preset)
                    .setBinding({ config.lod.preset = it }, { config.lod.preset })
            )

        builder.registerModOptions(Mobium.MOD_ID)
            .addPage(
                builder.createOptionPage()
                    .setName(Component.translatable("mobium.settings.title"))
                    .addOptionGroup(general)
                    .addOptionGroup(cache)
                    .addOptionGroup(parts)
                    .addOptionGroup(lod)
            )
    }

    /** One switch per Module 1 type, resolved through the live config on every read and write. */
    private fun addTypeSwitches(builder: ConfigBuilder, group: OptionGroupBuilder) {
        val defaultSwitches = defaults.cache.types.switches()
        for ((index, entry) in defaultSwitches.withIndex()) {
            val key = entry.first
            group.addOption(
                builder.createBooleanOption(id("type_$key"))
                    .setName(Component.translatable("mobium.settings.type.$key"))
                    .setTooltip(Component.translatable("mobium.settings.type.$key.tooltip"))
                    .setStorageHandler(save)
                    .setDefaultValue(entry.second.get())
                    .setBinding(
                        { config.cache.types.switches()[index].second.set(it) },
                        { config.cache.types.switches()[index].second.get() }
                    )
            )
        }
    }

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(Mobium.MOD_ID, path)
}
