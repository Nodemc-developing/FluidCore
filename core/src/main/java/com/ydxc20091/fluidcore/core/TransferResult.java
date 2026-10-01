package com.ydxc20091.fluidcore.core;

public record TransferResult(Status status, long amount, String message) {
    public enum Status { SUCCESS, EMPTY, REJECTED, WRONG_CONTEXT, UNSUPPORTED, STALE }
    public TransferResult {
        if (amount < 0) throw new IllegalArgumentException("Negative amount");
    }
    public boolean successful() { return status == Status.SUCCESS; }
}
