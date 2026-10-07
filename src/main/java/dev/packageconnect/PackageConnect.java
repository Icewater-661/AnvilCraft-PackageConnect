package dev.packageconnect;

import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@Mod(PackageConnect.ID)
public final class PackageConnect {
    public static final String ID = "anvilcraft_packageconnect";
    public PackageConnect(IEventBus modBus) {
        modBus.addListener(this::setup);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> StorageCache.clear());
        // Only the isolated Gradle development server enables these integration checks.
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            if (Boolean.getBoolean("packageconnect.selftest")) {
                event.getServer().execute(() -> IntegrationChecks.run(event.getServer()));
            }
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> event.getDispatcher().register(
            Commands.literal("packageconnect").requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats").executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(StorageCache.stats()), false);
                    return 1;
                }))));
    }
    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            StorageUnpacking unpacking = new StorageUnpacking();
            for (String name : StorageConnection.BLOCKS) {
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("anvilcraft", name));
                InventoryIdentifier.REGISTRY.register(block, (level, state, face) -> {
                    var be = StorageConnection.resolve(level, face.getPos());
                    return be == null ? null : new StorageConnection.Identifier(level, StorageConnection.storage(be).getId());
                });
                UnpackingHandler.REGISTRY.register(block, unpacking);
            }
        });
    }
}
