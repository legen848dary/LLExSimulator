package com.llexsimulator.disruptor;

import com.llexsimulator.engine.FixConnection;
import com.llexsimulator.sbe.*;
import com.lmax.disruptor.EventTranslatorVararg;
import java.util.concurrent.atomic.AtomicLong;
import uk.co.real_logic.artio.decoder.NewOrderSingleDecoder;
import uk.co.real_logic.artio.decoder.OrderCancelRequestDecoder;
import uk.co.real_logic.artio.fields.ReadOnlyDecimalFloat;

/**
 * Translates an incoming Artio FIX decoder into the pre-allocated
 * {@link OrderEvent} ring-buffer slot.
 *
 * <p>All scratch arrays are {@code byte[]} since SBE fixed-length char fields
 * use {@code byte[]} for their put/get methods.
 */
public final class OrderEventTranslator implements EventTranslatorVararg<OrderEvent> {

    private static final AtomicLong CORRELATION_COUNTER = new AtomicLong(0);
    private static final long[] POW10 = {
            1L,
            10L,
            100L,
            1_000L,
            10_000L,
            100_000L,
            1_000_000L,
            10_000_000L,
            100_000_000L
    };

    // Scratch byte arrays (single Artio library-thread handler instance — no sync needed)
    private final byte[] symbolBuf  = new byte[16];
    private final byte[] clOrdBuf   = new byte[36];
    private final byte[] senderBuf  = new byte[16];
    private final byte[] targetBuf  = new byte[16];

    @Override
    public void translateTo(OrderEvent event, long sequence, Object... args) {
        Object        decodedMessage = args[0];
        FixConnection connection     = (FixConnection) args[1];
        long          arrivalNs      = (long) args[2];
        long          sessionConnId  = connection.connectionId();

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

        if (decodedMessage instanceof NewOrderSingleDecoder decoder) {
            copyCharsToBytes(decoder.clOrdID(), decoder.clOrdIDLength(), clOrdBuf, 36);
            copyCharsToBytes(decoder.symbol(), decoder.symbolLength(), symbolBuf, 16);
            encoder.side(mapSide(decoder.side()));
            encoder.orderType(mapOrdType(decoder.ordType()));
            encoder.timeInForce(decoder.hasTimeInForce()
                    ? mapTif(decoder.timeInForce())
                    : com.llexsimulator.sbe.TimeInForce.DAY);
            encoder.orderQty(toScaledLong(decoder.orderQty(), 4));
            encoder.price(decoder.hasPrice() ? toScaledLong(decoder.price(), 8) : 0L);
            encoder.stopPx(decoder.hasStopPx() ? toScaledLong(decoder.stopPx(), 8) : 0L);
            encoder.transactTimeNs(arrivalNs);
        } else if (decodedMessage instanceof OrderCancelRequestDecoder decoder) {
            copyCharsToBytes(decoder.clOrdID(), decoder.clOrdIDLength(), clOrdBuf, 36);
            copyCharsToBytes(decoder.symbol(), decoder.symbolLength(), symbolBuf, 16);
            encoder.side(mapSide(decoder.side()));
            encoder.orderType(OrderType.LIMIT);
            encoder.timeInForce(com.llexsimulator.sbe.TimeInForce.DAY);
            encoder.orderQty(0L);
            encoder.price(0L);
            encoder.stopPx(0L);
            encoder.transactTimeNs(arrivalNs);
        } else {
            throw new IllegalArgumentException("Unsupported FIX decoder: " + decodedMessage.getClass().getName());
        }

        encoder.putClOrdId(clOrdBuf, 0);
        encoder.putSymbol(symbolBuf, 0);

        // FIX version
        encoder.fixVersion(mapBeginString(connection.beginString()));

        // sender / target compId
        copyStringToBytes(connection.senderCompId(), senderBuf, 16);
        encoder.putSenderCompId(senderBuf, 0);
        copyStringToBytes(connection.targetCompId(), targetBuf, 16);
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

    private static void copyCharsToBytes(char[] src, int srcLen, byte[] dst, int dstLen) {
        int n = Math.min(srcLen, dstLen);
        for (int i = 0; i < n; i++) {
            dst[i] = (byte) src[i];
        }
        for (int i = n; i < dstLen; i++) {
            dst[i] = (byte) ' ';
        }
    }

    private static long toScaledLong(ReadOnlyDecimalFloat value, int targetScale) {
        long unscaled = value.value();
        int scale = value.scale();
        if (unscaled == 0L) {
            return 0L;
        }
        if (scale == targetScale) {
            return unscaled;
        }
        int delta = Math.abs(targetScale - scale);
        long factor = delta < POW10.length ? POW10[delta] : 1L;
        return scale < targetScale ? unscaled * factor : unscaled / factor;
    }

    static void fillBytes(byte[] dst, int len, byte b) {
        for (int i = 0; i < len; i++) dst[i] = b;
    }
}
