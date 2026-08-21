package io.akka.memos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.memos.domain.DurableTier;
import io.akka.memos.domain.Memory;
import io.akka.memos.domain.Promotion;
import io.akka.memos.domain.QueryWindow;
import io.akka.memos.domain.RankedMemory;
import io.akka.memos.domain.RankedTier;
import io.akka.memos.domain.TierBook;
import io.akka.memos.domain.Weights;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Runs {@code src/test/resources/bench/workloads.json} against this port and writes
 * {@code ../memos-port/bench/port-answers.json}, which
 * {@code python toolkit/answer_diff.py} compares against the same workloads run against
 * MemOS by {@code ../memos-port/bench/run_source.py}.
 *
 * <p>A measurement rather than an assertion — the assertions about these rules are in the
 * six test classes beside this one. Run with {@code mvn test -Dtest=BenchAnswersTest}.
 */
public class BenchAnswersTest {

  /** The same fixed instant {@code run_source.py} uses; both sides add the workload's seconds to it. */
  private static final Instant BASE = Instant.ofEpochSecond(1_787_000_000L);

  private static final ObjectMapper JSON = new ObjectMapper();

  private static Map<String, JsonNode> workloads() throws IOException {
    var stream = BenchAnswersTest.class.getResourceAsStream("/bench/workloads.json");
    var byName = new LinkedHashMap<String, JsonNode>();
    for (JsonNode w : JSON.readTree(stream)) {
      byName.put(w.get("name").asText(), w);
    }
    return byName;
  }

  private static List<String> keysOf(List<Memory> memories) {
    return memories.stream().map(Memory::key).toList();
  }

  private static RankedMemory ranked(JsonNode node) {
    return new RankedMemory(
        node.get("key").asText(), "text of " + node.get("key").asText(),
        node.get("rerank").asDouble(), 0, 1);
  }

  private static ObjectNode rankedSnapshot(RankedTier tier, ArrayNode into) {
    for (RankedMemory m : tier.memories()) {
      var row = into.addObject();
      row.put("key", m.key());
      row.put("rerank", round(m.rerankScore()));
      row.put("keyword", round(m.keywordScore()));
      row.put("observations", m.observationCount());
    }
    return null;
  }

  /** The source rounds its scores to nine places before writing them; this matches that. */
  private static double round(double value) {
    return Math.round(value * 1e9) / 1e9;
  }

  // ------------------------------------------------------------------ the durable tier

  private static ArrayNode durableRecency(JsonNode w) {
    var steps = JSON.createArrayNode();
    var tier = DurableTier.empty("BenchTier", w.get("capacity").asInt());
    long admission = 0;
    int step = 0;
    for (JsonNode row : w.get("rows")) {
      tier = tier.admit(memory(row.get("key").asText(), row.get("updatedAtSeconds").asLong(), admission++))
          .sweep();
      var node = steps.addObject();
      node.put("step", step++);
      node.put("admitted", row.get("key").asText());
      node.put("size", tier.memories().size());
      var survivors = node.putArray("survivors");
      keysOf(tier.memories()).forEach(survivors::add);
    }
    return steps;
  }

  private static Memory memory(String key, long seconds, long admission) {
    return new Memory(key, "text of " + key, BASE.plusSeconds(seconds), admission);
  }

  private static ArrayNode durableArrivalOrders(JsonNode w) {
    var at = new LinkedHashMap<String, Long>();
    w.get("rows").forEach(r -> at.put(r.get("key").asText(), r.get("updatedAtSeconds").asLong()));

    var steps = JSON.createArrayNode();
    int step = 0;
    for (JsonNode order : w.get("orders")) {
      var tier = DurableTier.empty("BenchTier", w.get("capacity").asInt());
      long admission = 0;
      var delivered = new ArrayList<String>();
      for (JsonNode key : order) {
        delivered.add(key.asText());
        tier = tier.admit(memory(key.asText(), at.get(key.asText()), admission++));
      }
      tier = tier.sweep();
      var node = steps.addObject();
      node.put("step", step++);
      var as = node.putArray("deliveredAs");
      delivered.forEach(as::add);
      var survivors = node.putArray("survivors");
      keysOf(tier.memories()).stream().sorted().forEach(survivors::add);
    }
    return steps;
  }

