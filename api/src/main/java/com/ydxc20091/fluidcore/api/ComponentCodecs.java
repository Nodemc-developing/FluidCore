package com.ydxc20091.fluidcore.api;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable big-endian primitive codecs with strict decoding. Copyright 2026 ydxc20091. */
public final class ComponentCodecs {
    private ComponentCodecs() { }
    public static final ComponentCodec<Integer> INTEGER = new ComponentCodec<>() {
        public byte[] encode(Integer value) { return ByteBuffer.allocate(4).putInt(value).array(); }
        public Integer decode(byte[] encoded) { return exact(encoded, 4).getInt(); }
    };
    public static final ComponentCodec<Long> LONG = new ComponentCodec<>() {
        public byte[] encode(Long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
        public Long decode(byte[] encoded) { return exact(encoded, 8).getLong(); }
    };
    public static final ComponentCodec<Float> FLOAT = new ComponentCodec<>() {
        public byte[] encode(Float value) { finite(value); return ByteBuffer.allocate(4).putFloat(value).array(); }
        public Float decode(byte[] encoded) { float value = exact(encoded, 4).getFloat(); finite(value); return value; }
    };
    public static final ComponentCodec<Double> DOUBLE = new ComponentCodec<>() {
        public byte[] encode(Double value) { finite(value); return ByteBuffer.allocate(8).putDouble(value).array(); }
        public Double decode(byte[] encoded) { double value = exact(encoded, 8).getDouble(); finite(value); return value; }
    };
    public static final ComponentCodec<Boolean> BOOLEAN = new ComponentCodec<>() {
        public byte[] encode(Boolean value) { return new byte[]{(byte)(value ? 1 : 0)}; }
        public Boolean decode(byte[] encoded) { byte value = exact(encoded, 1).get(); if (value != 0 && value != 1) throw new IllegalArgumentException("Boolean encoding must be 0 or 1"); return value == 1; }
    };
    public static final ComponentCodec<String> STRING = boundedString(1_048_576);
    public static final ComponentCodec<byte[]> BYTES = new ComponentCodec<>() {
        public byte[] encode(byte[] value) { return Objects.requireNonNull(value, "value").clone(); }
        public byte[] decode(byte[] encoded) { return Objects.requireNonNull(encoded, "encoded").clone(); }
    };
    public static final ComponentCodec<FluidKey> KEY = new ComponentCodec<>() {
        public byte[] encode(FluidKey value) { return STRING.encode(value.toString()); }
        public FluidKey decode(byte[] encoded) { return FluidKey.of(STRING.decode(encoded)); }
    };
    public static final ComponentCodec<UUID> UUID = new ComponentCodec<>() {
        public byte[] encode(java.util.UUID value) { return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array(); }
        public java.util.UUID decode(byte[] encoded) { var data = exact(encoded, 16); return new java.util.UUID(data.getLong(), data.getLong()); }
    };

    public static ComponentCodec<String> boundedString(int maximumBytes) {
        if (maximumBytes < 0) throw new IllegalArgumentException("Negative string byte limit");
        return new ComponentCodec<>() {
            public byte[] encode(String value) {
                try {
                    ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(Objects.requireNonNull(value, "value")));
                    if (buffer.remaining() > maximumBytes) throw new IllegalArgumentException("String exceeds byte limit");
                    byte[] encoded = new byte[buffer.remaining()]; buffer.get(encoded); return encoded;
                } catch (CharacterCodingException failure) { throw new IllegalArgumentException("Malformed Unicode string", failure); }
            }
            public String decode(byte[] encoded) {
                Objects.requireNonNull(encoded, "encoded");
                if (encoded.length > maximumBytes) throw new IllegalArgumentException("String exceeds byte limit");
                try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString(); }
                catch (CharacterCodingException failure) { throw new IllegalArgumentException("Malformed UTF-8", failure); }
            }
        };
    }
    private static ByteBuffer exact(byte[] encoded, int size) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length != size) throw new IllegalArgumentException("Expected " + size + " encoded bytes");
        return ByteBuffer.wrap(encoded);
    }
    private static void finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Numeric component must be finite"); }
}
