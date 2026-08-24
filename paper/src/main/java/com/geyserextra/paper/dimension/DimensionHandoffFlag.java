package com.geyserextra.paper.dimension;

/**
 * What a backend knew about a Bedrock player at the moment they disconnected,
 * written to the shared extension folder so the <em>next</em> backend they land
 * on can decide whether their client's sky is at risk of being stuck.
 *
 * <p>Serialized with Gson as {@code dimension-handoff/&lt;uuid&gt;.json}. The
 * shared folder is the one place every backend of the network can read and
 * write, which is exactly what a Velocity {@code /server} transfer needs:
 * the quit fires on one backend and the join on another, and neither has any
 * other channel to the other's state.</p>
 *
 * <p>Plain mutable fields, not a record: the file is read by whatever Gson
 * version the runtime classpath carries, and field-reflection deserialization
 * is the one shape they all support.</p>
 */
public final class DimensionHandoffFlag {

    private long quitAtMs;
    private String environment;
    private boolean inPortal;

    /** Gson needs this. */
    public DimensionHandoffFlag() {
    }

    public DimensionHandoffFlag(long quitAtMs, String environment, boolean inPortal) {
        this.quitAtMs = quitAtMs;
        this.environment = environment;
        this.inPortal = inPortal;
    }

    /** Wall-clock time of the disconnect. Comparable across backends because they share a host. */
    public long quitAtMs() {
        return quitAtMs;
    }

    /** {@code World.Environment.name()} of the world the player quit in, e.g. {@code NORMAL}. */
    public String environment() {
        return environment;
    }

    /** Whether the player's feet or head were inside a nether portal block on quit. */
    public boolean inPortal() {
        return inPortal;
    }
}
