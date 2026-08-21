package io.akka.memos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** SPEC-001 R19, R20. */
class PromotionTest {

  private static List<String> keysByImportance(RankedTier tier) {
    return tier.rankedByImportance(Weights.DEFAULT).stream().map(RankedMemory::key).toList();
  }

  @Test
  void theWorkingRankedCapacityIsDerivedFromTheDurableWorkingCapacity() {
    assertEquals(4, Promotion.workingRankedCapacity(2, 2));
    assertEquals(22, Promotion.workingRankedCapacity(20, 2));
    assertEquals(30, Promotion.workingRankedCapacity(28, 2));
    assertEquals(30, Promotion.workingRankedCapacity(29, 2));
    assertEquals(30, Promotion.workingRankedCapacity(1000, 2));
  }

  @Test
  void aTierlessCubeFallsBackToTheFlatCeiling() {
    assertEquals(30, Promotion.workingRankedCapacity(DurableTier.UNBOUNDED, 2));
  }

  @Test
  void promotionCarriesTheBestTwentyOfThirtyWorkingMemories() {
    var working = new RankedTier(RankedTier.UNBOUNDED, List.of()).observe(
        IntStream.range(0, 30)
            .mapToObj(i -> new RankedMemory("w" + i, "text", i / 100.0, 0, 1))
            .toList(),
        0);
    var activation = Promotion.promote(
        working, new RankedTier(20, List.of()), Weights.DEFAULT, 2);

    assertEquals(20, activation.memories().size());
    assertEquals(
        IntStream.range(10, 30).map(i -> 39 - i).mapToObj(i -> "w" + i).toList(),
        keysByImportance(activation));
  }

  @Test
  void aSecondPromotionOfTheSameMemoriesRaisesTheirCountsRatherThanDuplicatingThem() {
    var working = new RankedTier(RankedTier.UNBOUNDED, List.of()).observe(
        IntStream.range(0, 30)
            .mapToObj(i -> new RankedMemory("w" + i, "text", i / 100.0, 0, 1))
            .toList(),
        0);
    var activation = Promotion.promote(working, new RankedTier(20, List.of()), Weights.DEFAULT, 2);
    var again = Promotion.promote(working, activation, Weights.DEFAULT, 2);

    assertEquals(20, again.memories().size());
    assertTrue(again.memories().stream().allMatch(m -> m.observationCount() == 2));
  }

  @Test
  void promotionFromAnEmptyWorkingTierLeavesTheActivationTierRetainedNotEmptied() {
    var activation = Promotion.promote(
        new RankedTier(RankedTier.UNBOUNDED, List.of()).observe(
            List.of(new RankedMemory("a", "text", 0.9, 0, 1),
                    new RankedMemory("b", "text", 0.8, 0, 1)), 0),
        new RankedTier(20, List.of()), Weights.DEFAULT, 2);
    var afterEmpty = Promotion.promote(
        new RankedTier(RankedTier.UNBOUNDED, List.of()), activation, Weights.DEFAULT, 2);

    assertEquals(List.of("a", "b"), afterEmpty.memories().stream().map(RankedMemory::key).toList());
    assertTrue(afterEmpty.memories().stream().allMatch(m -> m.rerankScore() == 0.0));
  }
}
