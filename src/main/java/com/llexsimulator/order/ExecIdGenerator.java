package com.llexsimulator.order;

/** Zero-GC execution-report ID generator — same mechanics as {@link OrderIdGenerator}. */
public final class ExecIdGenerator {

    private final OrderIdGenerator delegate;

    public ExecIdGenerator() {
        this.delegate = new OrderIdGenerator("E", System.currentTimeMillis() + 1_000_000L);
    }

    public long nextId() { return delegate.nextId(); }

    public int nextId(byte[] dest, int offset) { return delegate.nextId(dest, offset); }

    public int nextId(char[] dest, int offset) { return delegate.nextId(dest, offset); }
}

