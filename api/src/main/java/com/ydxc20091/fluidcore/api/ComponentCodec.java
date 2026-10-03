package com.ydxc20091.fluidcore.api;

/** Application codecs translate values to their stable, bounded encoded representation. */
public interface ComponentCodec<T> {
    byte[] encode(T value);
    T decode(byte[] encoded);

    /** Config values use the codec's real value type, or an explicit base64: encoded value. */
    @SuppressWarnings("unchecked")
    default byte[] encodeConfiguration(Object value) {
        byte[] encoded;
        if (value instanceof byte[] bytes) encoded = bytes.clone();
        else if (value instanceof String text && text.startsWith("base64:")) {
            try { encoded = java.util.Base64.getDecoder().decode(text.substring(7)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid base64 component value", invalid); }
        } else {
            try { encoded = encode((T) value); }
            catch (ClassCastException invalid) {
                throw new IllegalArgumentException("Component configuration has the wrong value type; supply the codec value or base64: data", invalid);
            }
        }
        if (encoded == null) throw new IllegalArgumentException("Component codec returned null");
        if (encoded.length > 65536) throw new IllegalArgumentException("Component configuration exceeds 65536 bytes");
        // Reject malformed encoded values, and use the application's canonical representation.
        byte[] canonical = encode(decode(encoded.clone()));
        if (canonical == null || canonical.length > 65536) throw new IllegalArgumentException("Invalid canonical component data");
        return canonical.clone();
    }
}
