package io.akka.memos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** SPEC-001 R13, R15–R18. */
class TierBookTest {

  private static final Instant T0 = Instant.parse("2026-08-20T00:00:00Z");

  private static TierBook fill(TierBook book, String tier, int count) {
    for (int i = 0; i < count; i++) {
      book = book.admitWithoutSweeping(tier, tier + "-" + i, "text " + i, T0.plusSeconds(i));
    }
    return book;
  }

  @Test
  void theFourDefaultTiersCarryTheLimitsTheSourceShips() {
    var book = TierBook.withDefaults();
    assertEquals(20, book.capacityOf("WorkingMemory"));
    assertEquals(1500, book.capacityOf("LongTermMemory"));
    assertEquals(1500, book.capacityOf("RawFileMemory"));
    assertEquals(480, book.capacityOf("UserMemory"));
  }

  @Test
  void aTierBelowEightyPercentIsNotSwept() {
    var book = fill(TierBook.withDefaults(), "UserMemory", 379);
    var swept = book.sweepTiersAtOrOverThreshold();
    assertEquals(List.of(), swept.sweptTiers());
    assertEquals(379, swept.book().sizeOf("UserMemory"));
  }

  @Test
  void aTierAtEightyPercentIsSweptAndLosesNothing() {
    var book = fill(TierBook.withDefaults(), "UserMemory", 384);
    var swept = book.sweepTiersAtOrOverThreshold();
    assertEquals(List.of("UserMemory"), swept.sweptTiers());
    assertEquals(384, swept.book().sizeOf("UserMemory"));
  }

  @Test
  void aTierOverItsCapacityIsSweptDownToIt() {
    var book = fill(TierBook.withDefaults(), "UserMemory", 600);
    var swept = book.sweepTiersAtOrOverThreshold();
    assertEquals(List.of("UserMemory"), swept.sweptTiers());
    assertEquals(480, swept.book().sizeOf("UserMemory"));
  }

  @Test
  void sweepingOneTierLeavesTheOthersAlone() {
    var book = fill(fill(TierBook.withDefaults(), "WorkingMemory", 40), "LongTermMemory", 12);
    var swept = book.sweepTiersAtOrOverThreshold().book();
    assertEquals(20, swept.sizeOf("WorkingMemory"));
    assertEquals(12, swept.sizeOf("LongTermMemory"));
  }

  @Test
  void aTierWithNoCapacityIsNeverSweptHoweverLargeItGets() {
    var book = fill(TierBook.withDefaults(), "SomethingElse", 5000);
    var swept = book.sweepTiersAtOrOverThreshold();
    assertEquals(List.of(), swept.sweptTiers());
    assertEquals(5000, swept.book().sizeOf("SomethingElse"));
  }

  @Test
  void theWorkingTierIsSweptOnEveryAdmissionWithNoThreshold() {
    var book = TierBook.withDefaults();
    for (int i = 0; i < 100; i++) {
      book = book.admit("WorkingMemory", "w" + i, "text " + i, T0.plusSeconds(i));
      assertTrue(book.sizeOf("WorkingMemory") <= 20);
    }
    assertEquals(20, book.sizeOf("WorkingMemory"));
  }

  @Test
  void admittingToAnotherTierDoesNotSweepItBelowTheThreshold() {
    var book = TierBook.withDefaults();
    for (int i = 0; i < 100; i++) {
      book = book.admit("UserMemory", "u" + i, "text " + i, T0.plusSeconds(i));
    }
    assertEquals(100, book.sizeOf("UserMemory"));
  }

  @Test
  void replacingTheWorkingTierAdmitsTheFirstTwentyOfferedInTheOrderOffered() {
    var offered = IntStream.range(0, 50)
        .mapToObj(i -> new TierBook.Offered("m" + i, "text " + i))
        .toList();
    var book = TierBook.withDefaults().replaceWorkingMemory(offered, T0);
    assertEquals(20, book.sizeOf("WorkingMemory"));
    assertEquals(
        IntStream.range(0, 20).mapToObj(i -> "m" + i).toList(),
        book.memoriesOf("WorkingMemory").stream().map(Memory::key).sorted(
            (a, b) -> Integer.compare(Integer.parseInt(a.substring(1)), Integer.parseInt(b.substring(1))))
            .toList());
  }

  @Test
  void replacementDoesNotRankTheOfferedMemories() {
    var offered = List.of(
        new TierBook.Offered("last-but-best", "text"),
        new TierBook.Offered("first", "text"));
    var book = new TierBook(java.util.Map.of("WorkingMemory", new DurableTier("WorkingMemory", 1, List.of())), 0)
        .replaceWorkingMemory(offered, T0);
    assertEquals(List.of("last-but-best"), book.memoriesOf("WorkingMemory").stream().map(Memory::key).toList());
  }
}
