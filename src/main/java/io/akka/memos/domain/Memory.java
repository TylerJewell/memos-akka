package io.akka.memos.domain;

import java.time.Instant;

/**
 * A memory held in a durable tier — SPEC-001 §2.
 *
 * @param key what identifies this memory; readmitting a key replaces the memory rather than
 *     adding a second one
 * @param updatedAt when the memory was last written, which is what recency eviction reads
 * @param admission a strictly increasing number, the tie-break when two memories share one
 *     {@code updatedAt}. The source has no such rule and its answer there depends on the database
 *     (SPEC-001 §4.2).
 */
public record Memory(String key, String text, Instant updatedAt, long admission) {}
