package com.dypcet.vayora.eventlog;

/**
 * Holds the run context for the request being served on the current thread.
 *
 * <p>Empty in production: live platform events carry no run, variant or seed. Whoever
 * sets it during simulation must clear it when the request ends, because server
 * threads are reused.
 */
public final class RunContextHolder {

    private static final ThreadLocal<RunContext> CURRENT = new ThreadLocal<>();

    private RunContextHolder() {
    }

    /** Returns the current run context, or null outside a simulation run. */
    public static RunContext get() {
        return CURRENT.get();
    }

    public static void set(RunContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
