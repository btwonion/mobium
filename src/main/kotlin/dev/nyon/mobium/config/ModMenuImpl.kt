package dev.nyon.mobium.config

/*? if fabric {*/
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.nyon.mobium.config.screen.MobiumSettingsScreen

/** Mod Menu's settings button opens [MobiumSettingsScreen]. */
@Suppress("unused")
class ModMenuImpl : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { MobiumSettingsScreen(it) }
}
/*?}*/
