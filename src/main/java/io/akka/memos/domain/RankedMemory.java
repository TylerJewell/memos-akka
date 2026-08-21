package io.akka.memos.domain;

/**
 * A memory in a ranked tier, carrying the three numbers the importance score combines —
 * SPEC-001 §2, R1, R2.
 *
 * @param key what identifies this memory across observations; two memories with the same key are
 *     the same memory
 * @param rerankScore how well the memory matched, supplied from outside this capability
 * @param keywordScore how often the memory's keywords occurred in the recent query window
 * @param observationCount how many times this memory has been seen in an observation
 */
public record RankedMemory(
    String key, String text, double rerankScore, double keywordScore, int observationCount) {

  /** The value MemOS writes into a score field it has not filled in yet. */
  public static final double NOT_INITIALIZED = -1.0;

  private static final double KEYWORD_CAP = 5.0;
  private static final double OBSERVATION_CAP = 2.0;

  public RankedMemory {
    if (observationCount < 1) {
      throw new IllegalArgumentException("observationCount must be at least 1");
    }
  }

  /**
   * Rule R1. The keyword and observation terms are multiplied by their weight twice — once inside
   * the cap and once in the sum — so a weight of 0.05 lands as 0.0025 and the two terms saturate
   * at 0.2525 and 0.1 against an unbounded rerank term. That arithmetic is what the source's
   * ranking means, so it is reproduced rather than corrected (SPEC-001 §4.4).
   *
   * <p>Rule R4: this computes and returns. It writes nothing back.
   */
  public double importance(Weights weights) {
    double rerank = atFloor(rerankScore);
    double keyword = atFloor(keywordScore);
    double cappedKeyword = Math.min(keyword * weights.keyword(), KEYWORD_CAP);
    double cappedObservation = Math.min(observationCount * weights.observation(), OBSERVATION_CAP);
    return rerank * weights.rerank()
        + cappedKeyword * weights.keyword()
        + cappedObservation * weights.observation();
  }

  /** Rule R2: an uninitialised score contributes nothing rather than subtracting. */
  private static double atFloor(double score) {
    return score == NOT_INITIALIZED ? 0.0 : score;
  }

  /** Rule R8: kept, but at the bottom of the ranking. */
  public RankedMemory reset() {
    return new RankedMemory(key, text, 0.0, 0.0, 1);
  }

  /** Rule R5: the count rises, the scores are replaced. */
  public RankedMemory observedAgain(RankedMemory observation) {
    return new RankedMemory(
        key, text, observation.rerankScore(), observation.keywordScore(), observationCount + 1);
  }
}
