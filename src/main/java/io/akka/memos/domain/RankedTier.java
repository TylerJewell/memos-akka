package io.akka.memos.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A tier whose contents are decided by importance rather than by recency — SPEC-001 R5–R11.
 *
 * <p>The memories are held in the order R10 leaves them, because that order is the tie-break:
 * R11 makes it a rule rather than a property of a stable sort.
 */
public record RankedTier(int capacity, List<RankedMemory> memories) {

  public static final int UNBOUNDED = -1;

  /** A memory paired with its score, so a ranking computes each score once rather than per comparison. */
  private record Scored(RankedMemory memory, double importance) {}

  public RankedTier {
    memories = List.copyOf(memories);
  }

  public static RankedTier empty(int capacity) {
    return new RankedTier(capacity, List.of());
  }

  /**
   * One observation: rules R5 through R10, in that order. The order is the policy — retention runs
   * before the capacity cut and zeroes the scores of what it saves, so a retained memory is the
   * first thing the capacity cut discards.
   */
  public RankedTier observe(List<RankedMemory> observed, int retentionCount) {
    if (retentionCount < 0) {
      throw new IllegalArgumentException("retentionCount must not be negative");
    }

    // Insertion-ordered: a re-observed memory keeps its position, a new one goes to the end.
    var next = new LinkedHashMap<String, RankedMemory>();
    memories.forEach(m -> next.put(m.key(), m));
    for (RankedMemory observation : observed) {
      var existing = next.get(observation.key());
      next.put(
          observation.key(),
          existing == null ? observation : existing.observedAgain(observation));  // R6 / R5
    }

    var observedKeys = observed.stream().map(RankedMemory::key).collect(Collectors.toSet());
    var absent = next.values().stream().filter(m -> !observedKeys.contains(m.key())).toList();
    var retainedKeys = topKeys(absent, Math.min(retentionCount, absent.size()));  // R7

    next.keySet().removeIf(key -> !observedKeys.contains(key) && !retainedKeys.contains(key));
    retainedKeys.forEach(key -> next.put(key, next.get(key).reset()));            // R8

    if (capacity == UNBOUNDED) {
      return new RankedTier(capacity, List.copyOf(next.values()));
    }
    // R10: a tier with a capacity is left in importance order whether or not anything was cut,
    // so the tie-break R11 applies next round reads this round's ranking rather than arrival.
    return new RankedTier(capacity, rank(next.values(), Weights.DEFAULT, capacity));
  }

  /**
   * Rule R11: most important first, and among equals the one earlier in the tier's list. A stable
   * sort is what gives the second half; R10 decides what that list order is.
   */
  public List<RankedMemory> rankedByImportance(Weights weights) {
    return rank(memories, weights, memories.size());
  }

  private static List<RankedMemory> rank(Collection<RankedMemory> items, Weights weights, int howMany) {
    var scored = new ArrayList<Scored>(items.size());
    items.forEach(m -> scored.add(new Scored(m, m.importance(weights))));
    scored.sort(Comparator.comparingDouble(Scored::importance).reversed());
    var ranked = new ArrayList<RankedMemory>(Math.min(howMany, scored.size()));
    for (int i = 0; i < Math.min(howMany, scored.size()); i++) {
      ranked.add(scored.get(i).memory());
    }
    return List.copyOf(ranked);
  }

  private static Set<String> topKeys(Collection<RankedMemory> items, int howMany) {
    return rank(items, Weights.DEFAULT, howMany).stream()
        .map(RankedMemory::key)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
