package io.akka.memos.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * A tier that evicts by recency — SPEC-001 R12–R14.
 *
 * <p>The memories are held most-recent-first, so the tier reads in the order eviction works
 * through it, and a sweep is a truncation.
 */
public record DurableTier(String name, int capacity, List<Memory> memories) {

  public static final int UNBOUNDED = -1;

  /** Rule R15: a tier at or above this fraction of its capacity is swept. */
  public static final double SWEEP_THRESHOLD = 0.80;

  private static final Comparator<Memory> MOST_RECENT_FIRST =
      Comparator.comparing(Memory::updatedAt).thenComparingLong(Memory::admission).reversed();

  public DurableTier {
    memories = List.copyOf(memories);
  }

  public static DurableTier empty(String name, int capacity) {
    return new DurableTier(name, capacity, List.of());
  }

  /**
   * Admits without sweeping. Readmitting a key replaces the memory it names.
   *
   * <p>The list is already in eviction order, so the admission is placed rather than the whole
   * tier re-sorted — a tier of 1500 is admitted into on every write, and re-sorting it each time
   * is the difference between a copy and an n log n.
   */
  public DurableTier admit(Memory memory) {
    var next = new ArrayList<>(memories);
    int existing = indexOfKey(next, memory.key());
    if (existing >= 0) {
      next.remove(existing);
    }
    int at = Collections.binarySearch(next, memory, MOST_RECENT_FIRST);
    next.add(at >= 0 ? at : -(at + 1), memory);
    return new DurableTier(name, capacity, next);
  }

  private static int indexOfKey(List<Memory> memories, String key) {
    for (int i = 0; i < memories.size(); i++) {
      if (memories.get(i).key().equals(key)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Rule R12: keep exactly the {@code capacity} most recently updated, discard the rest. Rule R14
   * decides a tie on {@code updatedAt} by admission order, the later admission counting as more
   * recent.
   */
  public DurableTier sweep() {
    if (capacity == UNBOUNDED || memories.size() <= capacity) {  // R16
      return this;
    }
    return new DurableTier(name, capacity, memories.subList(0, capacity));
  }

  /** Rule R15: whether a sweep is due, which is a question about size and never about what to keep. */
  public boolean atOrOverSweepThreshold() {
    return capacity != UNBOUNDED && memories.size() >= (int) (capacity * SWEEP_THRESHOLD);
  }

  /**
   * Rule R18: admit the first {@code capacity} offered, in the order offered, replacing whatever
   * the tier held. The offered memories are not ranked — that is the caller's job.
   */
  public DurableTier replaceWith(List<Memory> offered) {
    int howMany = capacity == UNBOUNDED ? offered.size() : Math.min(capacity, offered.size());
    return new DurableTier(
        name, capacity, offered.stream().limit(howMany).sorted(MOST_RECENT_FIRST).toList());
  }
}
