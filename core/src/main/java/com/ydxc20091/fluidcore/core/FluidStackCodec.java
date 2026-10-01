package com.ydxc20091.fluidcore.core;

import com.ydxc20091.fluidcore.api.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.zip.CRC32C;

/** Versioned deterministic binary record; failures never discard the original bytes. */
public final class FluidStackCodec {
    public static final int CURRENT_VERSION = 1;
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    public static final int MAX_COMPONENTS = 256;
    private static final int MAGIC = 0x46434F52;
    private static final int MAX_KEY_BYTES = 512;
    private final FluidRegistry registry;
    private final int maxBytes;
    private final Map<Integer, UnaryOperator<byte[]>> migrations = new ConcurrentHashMap<>();

    public FluidStackCodec(FluidRegistry registry) { this(registry, DEFAULT_MAX_BYTES); }
    public FluidStackCodec(FluidRegistry registry, int maxBytes) {
        this.registry = Objects.requireNonNull(registry, "registry");
        if (maxBytes < 13) throw new IllegalArgumentException("Record byte limit must be at least 13");
        this.maxBytes = maxBytes;
    }

    /** Migration receives/returns a complete record, with a strictly newer version. */
    public void registerMigration(int oldVersion, UnaryOperator<byte[]> migration) {
        if (oldVersion < 0 || oldVersion >= CURRENT_VERSION) throw new IllegalArgumentException("Migration version must predate current format");
        if (migrations.putIfAbsent(oldVersion, Objects.requireNonNull(migration, "migration")) != null) throw new IllegalArgumentException("Migration already exists");
    }

    public byte[] encode(FluidStack stack) {
        Objects.requireNonNull(stack, "stack");
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(MAGIC); output.writeInt(CURRENT_VERSION); output.writeBoolean(!stack.isEmpty());
            if (!stack.isEmpty()) {
                writeKey(output, stack.variant().fluid()); output.writeLong(stack.amount());
                Map<FluidKey, ComponentValue> components = stack.variant().components();
                if (components.size() > MAX_COMPONENTS) throw new IllegalArgumentException("Too many components");
                output.writeInt(components.size());
                for (var entry : new TreeMap<>(components).entrySet()) {
                    writeKey(output, entry.getKey());
                    if (entry.getValue().size() > maxBytes) throw new IllegalArgumentException("Component exceeds record byte limit");
                    byte[] value = entry.getValue().bytes();
                    output.writeInt(value.length); output.write(value);
                    if (bytes.size() > maxBytes - 4) throw new IllegalArgumentException("Record exceeds byte limit");
                }
            }
            output.flush();
            if (bytes.size() > maxBytes - 4) throw new IllegalArgumentException("Record exceeds byte limit");
            byte[] payload = bytes.toByteArray();
            CRC32C checksum = new CRC32C();
            checksum.update(payload, 0, payload.length);
            output.writeInt((int) checksum.getValue());
            return bytes.toByteArray();
        } catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    public FluidReadResult decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        byte[] original = encoded.clone();
        if (encoded.length > maxBytes) return result(FluidReadResult.Status.INVALID, FluidStack.EMPTY, original, "Record exceeds byte limit");
        byte[] current = original;
        try {
            int previousVersion = -1;
            for (int attempts = 0; attempts < 8; attempts++) {
                if (current.length > maxBytes) throw new IOException("Migrated record exceeds byte limit");
                var input = new DataInputStream(new ByteArrayInputStream(current));
                if (input.readInt() != MAGIC) throw new IOException("Wrong record magic");
                int version = input.readInt();
                if (version != CURRENT_VERSION) {
                    if (version < 0 || version <= previousVersion) throw new IOException("Invalid or non-progressing format version");
                    UnaryOperator<byte[]> migration = migrations.get(version);
                    if (migration == null) return result(FluidReadResult.Status.UNKNOWN, FluidStack.EMPTY, original, "Unknown format version: " + version);
                    previousVersion = version;
                    current = Objects.requireNonNull(migration.apply(current.clone()), "Migration returned null");
                    continue;
                }
                if (current.length < 13) throw new IOException("Truncated record checksum");
                CRC32C checksum = new CRC32C();
                checksum.update(current, 0, current.length - 4);
                if ((int) checksum.getValue() != ByteBuffer.wrap(current).getInt(current.length - 4)) throw new IOException("Record checksum mismatch");
                input = new DataInputStream(new ByteArrayInputStream(current, 0, current.length - 4));
                input.skipNBytes(8);
                int presence = input.readUnsignedByte();
                if (presence != 0 && presence != 1) throw new IOException("Invalid presence flag");
                if (presence == 0) {
                    if (input.available() != 0) throw new IOException("Trailing data in empty record");
                    return result(FluidReadResult.Status.EMPTY, FluidStack.EMPTY, original, "Empty");
                }
                FluidKey fluid = readKey(input);
                long amount = input.readLong();
                if (amount <= 0) throw new IOException("Non-positive stored amount");
                int componentCount = input.readInt();
                if (componentCount < 0 || componentCount > MAX_COMPONENTS) throw new IOException("Invalid component count");
                var components = new HashMap<FluidKey, ComponentValue>();
                var snapshot = registry.snapshot();
                boolean unknown = !snapshot.fluids().containsKey(fluid);
                for (int index = 0; index < componentCount; index++) {
                    FluidKey key = readKey(input);
                    int length = input.readInt();
                    if (length < 0 || length > maxBytes || length > input.available()) throw new IOException("Invalid component byte length");
                    byte[] value = input.readNBytes(length);
                    if (components.putIfAbsent(key, ComponentValue.of(value)) != null) throw new IOException("Duplicate component key");
                    ComponentCodec<?> codec = snapshot.componentCodecs().get(key);
                    if (codec == null) unknown = true;
                    else codec.decode(value.clone());
                }
                if (input.available() != 0) throw new IOException("Trailing record data");
                FluidStack stack = FluidStack.of(FluidVariant.of(fluid, components), amount);
                return result(unknown ? FluidReadResult.Status.UNKNOWN : FluidReadResult.Status.PRESENT, stack, original, unknown ? "Unknown fluid or component codec; original record retained" : "Decoded");
            }
            throw new IOException("Too many migration steps");
        } catch (IOException | RuntimeException exception) {
            return result(FluidReadResult.Status.INVALID, FluidStack.EMPTY, original, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    private static void writeKey(DataOutputStream output, FluidKey key) throws IOException {
        byte[] bytes = key.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_KEY_BYTES) throw new IllegalArgumentException("Key exceeds byte limit");
        output.writeShort(bytes.length); output.write(bytes);
    }
    private static FluidKey readKey(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > MAX_KEY_BYTES || length > input.available()) throw new IOException("Invalid key byte length");
        byte[] bytes = input.readNBytes(length);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return FluidKey.of(text);
        } catch (CharacterCodingException | IllegalArgumentException exception) { throw new IOException("Invalid fluid key", exception); }
    }
    private static FluidReadResult result(FluidReadResult.Status status, FluidStack stack, byte[] raw, String message) { return new FluidReadResult(status, stack, raw, message); }
}
