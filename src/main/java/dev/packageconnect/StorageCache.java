package dev.packageconnect;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.UnlimitedItemStacksResourceHandler;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 仅限服务器线程，按 UUID 共享，不持久化副本库存。 Server-thread-only, shared by UUID; no persisted duplicate inventory.
 */
public final class StorageCache {
  private static final Map<UUID, StorageCache> caches = new LinkedHashMap<>(16, .75f, true);

  /** 上次服务器停止以来的完整缓存构建次数。 Number of full cache builds since the last server stop. */
  public static long builds;

  /** 完整缓存构建扫描的物理槽位总数。 Number of physical slots scanned during full cache builds. */
  public static long scannedSlots;

  /** 槽位刷新总数，包括完整构建中的刷新。 Number of slot refreshes, including those during a full build. */
  public static long updates;

  /** 索引提取请求总数，包括模拟请求。 Number of indexed extraction requests, including simulations. */
  public static long indexedExtractions;

  /** 复用未变化库存摘要的请求总数。 Number of requests served by an unchanged inventory summary. */
  public static long stockHits;

  final UnlimitedItemStacksResourceHandler handler;
  private final Map<Integer, Slot> slots = new HashMap<>();
  final Map<Key, Entry> entries = new LinkedHashMap<>();
  private InventorySummary summary;
  private boolean dirty = true;
  long space;

  private StorageCache(UnlimitedItemStacksResourceHandler handler) {
    this.handler = handler;
  }

  /**
   * 返回共享缓存，存储 handler 更换时重新构建。 Returns the shared cache, rebuilding when the storage handler
   * changes.
   */
  public static StorageCache get(BaseStorage<?> storage) {
    StorageCache cache = caches.get(storage.getId());
    if (cache == null || cache.handler != storage.getItems()) {
      cache = new StorageCache(storage.getItems());
      caches.put(storage.getId(), cache);
    }
    // 限制保留的 handler 引用数量；被淘汰存储在下次访问时重建。
    // Bound retained handler references; an evicted connected storage rebuilds on next access.
    while (caches.size() > 256) {
      caches.remove(caches.keySet().iterator().next());
    }
    cache.ensure();
    return cache;
  }

  /**
   * 刷新变化槽位，批量替换时使缓存失效。 Refreshes a changed slot, or invalidates the cache for a bulk replacement.
   */
  public static void changed(BaseStorage<?> storage, int index) {
    StorageCache cache = caches.get(storage.getId());
    if (cache == null || cache.handler != storage.getItems()) {
      return;
    }
    if (index < 0 || index >= cache.handler.size()) {
      cache.dirty = true;
    } else if (!cache.dirty) {
      cache.refresh(index);
    }
  }

  /** 服务器停止时释放缓存并重置统计。 Releases cached handlers and resets statistics when the server stops. */
  public static void clear() {
    caches.clear();
    builds = scannedSlots = updates = indexedExtractions = stockHits = 0;
  }

  /** 返回诊断操作计数，不代表耗时。 Returns diagnostic operation counts, which do not measure elapsed time. */
  public static String stats() {
    return "caches="
        + caches.size()
        + ", builds="
        + builds
        + ", scannedSlots="
        + scannedSlots
        + ", slotUpdates="
        + updates
        + ", indexedExtractions="
        + indexedExtractions
        + ", stockCacheHits="
        + stockHits;
  }

  void ensure() {
    if (!dirty) {
      return;
    }
    slots.clear();
    entries.clear();
    space = 0;
    dirty = false;
    builds++;
    for (int i = 0; i < handler.size(); i++) {
      scannedSlots++;
      refresh(i);
    }
    summary = null;
  }

  private void refresh(int index) {
    Slot old = slots.remove(index);
    if (old != null) {
      Entry entry = entries.get(old.key);
      entry.count -= old.count;
      entry.slots.remove(index);
      if (entry.count == 0) {
        entries.remove(old.key);
      }
      space -= old.count * weight(old.key.stack);
    }
    ItemStack current = handler.getStackInSlot(index);
    long count = handler.getAmountAsLong(index);
    if (!current.isEmpty() && count > 0) {
      Key key = new Key(current);
      Entry entry = entries.computeIfAbsent(key, k -> new Entry());
      entry.count += count;
      entry.slots.add(index);
      slots.put(index, new Slot(key, count));
      space += count * weight(current);
    }
    summary = null;
    updates++;
  }

  /** 返回共享库存快照，调用方不得修改。 Returns a shared inventory snapshot that callers must not mutate. */
  public InventorySummary summary() {
    ensure();
    if (summary != null) {
      stockHits++;
      return summary;
    }
    InventorySummary result = new InventorySummary();
    // Create 将达到 INF 的计数视为无限；每次加入的类型数量不超过 INF，避免其总数溢出。
    // Create treats >=INF as unbounded. Each per-type add is <=INF, so its total cannot overflow.
    entries.forEach(
        (key, entry) -> result.add(key.stack, (int) Math.min(BigItemStack.INF, entry.count)));
    summary = result;
    return summary;
  }

  long count(Key key) {
    ensure();
    Entry e = entries.get(key);
    return e == null ? 0 : e.count;
  }

  int occupiedSlots() {
    ensure();
    return slots.size();
  }

  ItemStack extract(ItemStack requested, int amount, boolean simulate) {
    ensure();
    indexedExtractions++;
    Entry entry = entries.get(new Key(requested));
    if (entry == null || amount <= 0) {
      return ItemStack.EMPTY;
    }
    int count = (int) Math.min(entry.count, Math.min(amount, requested.getMaxStackSize()));
    if (simulate) {
      return requested.copyWithCount(count);
    }
    int remaining = count;
    // 仅复制匹配槽位：原生变更回调会在提取期间更新此索引项。
    // Snapshot only matching slots: native mutation callbacks update this entry during extraction.
    for (int slot : new ArrayList<>(entry.slots)) {
      remaining -= handler.extractItem(slot, remaining, false).getCount();
      if (remaining == 0) {
        break;
      }
    }
    return requested.copyWithCount(count - remaining);
  }

  List<ItemStack> keys() {
    ensure();
    return entries.keySet().stream().map(k -> k.stack).toList();
  }

  static int weight(ItemStack stack) {
    return (64 + stack.getMaxStackSize() - 1) / stack.getMaxStackSize();
  }

  static final class Key {
    final ItemStack stack;
    private final int hash;

    Key(ItemStack stack) {
      this.stack = stack.copyWithCount(1);
      hash = ItemStack.hashItemAndComponents(this.stack);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key k && ItemStack.isSameItemSameComponents(stack, k.stack);
    }
  }

  static final class Entry {
    long count;
    final Set<Integer> slots = new TreeSet<>();
  }

  private record Slot(Key key, long count) {}
}