  private static ArrayNode sweepThreshold(JsonNode w) {
    var limits = new LinkedHashMap<String, Integer>();
    w.get("limits").fields().forEachRemaining(e -> limits.put(e.getKey(), e.getValue().asInt()));

    var steps = JSON.createArrayNode();
    int step = 0;
    for (JsonNode row : w.get("rows")) {
      double fill = row.get("fill").asDouble();
      var tiers = new LinkedHashMap<String, DurableTier>();
      limits.forEach((name, limit) -> {
        var tier = DurableTier.empty(name, limit);
        for (int i = 0; i < (int) (limit * fill); i++) {
          tier = tier.admit(memory(name + "-" + i, i, i));
        }
        tiers.put(name, tier);
      });
      var result = new TierBook(tiers, 0).sweepTiersAtOrOverThreshold();

      var node = steps.addObject();
      node.put("step", step++);
      node.put("fill", fill);
      var swept = node.putArray("sweptTiers");
      result.sweptTiers().stream().sorted().forEach(swept::add);
      var sizes = node.putObject("sizes");
      new TreeMap<>(result.book().tiers())
          .forEach((name, tier) -> sizes.put(name, tier.memories().size()));
    }
    return steps;
  }

  // ------------------------------------------------------------------- the ranked tier

  private static ArrayNode rankedObservation(JsonNode w) {
    var initial = new ArrayList<RankedMemory>();
    w.get("initial").forEach(m -> initial.add(ranked(m)));
    var tier = new RankedTier(w.get("capacity").asInt(), initial);

    var steps = JSON.createArrayNode();
    for (JsonNode row : w.get("rows")) {
      var observed = new ArrayList<RankedMemory>();
      row.get("observed").forEach(m -> observed.add(ranked(m)));
      tier = tier.observe(observed, w.get("retention").asInt());
      var node = steps.addObject();
      node.put("step", row.get("round").asInt());
      rankedSnapshot(tier, node.putArray("tier"));
    }
    return steps;
  }

  private static ArrayNode rankedArrivalOrders(JsonNode w) {
    var rerank = new LinkedHashMap<String, Double>();
    w.get("rows").forEach(r -> rerank.put(r.get("key").asText(), r.get("rerank").asDouble()));

    var steps = JSON.createArrayNode();
    int step = 0;
    for (JsonNode order : w.get("orders")) {
      var observed = new ArrayList<RankedMemory>();
      var delivered = new ArrayList<String>();
      for (JsonNode key : order) {
        delivered.add(key.asText());
        observed.add(new RankedMemory(
            key.asText(), "text of " + key.asText(), rerank.get(key.asText()), 0, 1));
      }
      var tier = RankedTier.empty(w.get("capacity").asInt())
          .observe(observed, w.get("retention").asInt());

      var node = steps.addObject();
      node.put("step", step++);
      var as = node.putArray("deliveredAs");
      delivered.forEach(as::add);
      var kept = node.putArray("tier");
      tier.memories().forEach(m -> kept.add(m.key()));
    }
    return steps;
  }

  private static ArrayNode promotion(JsonNode w) {
    var observed = new ArrayList<RankedMemory>();
    for (int i = 0; i < w.get("workingSize").asInt(); i++) {
      observed.add(new RankedMemory("w" + i, "text", i / 100.0, 0, 1));
    }
    var working = RankedTier.empty(w.get("workingCapacity").asInt()).observe(observed, 0);
    var activation = RankedTier.empty(w.get("activationCapacity").asInt());

    var steps = JSON.createArrayNode();
    for (JsonNode row : w.get("rows")) {
      activation = Promotion.promote(
          working, activation, Weights.DEFAULT, w.get("retention").asInt());
      var node = steps.addObject();
      node.put("step", row.get("round").asInt());
      rankedSnapshot(activation, node.putArray("tier"));
    }
    return steps;
  }

  private static ArrayNode queryWindow(JsonNode w) {
    var window = QueryWindow.of(w.get("size").asInt());
    var steps = JSON.createArrayNode();
    for (JsonNode row : w.get("rows")) {
      window = window.record(row.get("query").asText());
      var node = steps.addObject();
      node.put("step", row.get("round").asInt());
      var held = node.putArray("window");
      window.queries().forEach(held::add);
    }
    return steps;
  }

