package dev.packageconnect;

import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.InfiniteItemStacksResourceHandler;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.OverflowDisposalItemStacksResourceHandler;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.SpaceSizeItemStacksResourceHandler;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.TypeLimitItemStacksResourceHandler;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 在服务器线程预判整包容量，再通过原生 handler 提交。 Plans whole-package capacity on the server thread before
 * committing through the native handler.
 *
 * <p>模拟只读取库存；预判与提交不得交错其他写入，也不提供通用回滚。 Simulation only reads inventory; planning and commit must not
 * interleave other writes. This is not a general rollback transaction.
 */
public final class StorageUnpacking implements UnpackingHandler {
  @Override
  public boolean unpack(
      Level level,
      BlockPos pos,
      BlockState state,
      Direction side,
      List<ItemStack> items,
      PackageOrderWithCrafts context,
      boolean simulate) {
    var blockEntity = StorageConnection.resolve(level, pos);
    if (blockEntity == null) {
      return false;
    }
    return insert(StorageConnection.storage(blockEntity), items, simulate);
  }

  static boolean insert(BaseStorage<?> storage, List<ItemStack> items, boolean simulate) {
    if (!fits(storage, items)) {
      return false;
    }
    if (!simulate) {
      for (ItemStack item : items) {
        ItemStack remainder = storage.getItems().insertItem(item.copy(), false);
        if (!remainder.isEmpty()) {
          throw new IllegalStateException(
              "PackageConnect capacity plan differed from AnvilCraft insertion");
        }
      }
    }
    return true;
  }

  static boolean fits(BaseStorage<?> storage, List<ItemStack> items) {
    var handler = storage.getItems();
    for (ItemStack stack : items) {
      if (!stack.isEmpty() && !handler.isItemValid(0, stack)) {
        return false;
      }
    }
    if (handler instanceof InfiniteItemStacksResourceHandler) {
      return true;
    }
    StorageCache cache = StorageCache.get(storage);
    if (handler instanceof TypeLimitItemStacksResourceHandler typed) {
      return fitsTypes(typed, cache, items);
    }
    if (handler instanceof SpaceSizeItemStacksResourceHandler sized) {
      return fitsSpace(sized, cache.usedSpace(), items);
    }
    return false;
  }

  private static boolean fitsTypes(
      TypeLimitItemStacksResourceHandler handler, StorageCache cache, List<ItemStack> items) {
    Object2LongMap<ItemKey> added = new Object2LongOpenHashMap<>();
    // 上游按占用的物理条目计数，包括迁移或直接槽位写入产生的重复类型。
    // Upstream counts occupied physical entries, including duplicate types from migration or slots.
    int occupied = cache.occupiedSlots();
    for (ItemStack stack : items) {
      if (stack.isEmpty()) {
        continue;
      }
      ItemKey key = new ItemKey(stack);
      long planned = added.getLong(key);
      long existing = cache.count(key) + planned;
      if (existing == 0 && ++occupied > handler.getTypeLimit()) {
        return false;
      }
      long capacity =
          TypeLimitItemStacksResourceHandler.computeCount(stack, handler.getSpaceSize());
      if (stack.getCount() > capacity - existing) {
        return false;
      }
      added.put(key, planned + stack.getCount());
    }
    return true;
  }

  private static boolean fitsSpace(
      SpaceSizeItemStacksResourceHandler handler, long usedSpace, List<ItemStack> items) {
    long available = Math.max(0, handler.getSpaceSize() - usedSpace);
    for (ItemStack stack : items) {
      if (stack.isEmpty()) {
        continue;
      }
      int weight = SpaceSizeItemStacksResourceHandler.computeSpace(stack, 1);
      long accepted = Math.min(stack.getCount(), available / weight);
      boolean disposesOverflow =
          handler instanceof OverflowDisposalItemStacksResourceHandler overflow
              && overflow.isDispose()
              && !OverflowDisposalItemStacksResourceHandler.isEternal(stack);
      if (!disposesOverflow && accepted < stack.getCount()) {
        return false;
      }
      // 按原顺序累计真正存入的空间；可销毁物品不能占用未实际存入的空间。
      // Preserve insertion order and charge space only for items actually retained after disposal.
      available -= accepted * weight;
    }
    return true;
  }
}
