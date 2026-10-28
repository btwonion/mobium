package dev.nyon.mobium

import dev.nyon.mobium.bench.BenchCommand
import dev.nyon.mobium.bench.BenchPack
import dev.nyon.mobium.debug.DebugLines
import dev.nyon.mobium.debug.ModuleLines

/*? if fabric {*/
import dev.nyon.mobium.mixins.DebugScreenEntriesAccessor
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier

object MobiumEntrypoint : ClientModInitializer {
    override fun onInitializeClient() {
        Mobium.bootstrap(FabricLoader.getInstance().configDir.resolve("mobium.json"))

        ClientLifecycleEvents.CLIENT_STARTED.register { Mobium.onClientStarted() }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ -> dispatcher.register(BenchCommand.create()) }
        DebugScreenEntriesAccessor.`mobium$register`(DebugLines.ID, DebugLines)
        DebugScreenEntriesAccessor.`mobium$register`(ModuleLines.ID, ModuleLines)
        MobiumKeys.ALL.forEach(KeyMappingHelper::registerKeyMapping)
        // NORMAL: listed under Create New World → Data Packs, off in new and existing worlds alike.
        ResourceLoader.registerBuiltinPack(
            Identifier.parse(BenchPack.ID), BenchPack.PATH, FabricLoader.getInstance().getModContainer(Mobium.MOD_ID).orElseThrow(),
            BenchPack.NAME, PackActivationType.NORMAL
        )
    }
}

/*?} else {*/
/*import dev.nyon.klf.MOD_BUS
import dev.nyon.mobium.config.screen.MobiumSettingsScreen
import net.neoforged.fml.ModLoadingContext
import net.neoforged.fml.common.Mod
import net.neoforged.fml.loading.FMLLoader
import net.neoforged.neoforge.client.event.ClientResourceLoadFinishedEvent
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent
import net.neoforged.neoforge.client.gui.IConfigScreenFactory
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.AddPackFindersEvent
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack

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
        MOD_BUS.addListener<RegisterDebugEntriesEvent> { event ->
            event.register(DebugLines.ID, DebugLines)
            event.register(ModuleLines.ID, ModuleLines)
        }
        MOD_BUS.addListener<RegisterKeyMappingsEvent> { event ->
            event.registerCategory(MobiumKeys.CATEGORY)
            MobiumKeys.ALL.forEach(event::register)
        }
        // Not always active, and a source that is not added automatically: off in new and existing worlds alike.
        MOD_BUS.addListener<AddPackFindersEvent> { event ->
            event.addPackFinders(
                Identifier.fromNamespaceAndPath(Mobium.MOD_ID, BenchPack.PATH), PackType.SERVER_DATA, BenchPack.NAME,
                BenchPack.SOURCE, false, Pack.Position.TOP
            )
        }
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory::class.java) {
            IConfigScreenFactory { _, parent -> MobiumSettingsScreen(parent) }
        }
    }
}
*//*?}*/
