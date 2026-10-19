package dev.nyon.mobium

/*? if fabric {*/
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader

object MobiumEntrypoint : ClientModInitializer {
    override fun onInitializeClient() {
        Mobium.bootstrap(FabricLoader.getInstance().configDir.resolve("mobium.json"))

        ClientLifecycleEvents.CLIENT_STARTED.register { Mobium.onClientStarted() }
    }
}

/*?} else {*/
/*import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLLoader
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent
import net.neoforged.neoforge.common.NeoForge

@Mod("mobium")
object MobiumEntrypoint {
    init {
        Mobium.bootstrap(FMLLoader.getCurrent().gameDir.resolve("config/mobium.json"))

        // Posted on the render thread when the loading overlay finishes; the GPU device exists by then.
        NeoForge.EVENT_BUS.addListener<ClientResourceLoadFinishedEvent> { event ->
            if (event.isInitial) Mobium.onClientStarted()
        }
    }
}
*//*?}*/
