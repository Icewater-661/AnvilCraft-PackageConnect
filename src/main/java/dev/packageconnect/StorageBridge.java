package dev.packageconnect;

import com.simibubi.create.content.logistics.packager.PackagingRequest;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** 将原生存储适配到 Create，并提供索引打包视图。 Adapts native storage for Create with indexed packaging views. */
public final class StorageBridge implements IItemHandler {
  final BaseStorage<?> storage;

  /** 创建适配器，不复制或持有独立库存。 Creates an adapter without copying or owning the underlying inventory. */
  public StorageBridge(BaseStorage<?> storage) {
    this.storage = storage;
  }

  /** 返回此存储当前的共享索引。 Returns the current shared index for this storage. */
  public StorageCache cache() {
    return StorageCache.get(storage);
  }

  /** 返回跨维度共享的全服存储标识。 Returns the server-wide storage identity shared across dimensions. */
  public UUID storageId() {
    return storage.getId();
  }

  @Override
  public int getSlots() {
    return storage.getItems().size() + 1;
  }

  @Override
  public ItemStack getStackInSlot(int slot) {
    return storage.getItems().getStackInSlot(slot);
  }

  @Override
  public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
    return storage.getItems().insertItem(stack, simulate);
  }

  @Override
  public ItemStack extractItem(int slot, int amount, boolean simulate) {
    return storage.getItems().extractItem(slot, amount, simulate);
  }

  @Override
  public int getSlotLimit(int slot) {
    return storage.getItems().getSlotLimit(slot);
  }

  @Override
  public boolean isItemValid(int slot, ItemStack stack) {
    return storage.getItems().isItemValid(slot, stack);
  }

  /**
   * 创建只允许提取的视图，跟随可变请求队列的当前队首。 Creates an extraction-only view that follows the current head of a
   * mutable request queue.
   *
   * @param requests 实时订单队列，{@code null} 表示全部索引类型；the live order queue, or {@code null} for all
   *     types
   * @return 读取和提取原始库存的视图；a view that reads and extracts from the original storage
   */
  public IItemHandler packingView(List<PackagingRequest> requests) {
    StorageCache cache = cache();
    List<ItemStack> keys = requests == null ? cache.keys() : List.of();
    return new IItemHandler() {
      private ItemStack item(int slot) {
        if (requests != null) {
          return requests.isEmpty() ? ItemStack.EMPTY : requests.getFirst().item();
        }
        return slot < 0 || slot >= keys.size() ? ItemStack.EMPTY : keys.get(slot);
      }

      @Override
      public int getSlots() {
        return requests == null ? keys.size() : requests.isEmpty() ? 0 : 1;
      }

      @Override
      public ItemStack getStackInSlot(int slot) {
        return extractItem(slot, 64, true);
      }

      @Override
      public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack key = item(slot);
        return key.isEmpty()
            ? ItemStack.EMPTY
            : StorageCache.get(storage).extract(key, amount, simulate);
      }

      @Override
      public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        return stack;
      }

      @Override
      public int getSlotLimit(int slot) {
        return 64;
      }

      @Override
      public boolean isItemValid(int slot, ItemStack stack) {
        return false;
      }
    };
  }
}
