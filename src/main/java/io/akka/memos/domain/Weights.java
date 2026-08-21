package io.akka.memos.domain;

/**
 * The three weights the importance score combines — SPEC-001 R1, R3.
 *
 * <p>Only vectors of three weights summing to 1 are accepted, which is what MemOS enforces, and
 * the default is the one it ships.
 */
public record Weights(double rerank, double keyword, double observation) {

  public static final Weights DEFAULT = new Weights(0.9, 0.05, 0.05);

  private static final double TOLERANCE = 1e-6;

  public Weights {
    if (Math.abs(rerank + keyword + observation - 1.0) > TOLERANCE) {
      throw new IllegalArgumentException(
          "weights must sum to 1.0, got " + (rerank + keyword + observation));
    }
  }

  public double sum() {
    return rerank + keyword + observation;
  }
}
