package com.ydxc20091.fluidcore.bukkit;

import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.channels.Channels;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/** Bounded owner-to-owner item escrow. No transaction or live ItemStack crosses region threads. */
public final class RegionItemHandoff implements AutoCloseable {
    public interface Source {
        <T> CompletableFuture<T> onOwner(Supplier<T> operation);
        /** Capture a bounded detached payload, and remember the original real resources on this owner. */
        byte[] prepare();
        /** Recheck and reserve exactly the resources represented by the prepared payload. */
        boolean reserve();
        /** Restore reserved resources, or return false to retain their durable recovery envelope. */
        boolean compensate(byte[] item);
        String address();
    }
    public interface Destination {
        <T> CompletableFuture<T> onOwner(Supplier<T> operation);
        default boolean canAccept(byte[] item) { return true; }
        /** Commit at most once, after rechecking the destination generation and capacity. */
        boolean deliver(byte[] item);
        /** Finish a return leg on its owner. A committed destination must never be refunded on failure. */
        default CompletableFuture<Boolean> afterDelivery(byte[] item) { return CompletableFuture.completedFuture(true); }
        String address();
    }
    public enum Outcome { DELIVERED, REJECTED, COMPENSATED, RECOVERY_REQUIRED, BUSY }
    private enum State { PREPARED, RESERVED, DELIVERED, COMPENSATED, RECOVERY_REQUIRED }
    private final Path directory;
    private final ThreadPoolExecutor io;
    private final ConcurrentHashMap<UUID, Escrow> active = new ConcurrentHashMap<>();
    private final int capacity;
    private final AtomicInteger admitted = new AtomicInteger();
    private volatile boolean closed;

