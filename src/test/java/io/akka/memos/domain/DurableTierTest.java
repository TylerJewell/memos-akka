package io.akka.memos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R12–R14. */
class DurableTierTest {

  private static final Instant T0 = Instant.parse("2026-08-20T00:00:00Z");

  private static DurableTier admit(DurableTier tier, String key, long secondsAfterT0, long seq) {
    return tier.admit(new Memory(key, "text of " + key, T0.plusSeconds(secondsAfterT0), seq));
  }

  private static List<String> keys(DurableTier t) {
    return t.memories().stream().map(Memory::key).toList();
  }

  @Test
  void aTierOverCapacityKeepsExactlyTheMostRecentlyUpdated() {
    var tier = new DurableTier("WorkingMemory", 5, List.of());
    for (int i = 0; i < 8; i++) tier = admit(tier, "n" + i, i * 60, i);
    var swept = tier.sweep();
    assertEquals(List.of("n7", "n6", "n5", "n4", "n3"), keys(swept));
  }

  @Test
  void aTierUnderCapacityLosesNothingToASweep() {
    var tier = new DurableTier("WorkingMemory", 20, List.of());
    for (int i = 0; i < 3; i++) tier = admit(tier, "n" + i, i * 60, i);
    assertEquals(3, tier.sweep().memories().size());
  }

  @Test
  void aCapacityOfZeroEmptiesTheTier() {
    var tier = new DurableTier("WorkingMemory", 0, List.of());
    for (int i = 0; i < 3; i++) tier = admit(tier, "n" + i, i * 60, i);
    assertEquals(List.of(), keys(tier.sweep()));
  }

  @Test
  void anOlderUpdateReadmittedLaterIsStillOlder() {
    var tier = new DurableTier("WorkingMemory", 2, List.of());
    tier = admit(tier, "newest", 300, 0);
    tier = admit(tier, "middle", 200, 1);
    tier = admit(tier, "oldest", 100, 2);
    assertEquals(List.of("newest", "middle"), keys(tier.sweep()));
  }

  @Test
  void amongMemoriesSharingOneUpdateInstantTheLaterAdmissionCountsAsMoreRecent() {
    var tier = new DurableTier("WorkingMemory", 2, List.of());
    for (int i = 0; i < 6; i++) tier = admit(tier, "t" + i, 0, i);
    assertEquals(List.of("t5", "t4"), keys(tier.sweep()));
  }

  @Test
  void thatTieBreakDoesNotDependOnWhichOrderTheKeysWereNamed() {
    var forward = new DurableTier("WorkingMemory", 2, List.of());
    var backward = new DurableTier("WorkingMemory", 2, List.of());
    List<String> names = List.of("t0", "t1", "t2", "t3", "t4", "t5");
    long seq = 0;
    for (String n : names) forward = admit(forward, n, 0, seq++);
    seq = 0;
    for (String n : names.reversed()) backward = admit(backward, n, 0, seq++);
    assertEquals(List.of("t5", "t4"), keys(forward.sweep()));
    assertEquals(List.of("t0", "t1"), keys(backward.sweep()));
    assertTrue(forward.sweep().memories().size() == backward.sweep().memories().size());
  }

  @Test
  void readmittingAKeyReplacesItRatherThanDuplicatingIt() {
    var tier = new DurableTier("WorkingMemory", 5, List.of());
    tier = admit(tier, "k", 100, 0);
    tier = admit(tier, "k", 900, 1);
    assertEquals(1, tier.memories().size());
    assertEquals(T0.plusSeconds(900), tier.memories().get(0).updatedAt());
  }

  @Test
  void aTierWithNoCapacityIsNeverCutByASweep() {
    var tier = new DurableTier("SomethingElse", DurableTier.UNBOUNDED, List.of());
    for (int i = 0; i < 500; i++) tier = admit(tier, "n" + i, i, i);
    assertEquals(500, tier.sweep().memories().size());
  }
}
