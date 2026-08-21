package io.akka.memos.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The bounded window of recent queries the keyword term of the importance score is counted over —
 * SPEC-001 R21, R22.
 */
public record QueryWindow(int size, List<String> queries) {

  public QueryWindow {
    queries = List.copyOf(queries);
  }

  /** A size of 0 or below means unbounded, which is what MemOS's bounded queue does (row 32). */
  public static QueryWindow of(int size) {
    return new QueryWindow(size, List.of());
  }

  private boolean unbounded() {
    return size <= 0;
  }

  /** Rule R21: a full window drops the oldest. It never refuses the newest. */
  public QueryWindow record(String query) {
    var next = new ArrayList<>(queries);
    next.add(query);
    if (!unbounded() && next.size() > size) {
      next = new ArrayList<>(next.subList(next.size() - size, next.size()));
    }
    return new QueryWindow(size, next);
  }

  /** How many times a word occurs across the window — the keyword score's raw count. */
  public long occurrencesOf(String word) {
    return wordCounts().getOrDefault(word.toLowerCase(Locale.ROOT), 0L);
  }

  /**
   * Every word in the window against how often it occurs. Scoring a whole observation reads this
   * once, rather than walking the window per memory per word.
   */
  public Map<String, Long> wordCounts() {
    return queries.stream()
        .flatMap(query -> java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+")))
        .filter(word -> !word.isEmpty())
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  /** The distinct words of a memory's text, which is what its keyword score is summed over. */
  public static Set<String> wordsOf(String text) {
    if (text == null) {
      return Set.of();
    }
    return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
        .filter(word -> !word.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
