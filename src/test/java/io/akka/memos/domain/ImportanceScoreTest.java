package io.akka.memos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** SPEC-001 R1–R4. */
class ImportanceScoreTest {

  private static RankedMemory ranked(double rerank, double keyword, int observations) {
    return new RankedMemory("k", "text", rerank, keyword, observations);
  }

  @Test
  void rerankScoreIsWorthFarMoreThanTheOtherTwoInputs() {
    var w = Weights.DEFAULT;
    assertEquals(0.9025, ranked(1.0, 0, 1).importance(w), 1e-12);
    assertEquals(0.005, ranked(0, 1.0, 1).importance(w), 1e-12);
    assertEquals(0.005, ranked(0, 0, 2).importance(w), 1e-12);
    assertEquals(0.0025, ranked(0, 0, 1).importance(w), 1e-12);
  }

  @Test
  void bothCappedTermsSaturate() {
    var w = Weights.DEFAULT;
    assertEquals(0.2525, ranked(0, 1e9, 1).importance(w), 1e-12);
    assertEquals(0.1, ranked(0, 0, 1_000_000_000).importance(w), 1e-12);
  }

  @Test
  void noKeywordScoreHoweverLargeBeatsARerankScoreOfOne() {
    var w = Weights.DEFAULT;
    assertTrue(ranked(0, 1e12, 1).importance(w) < ranked(1.0, 0, 1).importance(w));
  }

  @Test
  void aMaxedKeywordScoreTiesARerankScoreOfAboutTwoSevenSeven() {
    var w = Weights.DEFAULT;
    double maxed = ranked(0, 1e12, 1).importance(w);
    assertEquals(maxed, ranked(0.277778, 0, 1).importance(w), 1e-5);
  }

  @Test
  void anUninitialisedScoreCountsAsZeroNotAsMinusOne() {
    var w = Weights.DEFAULT;
    var uninitialised = new RankedMemory(
        "k", "text", RankedMemory.NOT_INITIALIZED, RankedMemory.NOT_INITIALIZED, 1);
    assertEquals(ranked(0, 0, 1).importance(w), uninitialised.importance(w), 1e-12);
  }

  @Test
  void aWeightVectorThatDoesNotSumToOneIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> new Weights(0.5, 0.5, 0.5));
    assertThrows(IllegalArgumentException.class, () -> new Weights(0.5, 0.4, 0.0));
    assertThrows(IllegalArgumentException.class, () -> new Weights(0.0, 0.0, 0.0));
  }

  @Test
  void aWeightVectorWithinRoundingOfOneIsAccepted() {
    assertEquals(1.0, new Weights(0.9, 0.05, 0.05).sum(), 1e-9);
    assertEquals(0.9025, ranked(1.0, 0, 1).importance(new Weights(0.9, 0.05, 0.05)), 1e-12);
  }

  @Test
  void computingTheScoreLeavesTheMemoryUnchanged() {
    var before = ranked(0.5, 3, 2);
    var score = before.importance(Weights.DEFAULT);
    var after = ranked(0.5, 3, 2);
    assertEquals(after, before);
    assertEquals(score, before.importance(Weights.DEFAULT), 1e-12);
    assertNotEquals(0.0, score);
  }
}
