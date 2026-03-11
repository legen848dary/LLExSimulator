package com.llexsimulator.order;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Zero-GC order ID generator.
 *
 * <p>Writes a monotonically increasing {@code long} counter directly into a
 * caller-supplied {@code char[]} buffer using integer arithmetic — no
 * {@link String} or {@link StringBuilder} allocation.
 */
public final class OrderIdGenerator {

    private final AtomicLong counter;
    private final String     prefix;

    public OrderIdGenerator(String prefix, long startValue) {
        this.prefix  = prefix;
        this.counter = new AtomicLong(startValue);
    }

    public OrderIdGenerator() {
        this("O", System.currentTimeMillis());
    }

    /** @return next monotonic order ID as a long */
    public long nextId() {
        return counter.incrementAndGet();
    }

    /**
     * Writes the next order ID into {@code dest} starting at {@code offset}.
     * Format: {@code <prefix><monotonic_counter>} left-aligned, blank-padded.
     *
     * @param dest   pre-allocated byte array (length ≥ offset + 36)
     * @param offset start position within {@code dest}
     * @return number of characters written
     */
    public int nextId(byte[] dest, int offset) {
        long id = counter.incrementAndGet();
        int  p  = offset;
        for (int i = 0; i < prefix.length(); i++) dest[p++] = (byte) prefix.charAt(i);
        p += longToBytes(id, dest, p);
        int written = p - offset;
        int pad     = 36 - written;
        for (int i = 0; i < pad && i + p < dest.length; i++) dest[p + i] = (byte) ' ';
        return Math.min(written, 36);
    }

    /** Writes the next order ID into a char array (kept for compatibility). */
    public int nextId(char[] dest, int offset) {
        long id = counter.incrementAndGet();
        int  p  = offset;
        for (int i = 0; i < prefix.length(); i++) dest[p++] = prefix.charAt(i);
        p += longToChars(id, dest, p);
        int written = p - offset;
        int pad     = 36 - written;
        for (int i = 0; i < pad && i + p < dest.length; i++) dest[p + i] = ' ';
        return Math.min(written, 36);
    }

    /** Writes {@code value} as decimal ASCII into a byte[] at {@code offset}. Returns chars written. */
    public static int longToBytes(long value, byte[] dest, int offset) {
        if (value == 0) { dest[offset] = '0'; return 1; }
        int start = offset;
        long v = value < 0 ? -value : value;
        int end = offset;
        while (v > 0) { dest[end++] = (byte)('0' + (v % 10)); v /= 10; }
        if (value < 0) dest[end++] = '-';
        int lo = start, hi = end - 1;
        while (lo < hi) { byte tmp = dest[lo]; dest[lo] = dest[hi]; dest[hi] = tmp; lo++; hi--; }
        return end - start;
    }

    /** Writes {@code value} as decimal ASCII into {@code dest} at {@code offset}. Returns chars written. */
    public static int longToChars(long value, char[] dest, int offset) {
        if (value == 0) { dest[offset] = '0'; return 1; }
        int start = offset;
        long v = value < 0 ? -value : value;
        int end = offset;
        while (v > 0) { dest[end++] = (char) ('0' + (v % 10)); v /= 10; }
        if (value < 0) dest[end++] = '-';
        // reverse in place
        int lo = start, hi = end - 1;
        while (lo < hi) { char tmp = dest[lo]; dest[lo] = dest[hi]; dest[hi] = tmp; lo++; hi--; }
        return end - start;
    }
}

