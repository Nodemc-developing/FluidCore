package com.ydxc20091.fluidcore.api;

/** Application codecs translate values to their stable, bounded encoded representation. */
public interface ComponentCodec<T> {
    byte[] encode(T value);
    T decode(byte[] encoded);
}
