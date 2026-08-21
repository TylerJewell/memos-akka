package io.akka.memos.domain;

import java.util.List;
import java.util.Map;

/**
 * One memory cube: its durable tiers, its two ranked tiers and its query window — SPEC-001 §2.
 *
 * <p>The working ranked tier's capacity is derived from the durable working tier's rather than
 * configured (R20), so the two are set up together and never drift apart.
 */
public record CubeState(
    TierBook tiers, RankedTier workingRanked, RankedTier activationRanked, QueryWindow queries) {

  /** The window MemOS reads keyword counts over (its `context_window_size`). */
  public static final int QUERY_WINDOW_SIZE = 5;

  public static CubeState initial() {
    var tiers = TierBook.withDefaults();
    return new CubeState(
        tiers,
        RankedTier.empty(
            Promotion.workingRankedCapacity(
                tiers.capacityOf(TierBook.WORKING), Promotion.RETENTION_COUNT)),
        RankedTier.empty(Promotion.ACTIVATION_CAPACITY),
        QueryWindow.of(QUERY_WINDOW_SIZE));
  }

  /** Every memory the cube holds across every tier — what the per-cube ceiling counts (R25). */
  public int totalMemories() {
    return tiers.tiers().values().stream().mapToInt(t -> t.memories().size()).sum();
  }

  public CubeState onEvent(CubeEvent event) {
    return switch (event) {
      case CubeEvent.MemoryAdmitted e ->
          withTiers(tiers.admit(e.tier(), e.key(), e.text(), e.updatedAt()));
      case CubeEvent.TiersSwept ignored ->
          withTiers(tiers.sweepTiersAtOrOverThreshold().book());
      case CubeEvent.WorkingMemoryReplaced e ->
          withTiers(tiers.replaceWorkingMemory(e.offered(), e.updatedAt()));
      case CubeEvent.MemoriesObserved e ->
          new CubeState(
              tiers,
              workingRanked.observe(scored(e.observed()), Promotion.RETENTION_COUNT),
              activationRanked,
              queries);
      case CubeEvent.Promoted ignored ->
          new CubeState(
              tiers,
              workingRanked,
              Promotion.promote(
                  workingRanked, activationRanked, Weights.DEFAULT, Promotion.RETENTION_COUNT),
              queries);
      case CubeEvent.QueryRecorded e ->
          new CubeState(tiers, workingRanked, activationRanked, queries.record(e.query()));
    };
  }

  /**
   * The keyword term is counted here rather than supplied, because it is a fact about the query
   * window this cube holds. The rerank term arrives from outside and is left alone (SPEC-001 §1).
   *
   * <p>The window is counted once for the whole observation rather than once per memory: an
   * observation carries up to a tier's worth of memories and the window does not change between
   * them.
   */
  private List<RankedMemory> scored(List<RankedMemory> observed) {
    var counts = queries.wordCounts();
    return observed.stream()
        .map(m -> new RankedMemory(
            m.key(),
            m.text(),
            m.rerankScore(),
            keywordScoreFor(m.text(), counts),
            m.observationCount()))
        .toList();
  }

  private static double keywordScoreFor(String text, Map<String, Long> windowCounts) {
    return QueryWindow.wordsOf(text).stream()
        .mapToLong(word -> windowCounts.getOrDefault(word, 0L))
        .sum();
  }

  private CubeState withTiers(TierBook next) {
    return new CubeState(next, workingRanked, activationRanked, queries);
  }
}
