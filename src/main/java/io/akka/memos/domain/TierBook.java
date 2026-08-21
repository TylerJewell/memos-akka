package io.akka.memos.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All of a memory cube's durable tiers, and the rule for when each is swept — SPEC-001 R13,
 * R15–R18.
 *
 * <p>{@code nextAdmission} is the source of the strictly increasing admission number that breaks a
 * tie on {@code updatedAt} (SPEC-001 R14, §4.2). It counts across every tier, so no two memories
 * in one cube ever share one.
 */
public record TierBook(Map<String, DurableTier> tiers, long nextAdmission) {

  public static final String WORKING = "WorkingMemory";

  /** The four limits MemOS ships (question-log row 8). */
  public static final Map<String, Integer> DEFAULT_CAPACITIES =
      Map.of(WORKING, 20, "LongTermMemory", 1500, "RawFileMemory", 1500, "UserMemory", 480);

  /** What the caller offers a tier: a key and its text. The instant and the admission are ours. */
  public record Offered(String key, String text) {}

  /** Which tiers a sweep actually looked at, so a caller can tell "not due" from "nothing to cut". */
  public record SweepResult(TierBook book, List<String> sweptTiers) {}

  public TierBook {
    tiers = Map.copyOf(tiers);
  }

  public static TierBook withDefaults() {
    var tiers = new LinkedHashMap<String, DurableTier>();
    DEFAULT_CAPACITIES.forEach((name, capacity) -> tiers.put(name, DurableTier.empty(name, capacity)));
    return new TierBook(tiers, 0);
  }

  public int capacityOf(String tier) {
    var held = tiers.get(tier);
    return held == null ? DurableTier.UNBOUNDED : held.capacity();
  }

  public int sizeOf(String tier) {
    return memoriesOf(tier).size();
  }

  public List<Memory> memoriesOf(String tier) {
    var held = tiers.get(tier);
    return held == null ? List.of() : held.memories();
  }

  /**
   * Admits into a tier and, per rule R17, sweeps the working tier every time — the working tier
   * alone has no threshold. Every other tier waits for {@link #sweepTiersAtOrOverThreshold()}.
   */
  public TierBook admit(String tier, String key, String text, Instant updatedAt) {
    var book = admitWithoutSweeping(tier, key, text, updatedAt);
    if (!WORKING.equals(tier)) {
      return book;
    }
    var next = new LinkedHashMap<>(book.tiers);
    next.put(WORKING, next.get(WORKING).sweep());
    return new TierBook(next, book.nextAdmission);
  }

  /** A tier that is not named in the capacities is created without one and is never swept (R16). */
  public TierBook admitWithoutSweeping(String tier, String key, String text, Instant updatedAt) {
    var next = new LinkedHashMap<>(tiers);
    var held = next.getOrDefault(tier, DurableTier.empty(tier, DurableTier.UNBOUNDED));
    next.put(tier, held.admit(new Memory(key, text, updatedAt, nextAdmission)));
    return new TierBook(next, nextAdmission + 1);
  }

  /**
   * Rule R15: sweep every tier at or above 80% of its capacity, keeping {@code capacity} memories.
   * A tier between those two numbers is therefore swept and loses nothing — the threshold decides
   * when to look, never what to keep.
   *
   * <p>Unlike the source, the size read here is the tier's actual size rather than a count cached
   * by a previous refresh (SPEC-001 §4.5).
   */
  public SweepResult sweepTiersAtOrOverThreshold() {
    var next = new LinkedHashMap<>(tiers);
    var swept = new java.util.ArrayList<String>();
    tiers.forEach((name, tier) -> {
      if (tier.atOrOverSweepThreshold()) {
        next.put(name, tier.sweep());
        swept.add(name);
      }
    });
    swept.sort(String::compareTo);
    return new SweepResult(new TierBook(next, nextAdmission), List.copyOf(swept));
  }

  /**
   * Rule R18: replace the working tier with the first {@code capacity} memories offered, in the
   * order offered. They are not ranked here.
   */
  public TierBook replaceWorkingMemory(List<Offered> offered, Instant updatedAt) {
    long admission = nextAdmission;
    var memories = new java.util.ArrayList<Memory>();
    for (Offered item : offered) {
      memories.add(new Memory(item.key(), item.text(), updatedAt, admission++));
    }
    var next = new LinkedHashMap<>(tiers);
    var working = next.getOrDefault(WORKING, DurableTier.empty(WORKING, DEFAULT_CAPACITIES.get(WORKING)));
    next.put(WORKING, working.replaceWith(memories));
    return new TierBook(next, admission);
  }
}
