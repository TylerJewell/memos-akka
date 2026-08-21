package io.akka.memos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R5–R11. */
class RankedTierTest {

  private static RankedMemory obs(String key, double rerank) {
    return new RankedMemory(key, "text of " + key, rerank, 0, 1);
  }

  private static RankedMemory obs(String key, double rerank, double keyword) {
    return new RankedMemory(key, "text of " + key, rerank, keyword, 1);
  }

  private static RankedTier tier(int capacity, RankedMemory... held) {
    return new RankedTier(capacity, List.of(held));
  }

  private static List<String> keys(RankedTier t) {
    return t.memories().stream().map(RankedMemory::key).toList();
  }

  @Test
  void anObservedMemoryHasItsCountRaisedAndItsScoresReplaced() {
    var after = tier(RankedTier.UNBOUNDED, obs("seen", 0.4)).observe(List.of(obs("seen", 0.1, 7)), 0);
    var seen = after.memories().get(0);
    assertEquals(2, seen.observationCount());
    assertEquals(0.1, seen.rerankScore(), 1e-12);
    assertEquals(7.0, seen.keywordScore(), 1e-12);
  }

  @Test
  void anObservationCountKeepsClimbingAcrossRounds() {
    var t = tier(RankedTier.UNBOUNDED, obs("seen", 0.4));
    for (int i = 0; i < 3; i++) t = t.observe(List.of(obs("seen", 0.1, 7)), 0);
    assertEquals(4, t.memories().get(0).observationCount());
  }

  @Test
  void aMemoryNotYetInTheTierIsAddedWithACountOfOne() {
    var after = tier(RankedTier.UNBOUNDED).observe(List.of(obs("new", 0.3)), 0);
    assertEquals(List.of("new"), keys(after));
    assertEquals(1, after.memories().get(0).observationCount());
  }

  @Test
  void anAbsentMemoryIsDroppedWhenNothingIsRetained() {
    var after = tier(RankedTier.UNBOUNDED, obs("stale", 0.9), obs("fresh", 0.1))
        .observe(List.of(obs("fresh", 0.1)), 0);
    assertEquals(List.of("fresh"), keys(after));
  }

  @Test
  void theBestOfTheAbsentSetAreRetainedAndTheRestDropped() {
    var after = tier(RankedTier.UNBOUNDED,
            obs("stale-high", 0.9), obs("stale-mid", 0.5), obs("stale-low", 0.1), obs("fresh", 0.2))
        .observe(List.of(obs("fresh", 0.2)), 2);
    assertEquals(List.of("stale-high", "stale-mid", "fresh"), keys(after));
  }

  @Test
  void aRetainedMemoryHasItsScoresAndCountReset() {
    var after = tier(RankedTier.UNBOUNDED, obs("stale", 0.9), obs("fresh", 0.2))
        .observe(List.of(obs("fresh", 0.2)), 1);
    var retained = after.memories().stream().filter(m -> m.key().equals("stale")).findFirst().orElseThrow();
    assertEquals(0.0, retained.rerankScore(), 1e-12);
    assertEquals(0.0, retained.keywordScore(), 1e-12);
    assertEquals(1, retained.observationCount());
  }

  @Test
  void retentionIsNotACountdownAndHoldsAcrossRepeatedAbsence() {
    var t = tier(RankedTier.UNBOUNDED, obs("held", 0.9), obs("fresh", 0.2));
    for (int i = 0; i < 3; i++) t = t.observe(List.of(obs("fresh", 0.2)), 1);
    assertTrue(keys(t).contains("held"));
    assertEquals(4, t.memories().stream()
        .filter(m -> m.key().equals("fresh")).findFirst().orElseThrow().observationCount());
  }

  @Test
  void aRetentionCountLargerThanTheAbsentSetIsTreatedAsItsSize() {
    var after = tier(RankedTier.UNBOUNDED, obs("stale", 0.9), obs("fresh", 0.2))
        .observe(List.of(obs("fresh", 0.2)), 99);
    assertEquals(List.of("stale", "fresh"), keys(after));
  }

  @Test
  void aNegativeRetentionCountIsRejected() {
    var t = tier(RankedTier.UNBOUNDED, obs("a", 0.1));
    assertThrows(IllegalArgumentException.class, () -> t.observe(List.of(obs("a", 0.1)), -1));
  }

  @Test
  void theCapacityCutKeepsTheMostImportant() {
    var after = tier(2).observe(List.of(obs("x", 0.1), obs("y", 0.9), obs("z", 0.5)), 0);
    assertEquals(List.of("y", "z"), keys(after));
  }

  @Test
  void theCapacityCutDiscardsRetainedMemoriesFirstBecauseRetentionZeroedThem() {
    var after = tier(3, obs("keep-a", 0.9), obs("keep-b", 0.8), obs("drop-c", 0.7), obs("drop-d", 0.6))
        .observe(List.of(obs("new-e", 0.95), obs("keep-a", 0.9)), 2);
    assertEquals(List.of("new-e", "keep-a", "keep-b"), keys(after));
  }

  @Test
  void arrivalOrderDecidesWhichOfTwoEquallyImportantMemoriesSurvives() {
    var forward = tier(2).observe(List.of(obs("p", 0.5), obs("q", 0.5), obs("r", 0.5)), 0);
    var backward = tier(2).observe(List.of(obs("r", 0.5), obs("q", 0.5), obs("p", 0.5)), 0);
    assertEquals(List.of("p", "q"), keys(forward));
    assertEquals(List.of("r", "q"), keys(backward));
  }

  @Test
  void aTierWithACapacityIsLeftInImportanceOrderEvenWhenNothingWasCut() {
    var after = tier(10).observe(List.of(obs("x", 0.1), obs("y", 0.9), obs("z", 0.5)), 0);
    assertEquals(List.of("y", "z", "x"), keys(after));
  }

  @Test
  void aTierWithNoCapacityKeepsArrivalOrder() {
    var after = tier(RankedTier.UNBOUNDED)
        .observe(List.of(obs("x", 0.1), obs("y", 0.9), obs("z", 0.5)), 0);
    assertEquals(List.of("x", "y", "z"), keys(after));
  }

  @Test
  void anUnboundedTierKeepsEverythingObserved() {
    var after = tier(RankedTier.UNBOUNDED)
        .observe(List.of(obs("a", 0.1), obs("b", 0.2), obs("c", 0.3)), 0);
    assertEquals(3, after.memories().size());
  }
}
