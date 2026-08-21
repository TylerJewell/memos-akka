package io.akka.memos.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R21, R22. */
class QueryWindowTest {

  @Test
  void afullWindowDropsTheOldestRatherThanRefusingTheNewest() {
    var window = QueryWindow.of(3);
    for (String q : List.of("a", "b", "c", "d", "e")) window = window.record(q);
    assertEquals(List.of("c", "d", "e"), window.queries());
  }

  @Test
  void aWindowOfZeroIsUnbounded() {
    var window = QueryWindow.of(0);
    for (int i = 0; i < 500; i++) window = window.record("q" + i);
    assertEquals(500, window.queries().size());
  }

  @Test
  void aNegativeWindowIsUnbounded() {
    var window = QueryWindow.of(-4);
    for (int i = 0; i < 50; i++) window = window.record("q" + i);
    assertEquals(50, window.queries().size());
  }

  @Test
  void theKeywordScoreCountsOccurrencesAcrossTheWindow() {
    var window = QueryWindow.of(5)
        .record("alpha beta")
        .record("beta gamma")
        .record("beta");
    assertEquals(3, window.occurrencesOf("beta"));
    assertEquals(1, window.occurrencesOf("alpha"));
    assertEquals(0, window.occurrencesOf("delta"));
  }

  @Test
  void aWordThatFellOutOfTheWindowStopsCounting() {
    var window = QueryWindow.of(2)
        .record("alpha")
        .record("beta")
        .record("gamma");
    assertEquals(0, window.occurrencesOf("alpha"));
    assertEquals(1, window.occurrencesOf("beta"));
  }
}