  private static ArrayNode importanceScore(JsonNode w) {
    var weights = new Weights(
        w.get("weights").get(0).asDouble(),
        w.get("weights").get(1).asDouble(),
        w.get("weights").get(2).asDouble());
    var steps = JSON.createArrayNode();
    for (JsonNode row : w.get("rows")) {
      var memory = new RankedMemory(
          "x", "text", row.get("rerank").asDouble(), row.get("keyword").asDouble(),
          row.get("observations").asInt());
      var node = steps.addObject();
      node.put("step", row.get("index").asInt());
      node.put("score", round(memory.importance(weights)));
    }
    return steps;
  }

  // ------------------------------------------------------------------------- timing

  private static long timeNs(Supplier<?> work, int iterations) {
    for (int i = 0; i < Math.max(1, iterations / 20); i++) work.get();
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) work.get();
    return (System.nanoTime() - start) / iterations;
  }

  private static ObjectNode timings(Map<String, JsonNode> workloads) {
    var scored = new RankedMemory("x", "text", 0.5, 3.0, 2);
    long scoreNs = timeNs(() -> scored.importance(Weights.DEFAULT), 200_000);

    var observed = new ArrayList<RankedMemory>();
    for (int i = 0; i < 30; i++) observed.add(new RankedMemory("w" + i, "text", i / 100.0, 0, 1));
    long observationNs = timeNs(
        () -> new RankedTier(30, observed).observe(observed, Promotion.RETENTION_COUNT), 20_000);

    var limits = workloads.get("sweep-threshold").get("limits");
    var tiers = new LinkedHashMap<String, DurableTier>();
    limits.fields().forEachRemaining(e -> {
      int limit = e.getValue().asInt();
      var tier = DurableTier.empty(e.getKey(), limit);
      for (int i = 0; i < (int) (limit * 0.9); i++) {
        tier = tier.admit(new Memory(e.getKey() + "-" + i, "t", BASE.plusSeconds(i), i));
      }
      tiers.put(e.getKey(), tier);
    });
    var book = new TierBook(tiers, 0);
    long sweepNs = timeNs(book::sweepTiersAtOrOverThreshold, 20_000);

    long windowNs = timeNs(() -> {
      var window = QueryWindow.of(3);
      for (String q : List.of("a", "b", "c", "d", "e")) window = window.record(q);
      return window;
    }, 20_000);

    var node = JSON.createObjectNode();
    node.put("importanceScoreNs", scoreNs);
    node.put("rankedObservationNs", observationNs);
    node.put("sweepDecisionNs", sweepNs);
    node.put("queryWindowFivePutsNs", windowNs);
    return node;
  }

  @Test
  public void writeAnswersAndTimings() throws IOException {
    var w = workloads();
    var answers = JSON.createObjectNode();
    answers.set("durable-recency", durableRecency(w.get("durable-recency")));
    answers.set("durable-arrival-order-ties",
        durableArrivalOrders(w.get("durable-arrival-order-ties")));
    answers.set("durable-arrival-order-distinct",
        durableArrivalOrders(w.get("durable-arrival-order-distinct")));
    answers.set("sweep-threshold", sweepThreshold(w.get("sweep-threshold")));
    answers.set("ranked-observation", rankedObservation(w.get("ranked-observation")));
    answers.set("ranked-arrival-order-ties",
        rankedArrivalOrders(w.get("ranked-arrival-order-ties")));
    answers.set("promotion", promotion(w.get("promotion")));
    answers.set("query-window", queryWindow(w.get("query-window")));
    answers.set("importance-score", importanceScore(w.get("importance-score")));

    var out = JSON.createObjectNode();
    out.put("runtime", "Java " + System.getProperty("java.version"));
    out.set("answers", answers);
    out.set("timing", timings(w));

    var target = Path.of("..", "memos-port", "bench", "port-answers.json");
    if (Files.isDirectory(target.getParent())) {
      Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out) + "\n");
      System.out.println("wrote " + target.toAbsolutePath().normalize());
    } else {
      // The published repository has no port folder beside it. The numbers still print.
      System.out.println("no ../memos-port/bench to write to; answers printed only");
    }
    System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));
  }
}
