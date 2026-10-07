package dev.packageconnect;

import com.simibubi.create.api.packager.unpacking.UnpackingHandler;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StorageUnpacking implements UnpackingHandler {
    @Override public boolean unpack(Level level, BlockPos pos, BlockState state, Direction side,
                                    List<ItemStack> items, PackageOrderWithCrafts context, boolean simulate) {
        var be = StorageConnection.resolve(level, pos);
        if (be == null) return false;
        var storage = StorageConnection.storage(be);
        StorageCache cache = StorageCache.get(storage);
        if (!fits(cache, items)) return false;
        if (simulate) return true;
        // Create calls this on the server thread; no mutation can interleave dry-run and commit.
        for (ItemStack item : items) {
            ItemStack remainder = storage.getItems().insertItem(item.copy(), false);
            if (!remainder.isEmpty()) {
                throw new IllegalStateException("PackageConnect capacity plan differed from AnvilCraft insertion");
            }
        }
        return true;
    }

    static boolean fits(StorageCache cache, List<ItemStack> items) {
        cache.ensure();
        var handler = cache.handler;
        long used = cache.space;
        Map<StorageCache.Key, Long> added = new HashMap<>();
        // AnvilCraft's finite-type handler counts occupied physical entries, including migrated duplicates.
        int types = cache.occupiedSlots();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            if (!handler.isItemValid(0, stack)) return false;
            StorageCache.Key key = new StorageCache.Key(stack);
            long old = cache.count(key) + added.getOrDefault(key, 0L);
            long incoming = stack.getCount();
            if (handler instanceof TypeLimitItemStacksResourceHandler typed) {
                if (old == 0 && ++types > typed.getTypeLimit()) return false;
                if (old + incoming > TypeLimitItemStacksResourceHandler.computeCount(stack, typed.getSpaceSize())) return false;
            } else if (handler instanceof SpaceSizeItemStacksResourceHandler sized) {
                long available = Math.max(0, sized.getSpaceSize() - used);
                int weight = StorageCache.weight(stack);
                boolean dispose = sized instanceof OverflowDisposalItemStacksResourceHandler overflow
                    && overflow.isDispose() && !OverflowDisposalItemStacksResourceHandler.isEternal(stack);
                if (!dispose && incoming * weight > available) return false;
                incoming = Math.min(incoming, available / weight);
                used += incoming * weight;
            } else if (!(handler instanceof InfiniteItemStacksResourceHandler)) {
                return false;
            }
            added.merge(key, incoming, Long::sum);
        }
        return true;
    }
}
