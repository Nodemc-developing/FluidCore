package com.ydxc20091.fluidcore.api;

public enum FluidAction {
    SIMULATE, EXECUTE;
    public boolean simulate() { return this == SIMULATE; }
    public boolean execute() { return this == EXECUTE; }
}
