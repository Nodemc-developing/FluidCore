/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.fluidcore.render;

import com.ydxc20091.fluidcore.api.FluidStack;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Pure bounded layout. Input layer order is bottom to top; grid row zero is the top row. */
public final class FluidRenderLayout {
    public static final int PIXELS_PER_CELL = 16;
    public static final int MAX_LAYERS = 64;
    private final int columns, rows;
    private final List<FluidStack> layers;
    private final int[][] bands;
    private final BigInteger totalAmount;
    private final boolean overflow;

    private FluidRenderLayout(int columns, int rows, List<FluidStack> layers, int[][] bands,
                              BigInteger totalAmount, boolean overflow) {
        this.columns = columns; this.rows = rows; this.layers = layers;
        this.bands = bands; this.totalAmount = totalAmount; this.overflow = overflow;
    }
    /** Amounts beyond capacity are visually clipped, never changed in storage. */
    public static FluidRenderLayout of(int columns, int rows, List<FluidStack> layers, long capacity) {
        if (columns < 1 || columns > 9 || rows < 1 || rows > 6)
            throw new IllegalArgumentException("Render grid must be within 1..9 columns and 1..6 rows");
        if (capacity < 0) throw new IllegalArgumentException("Negative render capacity");
        Objects.requireNonNull(layers, "layers");
        if (layers.size() > MAX_LAYERS) throw new IllegalArgumentException("At most " + MAX_LAYERS + " layers may be rendered");
        List<FluidStack> captured = List.copyOf(layers);
        int height = rows * PIXELS_PER_CELL;
        int[] vertical = new int[height]; Arrays.fill(vertical, -1);
        BigInteger total = BigInteger.ZERO;
        for (FluidStack stack : captured) total = total.add(BigInteger.valueOf(stack.amount()));
        if (capacity > 0) {
            BigInteger limit = BigInteger.valueOf(capacity), pixels = BigInteger.valueOf(height);
            BigInteger accumulated = BigInteger.ZERO;
            int previous = 0;
            for (int layer = 0; layer < captured.size(); layer++) {
                accumulated = accumulated.add(BigInteger.valueOf(captured.get(layer).amount()));
                // Integer ceiling gives a non-empty bottom layer a visible band without floating-point overflow.
                int end = accumulated.min(limit).multiply(pixels).add(limit).subtract(BigInteger.ONE)
                        .divide(limit).intValueExact();
                Arrays.fill(vertical, previous, end, layer);
                previous = end;
            }
        }
        int[][] bands = new int[rows][PIXELS_PER_CELL];
        for (int row = 0; row < rows; row++) for (int band = 0; band < PIXELS_PER_CELL; band++)
            bands[row][band] = vertical[(rows - row - 1) * PIXELS_PER_CELL + band];
        return new FluidRenderLayout(columns, rows, captured, bands, total,
                total.compareTo(BigInteger.valueOf(capacity)) > 0);
    }
    public int columns() { return columns; }
    public int rows() { return rows; }
    public List<FluidStack> layers() { return layers; }
    public BigInteger totalAmount() { return totalAmount; }
    public boolean overflow() { return overflow; }
    /** Bottom-to-top band indices within a particular grid row. -1 means empty. */
    public int[] bands(int row) { checkRow(row); return bands[row].clone(); }
    public int layerAt(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= columns) throw new IndexOutOfBoundsException(column);
        int[] counts = new int[layers.size()];
        for (int layer : bands[row]) if (layer >= 0) counts[layer]++;
        int best = -1, largest = 0;
        for (int index = 0; index < counts.length; index++) if (counts[index] > largest) {
            best = index; largest = counts[index];
        }
        return best;
    }
    private void checkRow(int row) { if (row < 0 || row >= rows) throw new IndexOutOfBoundsException(row); }
}
