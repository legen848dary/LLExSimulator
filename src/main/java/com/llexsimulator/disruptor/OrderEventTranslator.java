package com.llexsimulator.disruptor;

import com.llexsimulator.sbe.*;
import com.lmax.disruptor.EventTranslatorVararg;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Translates an incoming QuickFIX/J {@link Message} into the pre-allocated
 * {@link OrderEvent} ring-buffer slot.
 *
 * <p>All scratch arrays are {@code byte[]} since SBE fixed-length char fields
 * use {@code byte[]} for their put/get methods.
 */
public final class OrderEventTranslator implements EventTranslatorVararg<OrderEvent> {

    private static final AtomicLong CORRELATION_COUNTER = new AtomicLong(0);

    // Scratch byte arrays (single QuickFIX/J acceptor thread — no sync needed)
    private final byte[] symbolBuf  = new byte[16];
    private final byte[] clOrdBuf   = new byte[36];
    private final byte[] senderBuf  = new byte[16];
    private final byte[] targetBuf  = new byte[16];

    @Override
    public void translateTo(OrderEvent event, long sequence, Object... args) {
        Message   message       = (Message)   args[0];
        SessionID sessionId     = (SessionID) args[1];
        long      sessionConnId = (long)      args[2];
        long      arrivalNs     = (long)      args[3];

        event.correlationId       = CORRELATION_COUNTER.incrementAndGet();
        event.sessionConnectionId = sessionConnId;
        event.arrivalTimeNs       = arrivalNs;
        event.isValid             = false;

        // ── Encode into pre-allocated orderBuffer using SBE ──────────────────
        NewOrderSingleEncoder encoder = event.nosEncoder;
        encoder.wrapAndApplyHeader(event.orderBuffer, 0, event.headerEncoder);

        encoder.correlationId(event.correlationId)
               .sessionConnectionId(sessionConnId)
               .arrivalTimeNs(arrivalNs);

        // clOrdId
        try {
            copyStringToBytes(message.getString(ClOrdID.FIELD), clOrdBuf, 36);
        } catch (FieldNotFound e) { fillBytes(clOrdBuf, 36, (byte) ' '); }
        encoder.putClOrdId(clOrdBuf, 0);

        // symbol
        try {
            copyStringToBytes(message.getString(Symbol.FIELD), symbolBuf, 16);
        } catch (FieldNotFound e) { fillBytes(symbolBuf, 16, (byte) ' '); }
        encoder.putSymbol(symbolBuf, 0);

        // side
        try { encoder.side(mapSide(message.getChar(Side.FIELD))); }
        catch (FieldNotFound e) { encoder.side(OrderSide.BUY); }

        // order type
        try { encoder.orderType(mapOrdType(message.getChar(OrdType.FIELD))); }
        catch (FieldNotFound e) { encoder.orderType(OrderType.LIMIT); }

        // time in force — use fully-qualified SBE type to avoid ambiguity with QFJ field
        try { encoder.timeInForce(mapTif(message.getChar(quickfix.field.TimeInForce.FIELD))); }
        catch (FieldNotFound e) { encoder.timeInForce(com.llexsimulator.sbe.TimeInForce.DAY); }

        // orderQty (qty × 10^4)
        try { encoder.orderQty((long)(message.getDouble(OrderQty.FIELD) * 10_000L)); }
        catch (FieldNotFound e) { encoder.orderQty(0L); }

        // price (price × 10^8)
        try { encoder.price((long)(message.getDouble(Price.FIELD) * 100_000_000L)); }
        catch (FieldNotFound e) { encoder.price(0L); }

        // stopPx
        try { encoder.stopPx((long)(message.getDouble(StopPx.FIELD) * 100_000_000L)); }
        catch (FieldNotFound e) { encoder.stopPx(0L); }

        // transactTime
        try {
            LocalDateTime ldt = message.getUtcTimeStamp(TransactTime.FIELD);
            encoder.transactTimeNs(ldt.toEpochSecond(ZoneOffset.UTC) * 1_000_000_000L);
        } catch (FieldNotFound e) { encoder.transactTimeNs(arrivalNs); }

        // FIX version
        encoder.fixVersion(mapBeginString(sessionId.getBeginString()));

        // sender / target compId
        copyStringToBytes(sessionId.getSenderCompID(), senderBuf, 16);
        encoder.putSenderCompId(senderBuf, 0);
        copyStringToBytes(sessionId.getTargetCompID(), targetBuf, 16);
        encoder.putTargetCompId(targetBuf, 0);

        // Re-wrap the NOS *decoder* so downstream handlers can read the just-encoded data
        event.nosDecoder.wrapAndApplyHeader(event.orderBuffer, 0, event.headerDecoder);
    }

    // ── Mapping helpers ──────────────────────────────────────────────────────

    private static OrderSide mapSide(char c) {
        return switch (c) {
            case '1' -> OrderSide.BUY;
            case '2' -> OrderSide.SELL;
            case '5' -> OrderSide.SELL_SHORT;
            default  -> OrderSide.BUY;
        };
    }

    private static OrderType mapOrdType(char c) {
        return switch (c) {
            case '1' -> OrderType.MARKET;
            case '2' -> OrderType.LIMIT;
            case '3' -> OrderType.STOP;
            case '4' -> OrderType.STOP_LIMIT;
            default  -> OrderType.LIMIT;
        };
    }

    private static com.llexsimulator.sbe.TimeInForce mapTif(char c) {
        return switch (c) {
            case '0' -> com.llexsimulator.sbe.TimeInForce.DAY;
            case '1' -> com.llexsimulator.sbe.TimeInForce.GTC;
            case '3' -> com.llexsimulator.sbe.TimeInForce.IOC;
            case '4' -> com.llexsimulator.sbe.TimeInForce.FOK;
            default  -> com.llexsimulator.sbe.TimeInForce.DAY;
        };
    }

    private static FixVersion mapBeginString(String bs) {
        return switch (bs) {
            case "FIX.4.2"  -> FixVersion.FIX42;
            case "FIX.4.4"  -> FixVersion.FIX44;
            case "FIX.5.0"  -> FixVersion.FIX50;
            case "FIXT.1.1" -> FixVersion.FIXT11;
            default         -> FixVersion.FIX44;
        };
    }

    /** Copies a String into a byte[] with blank-padding (ASCII). */
    static void copyStringToBytes(String src, byte[] dst, int len) {
        int n = Math.min(src.length(), len);
        for (int i = 0; i < n; i++)  dst[i] = (byte) src.charAt(i);
        for (int i = n; i < len; i++) dst[i] = (byte) ' ';
    }

    static void fillBytes(byte[] dst, int len, byte b) {
        for (int i = 0; i < len; i++) dst[i] = b;
    }
}
