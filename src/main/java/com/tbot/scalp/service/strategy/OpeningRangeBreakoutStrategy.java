package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * Opening Range Breakout (ORB) — session-open institutional order flow.
 *
 * London (08:00 UTC) and NY (13:00 UTC) opens concentrate institutional
 * participation. The first 15 minutes establish the Opening Range.
 * A clean break of this range with volume confirms the directional bias
 * set by institutional players entering the session.
 *
 * Only time-anchored signal in the bot — no current strategy exploits
 * the structural liquidity injection at session opens.
 *
 * Only signals on the FIRST close outside the range (avoids duplicates).
 */
@Component
public class OpeningRangeBreakoutStrategy implements ScalpStrategy {

    private static final long[] SESSION_OPENS_MS = {
            8L * 3_600_000L,  // 08:00 UTC — London open
            13L * 3_600_000L  // 13:00 UTC — NY overlap
    };
    private static final long RANGE_DURATION_MS = 15 * 60_000L;    // 15-min range window
    private static final long BREAKOUT_WINDOW_MS = 4L * 3_600_000L; // signal valid 4h after open
    private static final double MIN_RELATIVE_VOLUME = 1.3;
    private static final double MIN_BODY_RATIO = 0.40;
    private static final double MAX_RANGE_ATR_MULT = 3.0; // discard chaotic opens
    private static final double MIN_RR = 1.5;
    private static final double MAX_SL_PCT = 0.02;

    @Override
    public String getName() {
        return "Opening Range Breakout";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < 25)
            return signals;

        for (int i = 20; i < candles.size(); i++) {
            if (atr[i] <= 0 || rsi[i] <= 0)
                continue;

            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            long ts = c.getTimestamp();
            long msInDay = ts % 86_400_000L;
            long dayStart = ts - msInDay;

            // Check if we're in a valid breakout window for any session
            long activeSessionOpen = -1;
            for (long sessionOpen : SESSION_OPENS_MS) {
                long rangeEnd = sessionOpen + RANGE_DURATION_MS;
                long windowEnd = sessionOpen + BREAKOUT_WINDOW_MS;
                if (msInDay >= rangeEnd && msInDay < windowEnd) {
                    activeSessionOpen = sessionOpen;
                    break;
                }
            }
            if (activeSessionOpen < 0)
                continue;

            // Build opening range from candles in [sessionOpen, sessionOpen + RANGE_DURATION_MS)
            long rangeStartMs = dayStart + activeSessionOpen;
            long rangeEndMs = rangeStartMs + RANGE_DURATION_MS;
            double rangeHigh = -Double.MAX_VALUE, rangeLow = Double.MAX_VALUE;
            double rangeVolSum = 0;
            int rangeCount = 0;
            for (int j = 0; j < i; j++) {
                long jts = candles.get(j).getTimestamp();
                if (jts >= rangeStartMs && jts < rangeEndMs) {
                    rangeHigh = Math.max(rangeHigh, candles.get(j).getHigh());
                    rangeLow = Math.min(rangeLow, candles.get(j).getLow());
                    rangeVolSum += candles.get(j).getVolume();
                    rangeCount++;
                }
            }
            if (rangeCount == 0 || rangeHigh <= rangeLow)
                continue;
            double rangeSize = rangeHigh - rangeLow;

            // Discard chaotic opens (range too wide — high uncertainty)
            if (rangeSize > MAX_RANGE_ATR_MULT * atr[i])
                continue;

            // Volume and body confirmation
            double rangeAvgVol = rangeVolSum / rangeCount;
            double relVol = rangeAvgVol > 0 ? c.getVolume() / rangeAvgVol : 1.0;
            if (relVol < MIN_RELATIVE_VOLUME)
                continue;
            double bodyRatio = c.range() > 0 ? c.body() / c.range() : 0;
            if (bodyRatio < MIN_BODY_RATIO)
                continue;

            double strength = Math.min(1.0, (relVol / MIN_RELATIVE_VOLUME) * 0.5 + bodyRatio * 0.5);

            // LONG: first close above range high (prev was inside or below)
            if (c.getClose() > rangeHigh && c.isBullish() && prev.getClose() <= rangeHigh) {
                double sl = rangeLow - 0.2 * atr[i];
                double risk = c.getClose() - sl;
                double tp = c.getClose() + Math.max(rangeSize * 1.5, risk * MIN_RR);
                if (risk > 0 && (tp - c.getClose()) / risk >= MIN_RR && risk / c.getClose() < MAX_SL_PCT) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName()).strength(strength)
                            .build());
                }
            }

            // SHORT: first close below range low (prev was inside or above)
            if (c.getClose() < rangeLow && c.isBearish() && prev.getClose() >= rangeLow) {
                double sl = rangeHigh + 0.2 * atr[i];
                double risk = sl - c.getClose();
                double tp = c.getClose() - Math.max(rangeSize * 1.5, risk * MIN_RR);
                if (risk > 0 && (c.getClose() - tp) / risk >= MIN_RR && risk / c.getClose() < MAX_SL_PCT) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("SHORT")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName()).strength(strength)
                            .build());
                }
            }
        }
        return signals;
    }
}
