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
import java.util.Set;
import java.util.UUID;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 将 Create 库存访问接入已加载存储及其全局标识。 Connects Create inventory access to loaded storage and its identity.
 */
public final class StorageConnection extends InvManipulationBehaviour {
  /** AnvilCraft 命名空间中支持的方块路径。 Supported block paths in the AnvilCraft namespace. */
  public static final Set<String> BLOCKS =
      Set.of("crate", "large_crate", "shulker_container", "hyperdimension_storage_station");

  /**
   * 使用 Create 目标过滤器连接打包器库存面。 Connects the packager inventory face using Create target filtering.
   */
  public StorageConnection(PackagerBlockEntity be) {
    super(be, InterfaceProvider.oppositeOfBlockFacing());
    withFilter(
        target -> target != null && !(target instanceof PortableStorageInterfaceBlockEntity));
  }

  /**
   * 返回已加载的服务端存储实体，不可用时返回 {@code null}。 Returns loaded server storage, or {@code null} if
   * unavailable.
   */
  public static StorageBlockEntity resolve(Level level, BlockPos pos) {
    return level.isClientSide ? null : resolveBlock(level, pos);
  }

  /** 只读查找，可用于客户端和服务端放置预览。 Read-only lookup for placement preview on both client and server. */
  public static StorageBlockEntity resolveBlock(Level level, BlockPos pos) {
    if (!level.isLoaded(pos)) {
      return null;
    }
    BlockState state = level.getBlockState(pos);
    var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
    if (!id.getNamespace().equals("anvilcraft") || !BLOCKS.contains(id.getPath())) {
      return null;
    }
    if (state.getBlock() instanceof AbstractMultiPartBlock<?> multipart) {
      pos = multipart.getMainPartPos(pos, state);
    }
    if (!level.isLoaded(pos)) {
      return null;
    }
    BlockEntity be = level.getBlockEntity(pos);
    return be instanceof StorageBlockEntity storage ? storage : null;
  }

  /**
   * 解析服务端存储，按需分配 UUID 并刷新板条箱销毁设置。 Resolves storage, assigning its UUID and refreshing disposal
   * settings.
   */
  public static BaseStorage<?> storage(StorageBlockEntity be) {
    if (be.getId() == null) {
      be.setId(UUID.randomUUID());
    }
    if (be instanceof CrateBlockEntity crate) {
      crate.refreshDispose();
    }
    return Storages.get().getOrCreate(be.getId(), be.getStorageType().clazz());
  }

  @Override
  public void findNewCapability() {
    StorageBlockEntity be = resolve(getWorld(), getTarget().getOpposite().getPos());
    if (be != null) {
      targetCapability = new StorageBridge(storage(be));
    } else {
      super.findNewCapability();
    }
  }

  @Override
  public IItemHandler getInventory() {
    // 在访问边界校验 UUID 与 handler 身份，覆盖存储升级和多方块替换。
    // Validate UUID/handler identity at the access boundary, including storage upgrades and
    // multipart replacement.
    if (targetCapability instanceof StorageBridge bridge) {
      StorageBlockEntity be = resolve(getWorld(), getTarget().getOpposite().getPos());
      if (be == null) {
        targetCapability = null;
        return null;
      }
      BaseStorage<?> current = storage(be);
      if (current != bridge.storage) {
        targetCapability = new StorageBridge(current);
      }
    }
    return super.getInventory();
  }

  /**
   * 使用全局 UUID 标识存储，世界仅用于解析查询面。 Identifies storage by global UUID; the level only resolves queried
   * faces.
   */
  public record Identifier(Level level, UUID id) implements InventoryIdentifier {
    // AnvilCraft 存储 UUID 在全服共享，包括不同维度。
    // AnvilCraft storage UUIDs are global to the server, even across dimensions.
    @Override
    public boolean equals(Object other) {
      return other instanceof Identifier i && id.equals(i.id);
    }

    @Override
    public int hashCode() {
      return id.hashCode();
    }

    @Override
    public boolean contains(BlockFace face) {
      StorageBlockEntity be = resolve(level, face.getPos());
      return be != null && id.equals(be.getId());
    }
  }
}
