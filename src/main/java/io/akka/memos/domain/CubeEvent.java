package io.akka.memos.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;
import java.util.List;

/** What a memory cube records — SPEC-001 §3. */
public sealed interface CubeEvent {

  @TypeName("memory-admitted")
  record MemoryAdmitted(String tier, String key, String text, Instant updatedAt)
      implements CubeEvent {}

  @TypeName("tiers-swept")
  record TiersSwept() implements CubeEvent {}

  @TypeName("working-memory-replaced")
  record WorkingMemoryReplaced(List<TierBook.Offered> offered, Instant updatedAt)
      implements CubeEvent {}

  @TypeName("memories-observed")
  record MemoriesObserved(List<RankedMemory> observed) implements CubeEvent {}

  @TypeName("promoted")
  record Promoted() implements CubeEvent {}

  @TypeName("query-recorded")
  record QueryRecorded(String query) implements CubeEvent {}
}
