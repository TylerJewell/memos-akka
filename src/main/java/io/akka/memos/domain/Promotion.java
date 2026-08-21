package io.akka.memos.domain;

import java.util.List;

/** Moving the best of the working ranked tier into the smaller activation tier — SPEC-001 R19, R20. */
public final class Promotion {

  /** The ceiling MemOS puts on the working ranked tier however large the durable tier is. */
  public static final int WORKING_RANKED_CEILING = 30;

  /** The activation tier's capacity (question-log row 30). */
  public static final int ACTIVATION_CAPACITY = 20;

  /** The number of absent memories a ranked tier reprieves (question-log row 24). */
  public static final int RETENTION_COUNT = 2;

  private Promotion() {}

  /**
   * Rule R20. The working ranked tier is a little larger than the durable tier it watches — by
   * exactly the retention count, so the memories the ranked tier is holding in reprieve have
   * somewhere to sit. A cube with no durable working capacity falls back to the flat ceiling.
   */
  public static int workingRankedCapacity(int durableWorkingCapacity, int retentionCount) {
    if (durableWorkingCapacity == DurableTier.UNBOUNDED) {
      return WORKING_RANKED_CEILING;
    }
    return Math.min(WORKING_RANKED_CEILING, durableWorkingCapacity + retentionCount);
  }

  /**
   * Rule R19: the best of the working tier are offered to the activation tier as an observation,
   * so everything R5 to R11 says about an observation applies to them there — including that the
   * activation tier's own absent memories are reprieved or dropped by the same rule.
   */
  public static RankedTier promote(
      RankedTier working, RankedTier activation, Weights weights, int retentionCount) {
    int howMany =
        activation.capacity() == RankedTier.UNBOUNDED
            ? working.memories().size()
            : activation.capacity();
    List<RankedMemory> best = working.rankedByImportance(weights).stream().limit(howMany).toList();
    return activation.observe(best, retentionCount);
  }
}