    public RegionItemHandoff(Path directory, int capacity) {
        if (capacity < 1 || capacity > 65536) throw new IllegalArgumentException("Handoff capacity must be 1..65536");
        this.directory = directory.toAbsolutePath().normalize(); this.capacity = capacity;
        io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity * 2), task -> {
            Thread thread = new Thread(task, "FluidCore-Handoff-IO"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public int active() { return active.size(); }
    public int capacity() { return capacity; }
    public record RecoveryEntry(UUID id, String state, String source, String destination, int itemBytes, String error) {}
    /** Bounded, read-only journal inspection; uncertain delivery is never automatically refunded. */
    public CompletableFuture<List<RecoveryEntry>> inspectRecoveries(int limit) {
        if (limit < 1 || limit > 4096) throw new IllegalArgumentException("Recovery inspection limit must be 1..4096");
        CompletableFuture<List<RecoveryEntry>> result = new CompletableFuture<>();
        io(() -> {
            List<RecoveryEntry> records = new ArrayList<>();
            if (Files.isDirectory(directory)) try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.escrow")) {
                for (Path file : files) {
                    if (records.size() == limit) break;
                    UUID id;
                    try { id = UUID.fromString(file.getFileName().toString().replaceFirst("\\.escrow$", "")); }
                    catch (IllegalArgumentException otherFile) { continue; }
                    try (var input = new DataInputStream(Files.newInputStream(file))) {
                        if (Files.size(file) > 2_300_000 || input.readInt() != 0x46434831) throw new IOException("Invalid escrow record");
                        String state = input.readUTF(); State.valueOf(state);
                        String source = input.readUTF(), destination = input.readUTF(); int bytes = input.readInt();
                        if (bytes < 0 || bytes > 2_097_152 || input.readNBytes(bytes).length != bytes || input.read() != -1) throw new IOException("Invalid escrow payload");
                        records.add(new RecoveryEntry(id, state, source, destination, bytes, ""));
                    } catch (IOException | IllegalArgumentException malformed) {
                        records.add(new RecoveryEntry(id, "INVALID", "", "", 0, malformed.getMessage()));
                    }
                }
            }
            records.sort(Comparator.comparing(record -> record.id().toString())); result.complete(List.copyOf(records));
        }).whenComplete((ignored, failure) -> { if (failure != null) result.completeExceptionally(failure); });
        return result;
    }
    public CompletableFuture<Outcome> transfer(Source source, Destination destination) {
        Objects.requireNonNull(source); Objects.requireNonNull(destination);
        if (closed) return CompletableFuture.completedFuture(Outcome.BUSY);
        if (admitted.incrementAndGet() > capacity) { admitted.decrementAndGet(); return CompletableFuture.completedFuture(Outcome.BUSY); }
        Escrow escrow = new Escrow(source, destination); active.put(escrow.id, escrow);
        onOwner(source, source::prepare).whenComplete((bytes, failure) -> {
            if (failure != null || bytes == null || bytes.length == 0 || bytes.length > 2_097_152 || closed) { finish(escrow, Outcome.REJECTED); return; }
            escrow.payload = bytes.clone();
            onOwner(destination, () -> destination.canAccept(escrow.payload.clone())).whenComplete((accepts, previewFailure) -> {
            if (previewFailure != null || !Boolean.TRUE.equals(accepts)) { finish(escrow, Outcome.REJECTED); return; }
            write(escrow, State.PREPARED).whenComplete((ignored, ioFailure) -> {
                if (ioFailure != null || closed) { finish(escrow, Outcome.REJECTED); return; }
                onOwner(source, source::reserve).whenComplete((reserved, sourceFailure) -> {
                    if (sourceFailure != null) { recover(escrow); return; }
                    if (!Boolean.TRUE.equals(reserved)) { erase(escrow); finish(escrow, Outcome.REJECTED); return; }
                    escrow.state = State.RESERVED;
                    write(escrow, State.RESERVED).whenComplete((saved, saveFailure) -> {
                        if (saveFailure != null || closed) { compensate(escrow); return; }
                        onOwner(destination, () -> destination.deliver(escrow.payload.clone())).whenComplete((delivered, targetFailure) -> {
                            if (targetFailure != null) { recover(escrow); return; }
                            if (!Boolean.TRUE.equals(delivered)) { compensate(escrow); return; }
                            CompletableFuture<Boolean> completion;
                            try { completion = Objects.requireNonNull(destination.afterDelivery(escrow.payload.clone())); }
                            catch (Throwable returnFailure) { recover(escrow); return; }
                            completion.whenComplete((completed, returnFailure) -> {
                                // The destination has committed. Keep both legs in the journal until completion;
                                // a failed or uncertain return must not refund a duplicate source item.
                                if (returnFailure != null || !Boolean.TRUE.equals(completed)) { recover(escrow); return; }
                                escrow.state = State.DELIVERED;
                                write(escrow, State.DELIVERED).whenComplete((confirmed, confirmFailure) -> {
                                    if (confirmFailure == null) erase(escrow);
                                    finish(escrow, Outcome.DELIVERED);
                                });
                            });
                        });
                    });
                });
            });
            });
        });
        return escrow.result;
    }
    private void compensate(Escrow escrow) {
        if (!escrow.refunding.compareAndSet(false, true)) return;
        onOwner(escrow.source, () -> escrow.source.compensate(escrow.payload.clone())).whenComplete((restored, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(restored)) { recover(escrow); return; }
            escrow.state = State.COMPENSATED;
            write(escrow, State.COMPENSATED).whenComplete((ignored, ioFailure) -> { if (ioFailure == null) erase(escrow); finish(escrow, Outcome.COMPENSATED); });
        });
    }
    private void recover(Escrow escrow) {
        escrow.state = State.RECOVERY_REQUIRED;
        write(escrow, State.RECOVERY_REQUIRED).whenComplete((ignored, failure) -> finish(escrow, Outcome.RECOVERY_REQUIRED));
    }
    private CompletableFuture<Void> write(Escrow escrow, State state) {
        byte[] payload = escrow.payload == null ? new byte[0] : escrow.payload.clone();
        String source = escrow.source.address(), destination = escrow.destination.address(); UUID id = escrow.id;
        return io(() -> {
            Files.createDirectories(directory);
            Path file = directory.resolve(id + ".escrow"), temporary = directory.resolve(id + ".tmp");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                var output = new DataOutputStream(Channels.newOutputStream(channel));
                output.writeInt(0x46434831); output.writeUTF(state.name()); output.writeUTF(source); output.writeUTF(destination);
                output.writeInt(payload.length); output.write(payload); output.flush(); channel.force(true);
            }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        });
    }
    private void erase(Escrow escrow) { io(() -> Files.deleteIfExists(directory.resolve(escrow.id + ".escrow"))); }
    private CompletableFuture<Void> io(IoOperation operation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try { io.execute(() -> { try { operation.run(); result.complete(null); } catch (Throwable failure) { result.completeExceptionally(failure); } }); }
        catch (RejectedExecutionException rejected) { result.completeExceptionally(rejected); }
        return result;
    }
    private void finish(Escrow escrow, Outcome outcome) { if (active.remove(escrow.id, escrow)) admitted.decrementAndGet(); escrow.result.complete(outcome); }
    private static <T> CompletableFuture<T> onOwner(Source source, Supplier<T> operation) {
        try { return Objects.requireNonNull(source.onOwner(operation)); } catch (Throwable unavailable) { return CompletableFuture.failedFuture(unavailable); }
    }
    private static <T> CompletableFuture<T> onOwner(Destination destination, Supplier<T> operation) {
        try { return Objects.requireNonNull(destination.onOwner(operation)); } catch (Throwable unavailable) { return CompletableFuture.failedFuture(unavailable); }
    }
    @Override public void close() { closed = true; io.shutdown(); }
    @FunctionalInterface private interface IoOperation { void run() throws IOException; }
    private static final class Escrow {
        final UUID id = UUID.randomUUID(); final Source source; final Destination destination;
        final CompletableFuture<Outcome> result = new CompletableFuture<>(); final AtomicBoolean refunding = new AtomicBoolean();
        volatile byte[] payload; volatile State state = State.PREPARED;
        Escrow(Source source, Destination destination) { this.source = source; this.destination = destination; }
    }
}
