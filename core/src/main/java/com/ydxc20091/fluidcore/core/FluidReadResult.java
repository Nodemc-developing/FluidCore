package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.FluidStack;
import java.util.Objects;

/** Keeps the original record for unknown versions/types/components and malformed data. */
public final class FluidReadResult {
    public enum Status { EMPTY, PRESENT, UNKNOWN, INVALID }
    private final Status status;
    private final FluidStack stack;
    private final byte[] raw;
    private final String message;

    public FluidReadResult(Status status, FluidStack stack, byte[] raw, String message) {
        this.status = Objects.requireNonNull(status, "status");
        this.stack = Objects.requireNonNull(stack, "stack");
        this.raw = Objects.requireNonNull(raw, "raw").clone();
        this.message = Objects.requireNonNull(message, "message");
    }
    public Status status() { return status; }
    public FluidStack stack() { return stack; }
    public byte[] raw() { return raw.clone(); }
    public String message() { return message; }
    public boolean usable() { return status == Status.EMPTY || status == Status.PRESENT; }
}
