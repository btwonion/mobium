package dev.nyon.mobium.debug.selfcheck

import dev.nyon.mobium.Mobium
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * The [SelfCheck] steps that need Sodium's internal config classes, reached by reflection because Sodium's NeoForge
 * jar nests them where the compiler cannot see them (same classes on both loaders: 0.9.2 Fabric, 0.9.3-beta.1
 * NeoForge). Only touched when Sodium is installed. Changing an option and applying it here is what Sodium's own
 * controls and its Apply button do.
 */
internal object SodiumCheck {
    private const val PACKAGE = "net.caffeinemc.mods.sodium.client"

    private fun config(): Any? = Class.forName("$PACKAGE.config.ConfigManager").getField("CONFIG").get(null)

    private fun Any.call(name: String, vararg args: Any?): Any? =
        javaClass.methods.first { it.name == name && it.parameterCount == args.size }.invoke(this, *args)

    private fun modOptions(): List<Any> = (config()?.call("getModOptions") as? List<*>)?.filterNotNull() ?: emptyList()

    /** Mobium's option page, or null if Sodium did not pick it up. */
    fun mobiumPage(): Any? =
        modOptions().firstOrNull { it.call("configId") == Mobium.MOD_ID }?.let { (it.call("pages") as List<*>).firstOrNull() }

    fun describe(): String = modOptions().joinToString { "${it.call("configId")}" }

    fun screen(parent: Screen, page: Any): Screen {
        val pageClass = Class.forName("$PACKAGE.config.structure.OptionPage")
        return Class.forName("$PACKAGE.gui.VideoSettingsScreen")
            .getMethod("createScreen", Screen::class.java, pageClass)
            .invoke(null, parent, page) as Screen
    }

    fun optionNames(): List<String> {
        val page = mobiumPage() ?: return emptyList()
        return (page.call("groups") as List<*>).flatMap { group ->
            (group!!.call("options") as List<*>).map { (it!!.call("getName") as Component).string }
        }
    }

    fun hasStatusButton(): Boolean =
        option(Identifier.fromNamespaceAndPath(Mobium.MOD_ID, "all_settings"))?.javaClass?.simpleName == "ExternalButtonOption"

    fun setAndApply(id: Identifier, value: Boolean) {
        checkNotNull(option(id)) { "no Sodium option $id" }.call("modifyValue", value)
        config()!!.call("applyAllOptions")
    }

    private fun option(id: Identifier): Any? = config()?.call("getOption", id)
}
