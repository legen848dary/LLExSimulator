package com.llexsimulator.order;

import org.agrona.concurrent.UnsafeBuffer;

import java.nio.ByteBuffer;

/**
 * Off-heap, fixed-size order state.
 *
 * <p>All fields are encoded directly into a direct {@link UnsafeBuffer} at
 * fixed byte offsets — no heap objects, no GC pressure. The buffer is
 * pre-allocated once and reused across order lifecycle events.
 *
 * <h3>Buffer layout (256 bytes):</h3>
 * <pre>
 * Offset  Size  Field
 *      0     8  correlationId
 *      8     8  sessionConnectionId
 *     16    36  clOrdId (char[], ASCII)
 *     52    36  orderId  (char[], ASCII)
 *     88    16  symbol   (char[], ASCII)
 *    104     1  side
 *    105     1  orderType
 *    106     1  timeInForce
 *    107     1  ordStatus
 *    108     8  orderQty  (× 10^4)
 *    116     8  price     (× 10^8)
 *    124     8  cumQty    (× 10^4)
 *    132     8  leavesQty (× 10^4)
 *    140     8  avgPxNumerator (sum of fillPrice × fillQty, for VWAP)
 *    148     4  fillCount
 *    152     8  arrivalTimeNs
 * </pre>
 */
public final class OrderState {

    public static final int BUFFER_SIZE                 = 256;

    public static final int CORRELATION_ID_OFFSET       = 0;
    public static final int SESSION_ID_OFFSET           = 8;
    public static final int CL_ORD_ID_OFFSET            = 16;
    public static final int ORDER_ID_OFFSET             = 52;
    public static final int SYMBOL_OFFSET               = 88;
    public static final int SIDE_OFFSET                 = 104;
    public static final int ORDER_TYPE_OFFSET           = 105;
    public static final int TIF_OFFSET                  = 106;
    public static final int ORD_STATUS_OFFSET           = 107;
    public static final int ORDER_QTY_OFFSET            = 108;
    public static final int PRICE_OFFSET                = 116;
    public static final int CUM_QTY_OFFSET              = 124;
    public static final int LEAVES_QTY_OFFSET           = 132;
    public static final int AVG_PX_NUMERATOR_OFFSET     = 140;
    public static final int FILL_COUNT_OFFSET           = 148;
    public static final int ARRIVAL_TIME_NS_OFFSET      = 152;

    final UnsafeBuffer buffer;

    public OrderState() {
        this.buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(BUFFER_SIZE));
    }

    // ── Setters / Getters ────────────────────────────────────────────────────

    public void setCorrelationId(long v)  { buffer.putLong(CORRELATION_ID_OFFSET, v); }
    public long getCorrelationId()         { return buffer.getLong(CORRELATION_ID_OFFSET); }

    public void setSessionConnectionId(long v) { buffer.putLong(SESSION_ID_OFFSET, v); }
    public long getSessionConnectionId()        { return buffer.getLong(SESSION_ID_OFFSET); }

    public void setClOrdId(byte[] src, int srcOff, int len) {
        for (int i = 0; i < 36; i++)
            buffer.putByte(CL_ORD_ID_OFFSET + i, i < len ? src[srcOff + i] : 0);
    }
    public void getClOrdId(byte[] dst, int dstOff) {
        for (int i = 0; i < 36; i++) dst[dstOff + i] = buffer.getByte(CL_ORD_ID_OFFSET + i);
    }

    public void setOrderId(byte[] src, int srcOff, int len) {
        for (int i = 0; i < 36; i++)
            buffer.putByte(ORDER_ID_OFFSET + i, i < len ? src[srcOff + i] : 0);
    }

    public void setSymbol(byte[] src, int srcOff, int len) {
        for (int i = 0; i < 16; i++)
            buffer.putByte(SYMBOL_OFFSET + i, i < len ? src[srcOff + i] : 0);
    }
    public void getSymbol(byte[] dst, int dstOff) {
        for (int i = 0; i < 16; i++) dst[dstOff + i] = buffer.getByte(SYMBOL_OFFSET + i);
    }

    public void setSide(byte v)           { buffer.putByte(SIDE_OFFSET, v); }
    public byte getSide()                  { return buffer.getByte(SIDE_OFFSET); }

    public void setOrderType(byte v)      { buffer.putByte(ORDER_TYPE_OFFSET, v); }
    public byte getOrderType()             { return buffer.getByte(ORDER_TYPE_OFFSET); }

    public void setTimeInForce(byte v)    { buffer.putByte(TIF_OFFSET, v); }

    public void setOrdStatus(byte v)      { buffer.putByte(ORD_STATUS_OFFSET, v); }
    public byte getOrdStatus()             { return buffer.getByte(ORD_STATUS_OFFSET); }

    public void setOrderQty(long v)       { buffer.putLong(ORDER_QTY_OFFSET, v); }
    public long getOrderQty()              { return buffer.getLong(ORDER_QTY_OFFSET); }

    public void setPrice(long v)          { buffer.putLong(PRICE_OFFSET, v); }
    public long getPrice()                 { return buffer.getLong(PRICE_OFFSET); }

    public void setCumQty(long v)         { buffer.putLong(CUM_QTY_OFFSET, v); }
    public long getCumQty()                { return buffer.getLong(CUM_QTY_OFFSET); }

    public void setLeavesQty(long v)      { buffer.putLong(LEAVES_QTY_OFFSET, v); }
    public long getLeavesQty()             { return buffer.getLong(LEAVES_QTY_OFFSET); }

    public void addAvgPxNumerator(long v) { buffer.putLong(AVG_PX_NUMERATOR_OFFSET, buffer.getLong(AVG_PX_NUMERATOR_OFFSET) + v); }
    public long getAvgPxNumerator()        { return buffer.getLong(AVG_PX_NUMERATOR_OFFSET); }

    public void incrementFillCount()      { buffer.putInt(FILL_COUNT_OFFSET, buffer.getInt(FILL_COUNT_OFFSET) + 1); }
    public int  getFillCount()             { return buffer.getInt(FILL_COUNT_OFFSET); }

    public void setArrivalTimeNs(long v)  { buffer.putLong(ARRIVAL_TIME_NS_OFFSET, v); }
    public long getArrivalTimeNs()         { return buffer.getLong(ARRIVAL_TIME_NS_OFFSET); }

    /** Zeroes all bytes — called when returned to the pool. */
    public void reset() {
        buffer.setMemory(0, BUFFER_SIZE, (byte) 0);
    }
}

