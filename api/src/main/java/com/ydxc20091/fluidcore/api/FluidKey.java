package com.ydxc20091.fluidcore.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** A stable namespaced identifier. Copyright 2026 ydxc20091. */
public record FluidKey(String namespace, String value) implements Comparable<FluidKey> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9/._-]+");

    public FluidKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(value, "value");
        if (!NAMESPACE.matcher(namespace).matches() || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid namespaced identifier: " + namespace + ":" + value);
        }
    }

    public static FluidKey of(String key) {
        Objects.requireNonNull(key, "key");
        int separator = key.indexOf(':');
        if (separator < 1 || separator != key.lastIndexOf(':')) {
            throw new IllegalArgumentException("Expected namespace:value: " + key);
        }
        return new FluidKey(key.substring(0, separator), key.substring(separator + 1));
    }

    @Override public String toString() { return namespace + ":" + value; }
    @Override public int compareTo(FluidKey other) { return toString().compareTo(other.toString()); }
}
