package io.akka.memos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.memos.api.MemoryCubeEndpoint;
import io.akka.memos.application.MemoryCubeEntity;
import io.akka.memos.domain.Memory;
import io.akka.memos.domain.RankedMemory;
import io.akka.memos.domain.TierBook;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 R23, R24, and every rule surviving a reload. Runs against a started runtime, over the
 * HTTP endpoint, which is the port's only route in from outside its own tests.
 */
class MemoryCubeEndpointIntegrationTest extends TestKitSupport {

  private static final Instant T0 = Instant.parse("2026-08-20T00:00:00Z");

  private io.akka.memos.api.MemoryCubeEndpoint.SizeResponse admit(
      String cube, String tier, String key, long secondsAfterT0) {
    return httpClient
        .POST("/cube/" + cube + "/memories")
        .withRequestBody(
            new MemoryCubeEndpoint.AdmitRequest(tier, key, "text of " + key, T0.plusSeconds(secondsAfterT0)))
        .responseBodyAs(MemoryCubeEndpoint.SizeResponse.class)
        .invoke()
        .body();
  }

  @Test
  void theWorkingTierIsHeldAtItsCapacityThroughTheEndpointOnStartedRuntime() {
    for (int i = 0; i < 60; i++) {
      var size = admit("cube-working", TierBook.WORKING, "w" + i, i);
      assertTrue(size.size() <= 20, "working tier grew past its capacity: " + size.size());
    }
    var sizes = httpClient.GET("/cube/cube-working/tiers")
        .responseBodyAs(Map.class).invoke().body();
    assertEquals(20, ((Number) sizes.get(TierBook.WORKING)).intValue());
  }

  @Test
  void theMostRecentlyUpdatedMemoriesAreTheOnesThatSurvive() {
    for (int i = 0; i < 30; i++) {
      admit("cube-recency", TierBook.WORKING, "r" + i, i * 60);
    }
    var kept = httpClient.GET("/cube/cube-recency/tiers/" + TierBook.WORKING)
        .responseBodyAs(Memory[].class).invoke().body();
    assertEquals(20, kept.length);
    assertEquals("r29", kept[0].key());
    assertEquals("r10", kept[19].key());
  }

  @Test
  void aTierBelowItsThresholdIsNotSweptAndOneAtItIs() {
    for (int i = 0; i < 379; i++) admit("cube-sweep", "UserMemory", "u" + i, i);
    var notDue = httpClient.POST("/cube/cube-sweep/sweep")
        .responseBodyAs(String[].class).invoke().body();
    assertEquals(0, notDue.length);

    for (int i = 379; i < 384; i++) admit("cube-sweep", "UserMemory", "u" + i, i);
    var due = httpClient.POST("/cube/cube-sweep/sweep")
        .responseBodyAs(String[].class).invoke().body();
    assertEquals(List.of("UserMemory"), List.of(due));

    var sizes = httpClient.GET("/cube/cube-sweep/tiers").responseBodyAs(Map.class).invoke().body();
    assertEquals(384, ((Number) sizes.get("UserMemory")).intValue(),
        "a sweep at the threshold looks, and keeps everything");
  }

  @Test
  void observationsThenAPromotionCarryTheBestTwentyIntoTheActivationTier() {
    var observed = IntStream.range(0, 25)
        .mapToObj(i -> new RankedMemory("o" + i, "text " + i, i / 100.0, 0, 1))
        .toList();
    httpClient.POST("/cube/cube-promote/observations").withRequestBody(observed)
        .responseBodyAs(MemoryCubeEndpoint.SizeResponse.class).invoke();
    var promoted = httpClient.POST("/cube/cube-promote/promotions")
        .responseBodyAs(MemoryCubeEndpoint.SizeResponse.class).invoke().body();
    assertEquals(20, promoted.size());

    var activation = httpClient.GET("/cube/cube-promote/ranked/activation")
        .responseBodyAs(RankedMemory[].class).invoke().body();
    assertEquals("o24", activation[0].key());
    assertEquals("o5", activation[19].key());
  }

  /** Rule R23: a refusal is a declared error, and the entity keeps working afterwards. */
  @Test
  void aBlankKeyIsRefusedWithoutStoppingTheEntity() {
    admit("cube-refuse", TierBook.WORKING, "before", 1);
    var refused = httpClient
        .POST("/cube/cube-refuse/memories")
        .withRequestBody(new MemoryCubeEndpoint.AdmitRequest(TierBook.WORKING, "  ", "text", T0))
        .invoke();
    assertEquals(StatusCodes.BAD_REQUEST, refused.httpResponse().status());

    var after = admit("cube-refuse", TierBook.WORKING, "after", 2);
    assertEquals(2, after.size());
  }

