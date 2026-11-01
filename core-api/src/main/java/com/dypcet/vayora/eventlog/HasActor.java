package com.dypcet.vayora.eventlog;

/**
 * Implemented by an event payload that knows who acted. The writer copies these into
 * {@code events.actor_type} and {@code events.actor_id}; payloads that do not implement
 * this leave both columns NULL.
 */
public interface HasActor {

    String actorType();

    Long actorId();
}
