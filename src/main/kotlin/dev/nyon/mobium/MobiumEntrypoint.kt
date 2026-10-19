package dev.nyon.mobium

import dev.nyon.mobium.bench.BenchCommand
import dev.nyon.mobium.debug.DebugLines

/*? if fabric {*/
import dev.nyon.mobium.mixins.DebugScreenEntriesAccessor
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader

object MobiumEntrypoint : ClientModInitializer {
    override fun onInitializeClient() {
        Mobium.bootstrap(FabricLoader.getInstance().configDir.resolve("mobium.json"))

        ClientLifecycleEvents.CLIENT_STARTED.register { Mobium.onClientStarted() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> dispatcher.register(BenchCommand.create()) }
        DebugScreenEntriesAccessor.`mobium$register`(DebugLines.ID, DebugLines)
    }
}

/*?} else {*/
/*import dev.nyon.klf.MOD_BUS
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLLoader
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent
import net.neoforged.neoforge.common.NeoForge

@Mod("mobium")
object MobiumEntrypoint {
    init {
        Mobium.bootstrap(FMLLoader.getCurrent().gameDir.resolve("config/mobium.json"))

        // Posted on the render thread when the loading overlay finishes; the GPU device exists by then.
        NeoForge.EVENT_BUS.addListener<ClientResourceLoadFinishedEvent> { event ->
            if (event.isInitial) Mobium.onClientStarted()
        }
        NeoForge.EVENT_BUS.addListener<RegisterClientCommandsEvent> { event ->
            event.dispatcher.register(BenchCommand.create())
        }
        MOD_BUS.addListener<RegisterDebugEntriesEvent> { event -> event.register(DebugLines.ID, DebugLines) }
    }
}
*//*?}*/
