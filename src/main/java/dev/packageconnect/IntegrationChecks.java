package dev.packageconnect;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.logistics.packager.*;
import com.simibubi.create.api.packager.InventoryIdentifier;
import net.createmod.catnip.math.BlockFace;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlock;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import dev.dubhe.anvilcraft.block.entity.storage.StorageBlockEntity;
import dev.dubhe.anvilcraft.block.multipart.AbstractMultiPartBlock;
import dev.dubhe.anvilcraft.saved.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Blocks;
import org.apache.commons.lang3.mutable.MutableBoolean;
import java.util.*;

/** Executed only when the development server explicitly enables the selftest JVM property. */
final class IntegrationChecks {
    private static int assertions;
    static void run(MinecraftServer server) {
        try {
            storageChecks();
            for (String name : StorageConnection.BLOCKS) worldChecks(server, name);
            LogUtils.getLogger().info("PACKAGECONNECT SELFTEST PASS: {} assertions; {}", assertions, StorageCache.stats());
        } catch (Throwable error) {
            LogUtils.getLogger().error("PACKAGECONNECT SELFTEST FAIL", error);
        } finally {
            server.halt(false);
        }
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static ItemStack copper(int count) { return new ItemStack(Items.COPPER_INGOT, count); }

    private static void storageChecks() {
        var crate = new CrateStorage(UUID.randomUUID());
        var handler = crate.getItems();
        handler.insertItem(copper(100), false);
        handler.insertItem(new ItemStack(Items.IRON_INGOT, 200), false);
        var cache = StorageCache.get(crate);
        check(cache.summary().getCountOf(copper(1)) == 100, "initial counts");
        check(StorageCache.get(crate) == cache, "shared cache");
        check(cache.summary() == cache.summary(), "unchanged summary reused");
        check(cache.extract(copper(1), 64, true).getCount() == 64, "simulated extract");
        check(cache.count(new StorageCache.Key(copper(1))) == 100, "simulation did not mutate");
        check(cache.extract(copper(1), 64, false).getCount() == 64, "real indexed extract");
        check(cache.summary().getCountOf(copper(1)) == 36, "mutation hook updates cache");
        handler.extractUnlimited(0, 36, false);
        check(cache.summary().getCountOf(new ItemStack(Items.IRON_INGOT)) == 200, "empty hole retains later slot");
        ItemStack named = copper(10);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("different components"));
        handler.insertItem(named, false);
        check(cache.count(new StorageCache.Key(named)) == 10 && cache.count(new StorageCache.Key(copper(1))) == 0,
            "component variants isolated");
        check(!StorageUnpacking.fits(cache, List.of(copper(1000), new ItemStack(Items.GOLD_INGOT, 1000))),
            "whole package capacity rather than per-stack capacity");
        handler.setDispose(true);
        check(StorageUnpacking.fits(cache, List.of(copper(3000))), "native disposal semantics");

        var shulker = new ShulkerContainerStorage(UUID.randomUUID());
        shulker.getItems().setTypeLimit(2);
        var typed = StorageCache.get(shulker);
        check(!StorageUnpacking.fits(typed, List.of(copper(10), new ItemStack(Items.GOLD_INGOT,10), new ItemStack(Items.IRON_INGOT,10))),
            "combined new-type limit");
        check(!StorageUnpacking.fits(typed, List.of(copper(40000), copper(40000))), "combined per-type limit");
        check(typed.entries.isEmpty(), "unpack simulation did not mutate");

        var infinite = new HyperdimensionStorage(UUID.randomUUID());
        infinite.getItems().insertItem(copper(Integer.MAX_VALUE), false);
        infinite.getItems().insertItem(copper(100), false);
        var huge = StorageCache.get(infinite);
        check(huge.count(new StorageCache.Key(copper(1))) == (long)Integer.MAX_VALUE + 100, "long counts and duplicate physical slots");
        check(huge.summary().getCountOf(copper(1)) == 1_000_000_000, "Create display count saturation");
        huge.extract(copper(1),64,false);
        check(huge.count(new StorageCache.Key(copper(1))) == (long)Integer.MAX_VALUE + 36, "large count extraction");
        long scanned = StorageCache.scannedSlots;
        for (int i=0; i<100; i++) huge.extract(copper(1),64,true);
        check(scanned == StorageCache.scannedSlots, "repeated lookup does not rescan storage");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void worldChecks(MinecraftServer server, String name) {
        var level = server.overworld();
        BlockPos main = new BlockPos(0, 100, 0);
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("anvilcraft",name));
        var state = block.defaultBlockState();
        BlockPos face = main;
        BlockPos other = null;
        if (block instanceof AbstractMultiPartBlock multipart) {
            for (Object part : multipart.getParts()) {
                var candidate = multipart.placedState((Enum)part, state);
                if (multipart.isMainPart(candidate)) { state = candidate; break; }
            }
            level.setBlock(main,state,2);
            for (Object part : multipart.getParts()) {
                var candidate = multipart.placedState((Enum)part,state);
                if (multipart.isMainPart(candidate)) continue;
                var offset = multipart.getOffset(candidate);
                BlockPos candidatePos = main.offset(offset);
                if (multipart.getMainPartPos(candidatePos,candidate).equals(main)) {
                    level.setBlock(candidatePos,candidate,2);
                    face = candidatePos; other = candidatePos; break;
                }
            }
            check(other != null, name + " non-main part placement");
        } else level.setBlock(main,state,2);
        check(level.getBlockEntity(main) instanceof StorageBlockEntity, name + " main block entity");
        var storageBE = (StorageBlockEntity)level.getBlockEntity(main);
        var storage = StorageConnection.storage(storageBE);
        storage.getItems().insertItem(copper(128),false);
        check(StorageConnection.resolve(level,face) == storageBE, name + " multipart resolution");

        BlockPos packPos = face.relative(Direction.EAST);
        if (packPos.equals(main)) packPos = face.relative(Direction.WEST);
        Direction facing = packPos.getX() > face.getX() ? Direction.EAST : Direction.WEST;
        var packBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:packager"));
        var placementContext = new DirectionalPlaceContext(level,packPos,Direction.NORTH,new ItemStack(packBlock),Direction.UP) {
            @Override public Direction[] getNearestLookingDirections() {
                return new Direction[]{Direction.NORTH,Direction.WEST,Direction.EAST,Direction.SOUTH,Direction.DOWN,Direction.UP};
            }
        };
        var placedState = packBlock.getStateForPlacement(placementContext);
        check(placedState != null && placedState.getValue(PackagerBlock.FACING) == facing,
            name + " automatic orientation toward connected multipart face");
        BlockPos preferredChestPos = packPos.north();
        level.setBlock(preferredChestPos,Blocks.CHEST.defaultBlockState(),2);
        var chestFacing = packBlock.getStateForPlacement(placementContext);
        check(chestFacing != null && chestFacing.getValue(PackagerBlock.FACING) == Direction.SOUTH,
            name + " original ordinary-container direction priority");
        level.setBlock(preferredChestPos,Blocks.AIR.defaultBlockState(),2);
        for (Direction side : new Direction[]{Direction.NORTH,Direction.SOUTH,Direction.UP}) {
            BlockPos sidePos = face.relative(side);
            if (!level.getBlockState(sidePos).isAir()) continue;
            var sideContext = new DirectionalPlaceContext(level,sidePos,Direction.NORTH,new ItemStack(packBlock),Direction.UP);
            var sideState = packBlock.getStateForPlacement(sideContext);
            check(sideState != null && sideState.getValue(PackagerBlock.FACING) == side,
                name + " automatic orientation on " + side + " side");
        }
        level.setBlock(packPos,placedState,2);
        var pack = (PackagerBlockEntity)level.getBlockEntity(packPos);
        check(pack != null && pack.targetInventory instanceof StorageConnection, name + " packager behaviour injection");
        pack.targetInventory.findNewCapability();
        check(pack.targetInventory.getInventory() instanceof StorageBridge, name + " direct connection");
        check(pack.getAvailableItems().getCountOf(copper(1)) == 128, name + " stock query");
        if (name.equals("large_crate")) {
            // Exercise the stock-link endpoint that supplies the warehouse terminal.
            BlockPos linkPos = packPos.above();
            var linkBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:stock_link"));
            level.setBlock(linkPos,linkBlock.defaultBlockState().setValue(PackagerLinkBlock.FACE,AttachFace.FLOOR),2);
            var link = (PackagerLinkBlockEntity)level.getBlockEntity(linkPos);
            check(link != null && link.getPackager() == pack, "large_crate stock link connection");
            check(link.fetchSummaryFromPackager(null).getCountOf(copper(1)) == 128,
                "large_crate warehouse stock-link reads items");
            level.setBlock(linkPos,Blocks.AIR.defaultBlockState(),2);
        }
        var mainId = InventoryIdentifier.get(level,new BlockFace(main,Direction.EAST));
        var faceId = InventoryIdentifier.get(level,new BlockFace(face,Direction.EAST));
        check(mainId != null && mainId.equals(faceId), name + " shared inventory identity");
        check(mainId.contains(new BlockFace(face,Direction.NORTH)), name + " identifies other faces");
        var crossDimensionId = new StorageConnection.Identifier(server.getLevel(net.minecraft.world.level.Level.NETHER),storage.getId());
        check(mainId.equals(crossDimensionId), name + " global UUID across dimensions");
        check(pack.isTargetingSameInventory(new IdentifiedInventory(crossDimensionId,pack.targetInventory.getInventory())),
            name + " rejects routing requests into same global storage");
        storage.getItems().insertItem(new ItemStack(Items.IRON_INGOT,10),false);
        var queue = new ArrayList<PackagingRequest>();
        queue.add(PackagingRequest.create(copper(1),80,"test",0,new MutableBoolean(true),0,1,null));
        queue.add(PackagingRequest.create(new ItemStack(Items.IRON_INGOT),10,"test",0,new MutableBoolean(true),0,1,null));
        pack.attemptToSend(queue);
        check(queue.isEmpty(), name + " stock order fulfilled");
        check(StorageCache.get(storage).summary().getCountOf(copper(1)) == 48, name + " stock order exact extraction");
        check(StorageCache.get(storage).count(new StorageCache.Key(new ItemStack(Items.IRON_INGOT))) == 0,
            name + " dynamic multiple-item order");
        pack.animationTicks = 0; pack.heldBox = ItemStack.EMPTY;
        pack.attemptToSend(null);
        check(StorageCache.get(storage).count(new StorageCache.Key(copper(1))) == 0, name + " ordinary packing");
        var unpack = new StorageUnpacking();
        List<ItemStack> contents = List.of(copper(15),new ItemStack(Items.IRON_INGOT,25));
        check(unpack.unpack(level,face,level.getBlockState(face),Direction.EAST,contents,null,true), name + " unpack dry run");
        check(StorageCache.get(storage).count(new StorageCache.Key(copper(1))) == 0, name + " unpack simulation leaves inventory unchanged");
        check(unpack.unpack(level,face,level.getBlockState(face),Direction.EAST,contents,null,false), name + " unpack commit");
        check(StorageCache.get(storage).summary().getCountOf(copper(1)) == 15, name + " unpack final counts");
        level.setBlock(packPos,Blocks.AIR.defaultBlockState(),2);
        if(other != null) level.setBlock(other,Blocks.AIR.defaultBlockState(),2);
        level.setBlock(main,Blocks.AIR.defaultBlockState(),2);
        Storages.get().remove(storage.getId());
    }
}
