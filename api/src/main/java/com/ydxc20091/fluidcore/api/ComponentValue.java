package com.ydxc20091.fluidcore.api;

import java.util.Arrays;
import java.util.Objects;

/** Immutable encoded component data; an unknown component can remain losslessly encoded. */
public final class ComponentValue {
    private final byte[] bytes;
    private final int hash;

    private ComponentValue(byte[] bytes) {
        this.bytes = bytes.clone();
        this.hash = Arrays.hashCode(this.bytes);
    }

    public static ComponentValue of(byte[] bytes) { return new ComponentValue(Objects.requireNonNull(bytes, "bytes")); }
    public byte[] bytes() { return bytes.clone(); }
    public int size() { return bytes.length; }
    @Override public boolean equals(Object other) { return other instanceof ComponentValue value && Arrays.equals(bytes, value.bytes); }
    @Override public int hashCode() { return hash; }
    @Override public String toString() { return "ComponentValue[" + bytes.length + " bytes]"; }
}
