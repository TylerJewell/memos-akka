package io.akka.memos.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.javasdk.testkit.EventSourcedTestKit;
import io.akka.memos.domain.CubeEvent;
import io.akka.memos.domain.RankedMemory;
import io.akka.memos.domain.TierBook;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R23, R25, and the journal the entity actually writes. */
class MemoryCubeEntityTest {

  private static final Instant T0 = Instant.parse("2026-08-20T00:00:00Z");

  private static EventSourcedTestKit<
          io.akka.memos.domain.CubeState, CubeEvent, MemoryCubeEntity> cube() {
    return EventSourcedTestKit.of(MemoryCubeEntity::new);
  }

  private static MemoryCubeEntity.Admit admit(String tier, String key, long seconds) {
    return new MemoryCubeEntity.Admit(tier, key, "text of " + key, T0.plusSeconds(seconds));
  }

  @Test
  void aBlankTierIsRefusedAsADeclaredErrorAndWritesNothing() {
    var testKit = cube();
    var result = testKit.method(MemoryCubeEntity::admit).invoke(admit("  ", "k", 1));
    assertTrue(result.isError());
    assertEquals("tier must not be blank", result.getError());
    assertFalse(result.didPersistEvents());
  }

  @Test
  void aBlankKeyIsRefusedAsADeclaredErrorAndWritesNothing() {
    var testKit = cube();
    var result =
        testKit.method(MemoryCubeEntity::admit).invoke(admit(TierBook.WORKING, "   ", 1));
    assertTrue(result.isError());
    assertEquals("key must not be blank", result.getError());
    assertFalse(result.didPersistEvents());
  }

  @Test
  void anObservationWithoutAKeyIsRefusedAndWritesNothing() {
    var testKit = cube();
    var result = testKit.method(MemoryCubeEntity::observe)
        .invoke(List.of(new RankedMemory("", "text", 0.5, 0, 1)));
    assertTrue(result.isError());
    assertFalse(result.didPersistEvents());
  }

  /** Rule R25: an uncapped tier is never swept, so the cube's own ceiling is what stops it. */
  @Test
  void aCubeAtItsCeilingRefusesTheNextAdmissionRatherThanGrowing() {
    var testKit = cube();
    for (int i = 0; i < MemoryCubeEntity.MAX_MEMORIES_PER_CUBE; i++) {
      testKit.method(MemoryCubeEntity::admit).invoke(admit("SomethingElse", "s" + i, i));
    }
    var refused = testKit.method(MemoryCubeEntity::admit).invoke(admit("SomethingElse", "one-too-many", 1));
    assertTrue(refused.isError());
    assertEquals(
        "cube holds its maximum of " + MemoryCubeEntity.MAX_MEMORIES_PER_CUBE + " memories",
        refused.getError());
    assertFalse(refused.didPersistEvents());
  }

  /** At the ceiling, replacing a memory the cube already holds is still allowed — it adds nothing. */
  @Test
  void aCubeAtItsCeilingStillAcceptsAKeyItAlreadyHolds() {
    var testKit = cube();
    for (int i = 0; i < MemoryCubeEntity.MAX_MEMORIES_PER_CUBE; i++) {
      testKit.method(MemoryCubeEntity::admit).invoke(admit("SomethingElse", "s" + i, i));
    }
    var again = testKit.method(MemoryCubeEntity::admit).invoke(admit("SomethingElse", "s0", 99_999));
    assertFalse(again.isError());
    assertEquals(MemoryCubeEntity.MAX_MEMORIES_PER_CUBE, again.getReply());
  }

  @Test
  void aSweepWithNoTierDueWritesNothingToTheJournal() {
    var testKit = cube();
    testKit.method(MemoryCubeEntity::admit).invoke(admit("UserMemory", "u1", 1));
    var result = testKit.method(MemoryCubeEntity::sweep).invoke();
    assertEquals(List.of(), result.getReply());
    assertFalse(result.didPersistEvents());
  }

  @Test
  void aSweepWithATierDueWritesOneEvent() {
    var testKit = cube();
    for (int i = 0; i < 384; i++) {
      testKit.method(MemoryCubeEntity::admit).invoke(admit("UserMemory", "u" + i, i));
    }
    var result = testKit.method(MemoryCubeEntity::sweep).invoke();
    assertEquals(List.of("UserMemory"), result.getReply());
    assertTrue(result.didPersistEvents());
    assertEquals(new CubeEvent.TiersSwept(), result.getNextEventOfType(CubeEvent.TiersSwept.class));
  }

  @Test
  void oneAdmissionWritesOneEventCarryingWhatWasAdmitted() {
    var testKit = cube();
    var result = testKit.method(MemoryCubeEntity::admit).invoke(admit(TierBook.WORKING, "w1", 5));
    var event = result.getNextEventOfType(CubeEvent.MemoryAdmitted.class);
    assertEquals(TierBook.WORKING, event.tier());
    assertEquals("w1", event.key());
    assertEquals(T0.plusSeconds(5), event.updatedAt());
    assertEquals(1, result.getReply());
  }

  @Test
  void anAdmissionWithNoInstantIsStampedRatherThanRefused() {
    var testKit = cube();
    var before = Instant.now();
    var result = testKit.method(MemoryCubeEntity::admit)
        .invoke(new MemoryCubeEntity.Admit(TierBook.WORKING, "w1", "text", null));
    var event = result.getNextEventOfType(CubeEvent.MemoryAdmitted.class);
    assertFalse(event.updatedAt().isBefore(before));
  }
}
