package dev.packageconnect;

import com.simibubi.create.api.packager.InventoryIdentifier;
import com.simibubi.create.content.contraptions.actors.psi.PortableStorageInterfaceBlockEntity;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import dev.dubhe.anvilcraft.block.entity.storage.CrateBlockEntity;
import dev.dubhe.anvilcraft.block.entity.storage.StorageBlockEntity;
import dev.dubhe.anvilcraft.block.multipart.AbstractMultiPartBlock;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import dev.dubhe.anvilcraft.saved.storage.Storages;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.UUID;

public final class StorageConnection extends InvManipulationBehaviour {
    public static final Set<String> BLOCKS = Set.of("crate", "large_crate", "shulker_container", "hyperdimension_storage_station");
    public StorageConnection(PackagerBlockEntity be) {
        super(be, InterfaceProvider.oppositeOfBlockFacing());
        withFilter(target -> target != null && !(target instanceof PortableStorageInterfaceBlockEntity));
    }

    public static StorageBlockEntity resolve(Level level, BlockPos pos) {
        return level.isClientSide ? null : resolveBlock(level, pos);
    }

    /** Read-only lookup, usable for placement preview on both client and server. */
    public static StorageBlockEntity resolveBlock(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return null;
        BlockState state = level.getBlockState(pos);
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!id.getNamespace().equals("anvilcraft") || !BLOCKS.contains(id.getPath())) return null;
        if (state.getBlock() instanceof AbstractMultiPartBlock<?> multipart) pos = multipart.getMainPartPos(pos, state);
        if (!level.isLoaded(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof StorageBlockEntity storage ? storage : null;
    }

    public static BaseStorage<?> storage(StorageBlockEntity be) {
        if (be.getId() == null) be.setId(UUID.randomUUID());
        if (be instanceof CrateBlockEntity crate) crate.refreshDispose();
        return Storages.get().getOrCreate(be.getId(), be.getStorageType().clazz());
    }

    @Override public void findNewCapability() {
        StorageBlockEntity be = resolve(getWorld(), getTarget().getOpposite().getPos());
        if (be != null) targetCapability = new StorageBridge(storage(be));
        else super.findNewCapability();
    }

    @Override public net.neoforged.neoforge.items.IItemHandler getInventory() {
        // Validate UUID/handler identity at the access boundary, including storage upgrades and multipart replacement.
        if (targetCapability instanceof StorageBridge bridge) {
            StorageBlockEntity be = resolve(getWorld(), getTarget().getOpposite().getPos());
            if (be == null) { targetCapability = null; return null; }
            BaseStorage<?> current = storage(be);
            if (current != bridge.storage) targetCapability = new StorageBridge(current);
        }
        return super.getInventory();
    }

    public record Identifier(Level level, UUID id) implements InventoryIdentifier {
        // AnvilCraft storage UUIDs are global to the server, even across dimensions.
        @Override public boolean equals(Object other) { return other instanceof Identifier i && id.equals(i.id); }
        @Override public int hashCode() { return id.hashCode(); }
        @Override public boolean contains(BlockFace face) {
            StorageBlockEntity be = resolve(level, face.getPos());
            return be != null && id.equals(be.getId());
        }
    }
}
