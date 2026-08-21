package io.akka.memos.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import io.akka.memos.domain.CubeEvent;
import io.akka.memos.domain.CubeState;
import io.akka.memos.domain.Memory;
import io.akka.memos.domain.RankedMemory;
import io.akka.memos.domain.TierBook;
import io.akka.memos.domain.Weights;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One memory cube's tiers and the eviction policy over them — SPEC-001.
 *
 * <p>One entity per cube, because every rule in the specification is a decision about one cube's
 * contents relative to each other: which of these memories to keep is not a question that can be
 * answered a memory at a time. Commands to one entity id are serialized by the runtime
 * (question-log row 4), so the read-modify-write each rule performs needs no locking.
 */
@Component(id = "memory-cube")
public class MemoryCubeEntity extends EventSourcedEntity<CubeState, CubeEvent> {

  /**
   * Rule R24. A tier read is capped because the runtime refuses a reply over roughly one megabyte,
   * which a 1500-memory tier exceeds (question-log row 3).
   */
  public static final int MAX_MEMORIES_PER_READ = 200;

  /**
   * Rule R25. A tier the capacities do not name has no capacity and is never swept (R16), so
   * without this a cube can be grown without limit through one — and unlike the source's database
   * this state has to fit inside one entity. The four default capacities sum to 3,500, so this
   * leaves room for uncapped tiers while keeping the cube inside what the runtime replicates.
   */
  public static final int MAX_MEMORIES_PER_CUBE = 5_000;

  @Override
  public CubeState emptyState() {
    return CubeState.initial();
  }

  public record Admit(String tier, String key, String text, Instant updatedAt) {}

  /** Rule R17: admitting to the working tier sweeps it; admitting elsewhere does not. */
  public Effect<Integer> admit(Admit cmd) {
    var rejection = reasonToRefuse(cmd);
    if (rejection != null) {
      return effects().error(rejection);
    }
    var at = cmd.updatedAt() == null ? Instant.now() : cmd.updatedAt();
    return effects()
        .persist(new CubeEvent.MemoryAdmitted(cmd.tier(), cmd.key(), cmd.text(), at))
        .thenReply(state -> state.tiers().sizeOf(cmd.tier()));
  }

  /**
   * Rules R23 and R25. Refusal is a declared error rather than a thrown exception, because an
   * unhandled throw stops the entity (question-log row 7). Nothing here reaches the event, so
   * replay never has to make one of these decisions again.
   */
  private String reasonToRefuse(Admit cmd) {
    if (cmd.tier() == null || cmd.tier().isBlank()) {
      return "tier must not be blank";
    }
    if (cmd.key() == null || cmd.key().isBlank()) {
      return "key must not be blank";
    }
    if (currentState().totalMemories() >= MAX_MEMORIES_PER_CUBE
        && !currentState().tiers().memoriesOf(cmd.tier()).stream()
            .anyMatch(m -> m.key().equals(cmd.key()))) {
      return "cube holds its maximum of " + MAX_MEMORIES_PER_CUBE + " memories";
    }
    return null;
  }

  /**
   * Rule R15: sweep every tier at or over its threshold, and say which were looked at. Nothing is
   * written when no tier is due — a journal entry for a decision that changed nothing is replayed
   * on every recovery for the rest of the cube's life.
   */
  public Effect<List<String>> sweep() {
    var due = currentState().tiers().sweepTiersAtOrOverThreshold().sweptTiers();
    if (due.isEmpty()) {
      return effects().reply(due);
    }
    return effects().persist(new CubeEvent.TiersSwept()).thenReply(state -> due);
  }

  public record ReplaceWorking(List<TierBook.Offered> offered, Instant updatedAt) {}

  /** Rule R18: the first {@code capacity} offered, in the order offered, unranked. */
  public Effect<Integer> replaceWorkingMemory(ReplaceWorking cmd) {
    var at = cmd.updatedAt() == null ? Instant.now() : cmd.updatedAt();
    return effects()
        .persist(new CubeEvent.WorkingMemoryReplaced(cmd.offered(), at))
        .thenReply(state -> state.tiers().sizeOf(TierBook.WORKING));
  }

  /** Rules R5–R11 over the working ranked tier. */
  public Effect<Integer> observe(List<RankedMemory> observed) {
    if (observed == null || observed.stream().anyMatch(m -> m.key() == null || m.key().isBlank())) {
      return effects().error("every observed memory must carry a key");
    }
    return effects()
        .persist(new CubeEvent.MemoriesObserved(observed))
        .thenReply(state -> state.workingRanked().memories().size());
  }

  /** Rule R19. */
  public Effect<Integer> promote() {
    return effects()
        .persist(new CubeEvent.Promoted())
        .thenReply(state -> state.activationRanked().memories().size());
  }

  public Effect<Done> recordQuery(String query) {
    return effects().persist(new CubeEvent.QueryRecorded(query)).thenReply(state -> Done.getInstance());
  }

  public ReadOnlyEffect<Map<String, Integer>> tierSizes() {
    var sizes = new java.util.TreeMap<String, Integer>();
    currentState().tiers().tiers().forEach((name, tier) -> sizes.put(name, tier.memories().size()));
    return effects().reply(sizes);
  }

  /** Rule R24: capped, and the cap is visible to the caller in {@link #tierSizes()}. */
  public ReadOnlyEffect<List<Memory>> readTier(String tier) {
    return effects()
        .reply(currentState().tiers().memoriesOf(tier).stream().limit(MAX_MEMORIES_PER_READ).toList());
  }

  public ReadOnlyEffect<List<RankedMemory>> readWorkingRanked() {
    return effects().reply(currentState().workingRanked().rankedByImportance(Weights.DEFAULT));
  }

  public ReadOnlyEffect<List<RankedMemory>> readActivationRanked() {
    return effects().reply(currentState().activationRanked().rankedByImportance(Weights.DEFAULT));
  }

  @Override
  public CubeState applyEvent(CubeEvent event) {
    return currentState().onEvent(event);
  }
}
