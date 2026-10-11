package dev.packageconnect;

import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import dev.dubhe.anvilcraft.api.itemhandler.unlimited.UnlimitedItemStacksResourceHandler;
import dev.dubhe.anvilcraft.saved.storage.BaseStorage;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * 仅限服务器线程，按 UUID 共享原始库存的索引。 Server-thread-only index of the native inventory, shared by UUID.
 *
 * <p>所有写入仍通过原生 handler，其同步内容回调维护索引。 All writes use the native handler; its synchronous content
 * callbacks maintain this index.
 */
public final class StorageCache {
  private static final int MAX_CACHES = 256;
  private static final Map<UUID, StorageCache> caches = new LinkedHashMap<>(16, .75f, true);
  private static long builds;
  private static long scannedSlots;
  private static long updates;
  private static long indexedExtractions;
  private static long extractionSlots;
  private static long stockHits;

  private final UnlimitedItemStacksResourceHandler handler;
  private final Int2ObjectMap<Slot> slots = new Int2ObjectOpenHashMap<>();
  private final Map<ItemKey, Entry> entries = new LinkedHashMap<>();
  private InventorySummary summary;
  private boolean dirty = true;
  private long usedSpace;

  private StorageCache(UnlimitedItemStacksResourceHandler handler) {
    this.handler = handler;
  }

  static StorageCache get(BaseStorage<?> storage) {
    StorageCache cache = caches.get(storage.getId());
    if (cache == null || cache.handler != storage.getItems()) {
      cache = new StorageCache(storage.getItems());
      caches.put(storage.getId(), cache);
    }
    // 限制保留的 handler 引用数量；被淘汰存储在下次访问时重建。
    // Bound retained handler references; evicted storage rebuilds on its next access.
    if (caches.size() > MAX_CACHES) {
      caches.remove(caches.keySet().iterator().next());
    }
    cache.ensure();
    return cache;
  }

  /**
   * 在服务器线程刷新变化槽位，批量替换则延迟重建。 Refreshes a changed slot on the server thread; bulk replacement defers
   * a full rebuild until the next read.
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
    builds = 0;
    scannedSlots = 0;
    updates = 0;
    indexedExtractions = 0;
    extractionSlots = 0;
    stockHits = 0;
  }

  /** 返回不可变的操作计数快照，仅限服务器线程。 Returns an immutable operation-count snapshot on the server thread. */
  public static Statistics statistics() {
    return new Statistics(
        caches.size(),
        builds,
        scannedSlots,
        updates,
        indexedExtractions,
        extractionSlots,
        stockHits);
  }

  /** 返回诊断计数，不代表耗时。 Returns diagnostic operation counts, which do not measure elapsed time. */
  public static String stats() {
    return statistics().toString();
  }

  private void ensure() {
    if (!dirty) {
      return;
    }
    slots.clear();
    entries.clear();
    usedSpace = 0;
    summary = null;
    builds++;
    for (int index = 0; index < handler.size(); index++) {
      scannedSlots++;
      refresh(index);
    }
    dirty = false;
  }

  private void refresh(int index) {
    Slot old = slots.get(index);
    ItemStack current = handler.getStackInSlot(index);
    long count = current.isEmpty() ? 0 : handler.getAmountAsLong(index);
    updates++;
    if (old != null && count > 0 && old.key.matches(current)) {
      // 常见的数量变化复用键和槽位集合，避免复制组件及重排类型。
      // Reuse the key and slot set for count-only changes, avoiding component copies and
      // reordering.
      long delta = count - old.count;
      if (delta != 0) {
        entries.get(old.key).count += delta;
        usedSpace += delta * old.key.spacePerItem();
        slots.put(index, new Slot(old.key, count));
        summary = null;
      }
      return;
    }
    if (old != null) {
      Entry entry = entries.get(old.key);
      entry.count -= old.count;
      entry.slots.remove(index);
      if (entry.slots.isEmpty()) {
        entries.remove(old.key);
      }
      slots.remove(index);
      usedSpace -= old.count * old.key.spacePerItem();
    }
    if (count > 0) {
      ItemKey key = new ItemKey(current);
      Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
      entry.count += count;
      entry.slots.add(index);
      slots.put(index, new Slot(key, count));
      usedSpace += count * key.spacePerItem();
    }
    if (old != null || count > 0) {
      summary = null;
    }
  }

  /**
   * 返回共享摘要，调用方不得修改；摘要内物品不与索引键共享。 Returns a shared summary that callers must not mutate; its items
   * are independent of index keys.
   */
  public InventorySummary summary() {
    ensure();
    if (summary != null) {
      stockHits++;
      return summary;
    }
    InventorySummary result = new InventorySummary();
    // Create 将达到 INF 的计数视为无限；单次加入不超过 INF，避免其总数溢出。
    // Create treats counts reaching INF as infinite; cap each addition to avoid total overflow.
    entries.forEach(
        (key, entry) -> result.add(key.copyStack(), (int) Math.min(BigItemStack.INF, entry.count)));
    summary = result;
    return summary;
  }

  long count(ItemStack stack) {
    return stack.isEmpty() ? 0 : count(new ItemKey(stack));
  }

  long count(ItemKey key) {
    ensure();
    Entry entry = entries.get(key);
    return entry == null ? 0 : entry.count;
  }

  int occupiedSlots() {
    ensure();
    return slots.size();
  }

  long usedSpace() {
    ensure();
    return usedSpace;
  }

  ItemStack extract(ItemStack requested, int amount, boolean simulate) {
    if (requested.isEmpty() || amount <= 0) {
      return ItemStack.EMPTY;
    }
    ensure();
    indexedExtractions++;
    Entry entry = entries.get(new ItemKey(requested));
    if (entry == null) {
      return ItemStack.EMPTY;
    }
    int count = (int) Math.min(entry.count, Math.min(amount, requested.getMaxStackSize()));
    if (simulate) {
      return requested.copyWithCount(count);
    }
    int firstSlot = entry.slots.firstInt();
    if (slots.get(firstSlot).count >= count) {
      extractionSlots++;
      return handler.extractItem(firstSlot, count, false);
    }
    // 回调会修改槽位集合；仅复制满足本次提取所需的槽位，工作量由提取量限定。
    // Callbacks mutate the slot set; snapshot only enough slots for this bounded extraction.
    IntArrayList selected = new IntArrayList();
    int needed = count;
    for (var iterator = entry.slots.iterator(); iterator.hasNext() && needed > 0; ) {
      int slot = iterator.nextInt();
      selected.add(slot);
      needed -= (int) Math.min(needed, slots.get(slot).count);
    }
    int remaining = count;
    for (int index = 0; index < selected.size() && remaining > 0; index++) {
      extractionSlots++;
      remaining -= handler.extractItem(selected.getInt(index), remaining, false).getCount();
    }
    return requested.copyWithCount(count - remaining);
  }

  List<ItemStack> itemTypes() {
    ensure();
    return entries.keySet().stream().map(ItemKey::copyStack).toList();
  }

  /**
   * 自上次清理起的诊断计数；提取槽位数只统计真实提取。 Diagnostic counts since the last clear; extraction slots count only
   * native, non-simulated extraction calls.
   */
  public record Statistics(
      int caches,
      long builds,
      long scannedSlots,
      long slotUpdates,
      long indexedExtractions,
      long extractionSlots,
      long stockCacheHits) {}

  private static final class Entry {
    private long count;
    private final IntSortedSet slots = new IntAVLTreeSet();
  }

  private record Slot(ItemKey key, long count) {}
}