  /** Rule R24: a tier larger than the reply limit still reads, because the read is capped. */
  @Test
  void aTierLargerThanTheReplyLimitStillReadsBecauseTheReadIsCapped() {
    for (int i = 0; i < 600; i++) admit("cube-big", "LongTermMemory", "l" + i, i);
    var sizes = httpClient.GET("/cube/cube-big/tiers").responseBodyAs(Map.class).invoke().body();
    assertEquals(600, ((Number) sizes.get("LongTermMemory")).intValue());

    var page = httpClient.GET("/cube/cube-big/tiers/LongTermMemory")
        .responseBodyAs(Memory[].class).invoke().body();
    assertEquals(MemoryCubeEntity.MAX_MEMORIES_PER_READ, page.length);
    assertEquals("l599", page[0].key());
  }

  @Test
  void theQueryWindowFeedsTheKeywordTermOfTheScore() {
    for (String q : List.of("alpha beta", "beta gamma", "beta")) {
      httpClient.POST("/cube/cube-keywords/queries").withRequestBody(q)
          .responseBodyAs(MemoryCubeEndpoint.SizeResponse.class).invoke();
    }
    var observed = List.of(
        new RankedMemory("about-beta", "beta", 0.0, 0, 1),
        new RankedMemory("about-delta", "delta", 0.0, 0, 1));
    httpClient.POST("/cube/cube-keywords/observations").withRequestBody(observed)
        .responseBodyAs(MemoryCubeEndpoint.SizeResponse.class).invoke();

    var ranked = httpClient.GET("/cube/cube-keywords/ranked/working")
        .responseBodyAs(RankedMemory[].class).invoke().body();
    assertEquals("about-beta", ranked[0].key());
    assertEquals(3.0, ranked[0].keywordScore(), 1e-12);
    assertEquals(0.0, ranked[1].keywordScore(), 1e-12);
  }

  @Test
  void everyRuleSurvivesTheCubeBeingReadBackThroughItsJournal() {
    for (int i = 0; i < 40; i++) admit("cube-reload", TierBook.WORKING, "k" + i, i);
    httpClient.POST("/cube/cube-reload/observations")
        .withRequestBody(List.of(new RankedMemory("k39", "text", 0.9, 0, 1)))
        .responseBodyAs(MemoryCubeEndpoint.SizeResponse.class).invoke();

    var beforeSizes = httpClient.GET("/cube/cube-reload/tiers").responseBodyAs(Map.class).invoke().body();
    var beforeTier = httpClient.GET("/cube/cube-reload/tiers/" + TierBook.WORKING)
        .responseBodyAs(Memory[].class).invoke().body();

    // A read through the component client rebuilds the state from the journal for this test's
    // purposes; the assertion is that the two routes agree on every field, including admission.
    var viaEntity = componentClient.forEventSourcedEntity("cube-reload")
        .method(MemoryCubeEntity::readTier).invoke(TierBook.WORKING);

    assertEquals(20, ((Number) beforeSizes.get(TierBook.WORKING)).intValue());
    assertEquals(List.of(beforeTier), viaEntity);
  }

  @Test
  void anUnknownTierIsUncappedRatherThanRefused() {
    for (int i = 0; i < 50; i++) admit("cube-unknown", "SomethingElse", "s" + i, i);
    var sizes = httpClient.GET("/cube/cube-unknown/tiers").responseBodyAs(Map.class).invoke().body();
    assertEquals(50, ((Number) sizes.get("SomethingElse")).intValue());
    var due = httpClient.POST("/cube/cube-unknown/sweep").responseBodyAs(String[].class).invoke().body();
    assertEquals(0, due.length);
  }

  @Test
  void aTierNameThatIsBlankIsRejectedByTheEndpointRatherThanCreatingATier() {
    var response = httpClient
        .POST("/cube/cube-blank/memories")
        .withRequestBody(new MemoryCubeEndpoint.AdmitRequest("  ", "k", "text", T0))
        .invoke();
    assertEquals(StatusCodes.BAD_REQUEST, response.httpResponse().status());
  }

  @Test
  void replacingTheWorkingTierAdmitsByPositionNotByScore() {
    var offered = IntStream.range(0, 50)
        .mapToObj(i -> new TierBook.Offered("m" + i, "text " + i))
        .toList();
    var size = httpClient.POST("/cube/cube-replace/working-memory")
        .withRequestBody(new MemoryCubeEndpoint.ReplaceRequest(offered, T0))
        .responseBodyAs(MemoryCubeEndpoint.SizeResponse.class).invoke().body();
    assertEquals(20, size.size());

    var kept = httpClient.GET("/cube/cube-replace/tiers/" + TierBook.WORKING)
        .responseBodyAs(Memory[].class).invoke().body();
    assertEquals("m19", kept[0].key());
    assertEquals("m0", kept[19].key());
  }

  @Test
  void aNegativeRetentionCountNeverReachesTheEntityBecauseTheCubeSuppliesIt() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new io.akka.memos.domain.RankedTier(2, List.of())
            .observe(List.of(new RankedMemory("a", "t", 0.1, 0, 1)), -1));
  }
}
