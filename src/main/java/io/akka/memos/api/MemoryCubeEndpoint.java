package io.akka.memos.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.HttpException;
import io.akka.memos.application.MemoryCubeEntity;
import io.akka.memos.domain.Memory;
import io.akka.memos.domain.RankedMemory;
import io.akka.memos.domain.TierBook;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The port's own surface — SPEC-001 §4.6. MemOS has no single endpoint for this: the tiering and
 * eviction decisions are spread across a memory manager, a scheduler monitor and three store
 * backends, and are reached only through the operations that happen to trigger them. Here they are
 * reachable directly, which is what makes the benchmark's answer comparison possible.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/cube")
public class MemoryCubeEndpoint {

  private final ComponentClient componentClient;

  public MemoryCubeEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record AdmitRequest(String tier, String key, String text, Instant updatedAt) {}

  public record ReplaceRequest(List<TierBook.Offered> offered, Instant updatedAt) {}

  public record SizeResponse(int size) {}

  @Post("/{cubeId}/memories")
  public SizeResponse admit(String cubeId, AdmitRequest request) {
    if (request.tier() == null || request.tier().isBlank()) {
      throw HttpException.badRequest("tier must not be blank");
    }
    return new SizeResponse(
        cube(cubeId)
            .method(MemoryCubeEntity::admit)
            .invoke(new MemoryCubeEntity.Admit(
                request.tier(), request.key(), request.text(), request.updatedAt())));
  }

  @Post("/{cubeId}/sweep")
  public List<String> sweep(String cubeId) {
    return cube(cubeId).method(MemoryCubeEntity::sweep).invoke();
  }

  @Post("/{cubeId}/working-memory")
  public SizeResponse replaceWorkingMemory(String cubeId, ReplaceRequest request) {
    return new SizeResponse(
        cube(cubeId)
            .method(MemoryCubeEntity::replaceWorkingMemory)
            .invoke(new MemoryCubeEntity.ReplaceWorking(request.offered(), request.updatedAt())));
  }

  @Post("/{cubeId}/observations")
  public SizeResponse observe(String cubeId, List<RankedMemory> observed) {
    return new SizeResponse(cube(cubeId).method(MemoryCubeEntity::observe).invoke(observed));
  }

  @Post("/{cubeId}/promotions")
  public SizeResponse promote(String cubeId) {
    return new SizeResponse(cube(cubeId).method(MemoryCubeEntity::promote).invoke());
  }

  @Post("/{cubeId}/queries")
  public SizeResponse recordQuery(String cubeId, String query) {
    cube(cubeId).method(MemoryCubeEntity::recordQuery).invoke(query);
    return new SizeResponse(1);
  }

  @Get("/{cubeId}/tiers")
  public Map<String, Integer> tierSizes(String cubeId) {
    return cube(cubeId).method(MemoryCubeEntity::tierSizes).invoke();
  }

  @Get("/{cubeId}/tiers/{tier}")
  public List<Memory> readTier(String cubeId, String tier) {
    return cube(cubeId).method(MemoryCubeEntity::readTier).invoke(tier);
  }

  @Get("/{cubeId}/ranked/working")
  public List<RankedMemory> readWorkingRanked(String cubeId) {
    return cube(cubeId).method(MemoryCubeEntity::readWorkingRanked).invoke();
  }

  @Get("/{cubeId}/ranked/activation")
  public List<RankedMemory> readActivationRanked(String cubeId) {
    return cube(cubeId).method(MemoryCubeEntity::readActivationRanked).invoke();
  }

  private akka.javasdk.client.EventSourcedEntityClient cube(String cubeId) {
    return componentClient.forEventSourcedEntity(cubeId);
  }
}
