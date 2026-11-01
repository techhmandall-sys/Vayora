package com.dypcet.vayora.eventlog;

import java.time.Instant;
import java.util.UUID;

/**
 * Identifies the simulation run an event belongs to.
 *
 * @param simTime the simulated clock; when null the writer falls back to wall clock
 */
public record RunContext(UUID runId, String variant, Integer seed, Instant simTime) {
}
