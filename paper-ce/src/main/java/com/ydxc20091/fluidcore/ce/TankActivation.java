/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.ce;

/** Owner-mutated lifecycle state; immutable publications can be checked by another owner. */
final class TankActivation {
    private record State(long version, boolean active) {}
    private volatile State state = new State(0, false);

    void activate() { publish(true); }
    void retire() { publish(false); }
    private void publish(boolean active) {
        State previous = state;
        state = new State(Math.incrementExact(previous.version), active);
    }
    long version() { return state.version; }
    boolean active() { return state.active; }
    boolean matches(long version) {
        State current = state;
        return current.active && current.version == version;
    }
}
