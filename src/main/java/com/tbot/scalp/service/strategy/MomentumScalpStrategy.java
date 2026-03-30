package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * Momentum Scalp — breakout from tight consolidation with volume spike.
 * Similar to tbot's MomentumBurst but tuned for shorter timeframes.
 * Detects: consolidation (low range period), then breakout candle with high
 * volume.
 */
@Component
public class MomentumScalpStrategy implements ScalpStrategy {

    private static final int RANGE_PERIOD = 8;
    private static final double MAX_RANGE_ATR_MULT = 1.5;
    private static final double MIN_RELATIVE_VOLUME = 1.5;
    private static final double MIN_BODY_RATIO = 0.5;
    private static final double MIN_RR = 1.5;
    private static final double RSI_LONG_MAX = 75;
    private static final double RSI_SHORT_MIN = 25;

    @Override
    public String getName() {
        return "Momentum Scalp";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < RANGE_PERIOD + 20)
            return signals;

        for (int i = RANGE_PERIOD + 10; i < candles.size(); i++) {
            if (atr[i] <= 0 || rsi[i] <= 0)
                continue;

            // Calculate consolidation range
            double rangeHigh = Double.MIN_VALUE, rangeLow = Double.MAX_VALUE;
            for (int j = i - RANGE_PERIOD; j < i; j++) {
                rangeHigh = Math.max(rangeHigh, candles.get(j).getHigh());
                rangeLow = Math.min(rangeLow, candles.get(j).getLow());
            }
            double rangeSize = rangeHigh - rangeLow;

            // Must be tight consolidation
            if (rangeSize > MAX_RANGE_ATR_MULT * atr[i])
                continue;

            Candle c = candles.get(i);
            double bodyRatio = c.range() > 0 ? c.body() / c.range() : 0;
            if (bodyRatio < MIN_BODY_RATIO)
                continue;

            // Check volume spike
            double avgVol = 0;
            for (int j = i - 20; j < i; j++) {
                if (j >= 0)
                    avgVol += candles.get(j).getVolume();
            }
            avgVol /= 20;
            double relVol = avgVol > 0 ? c.getVolume() / avgVol : 1.0;
            if (relVol < MIN_RELATIVE_VOLUME)
                continue;

            // Breakout LONG
            if (c.getClose() > rangeHigh && c.isBullish() && rsi[i] < RSI_LONG_MAX) {
                double sl = rangeLow - 0.3 * atr[i];
                double risk = c.getClose() - sl;
                double tp = c.getClose() + Math.max(rangeSize * 2.0, risk * 1.5);
                if (risk > 0 && (tp - c.getClose()) / risk >= MIN_RR && risk / c.getClose() < 0.015) {
                    double strength = Math.min(1.0, (relVol / MIN_RELATIVE_VOLUME) * 0.5 + bodyRatio * 0.5);
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName()).strength(strength)
                            .build());
                }
            }

            // Breakout SHORT
            if (c.getClose() < rangeLow && c.isBearish() && rsi[i] > RSI_SHORT_MIN) {
                double sl = rangeHigh + 0.3 * atr[i];
                double risk = sl - c.getClose();
                double tp = c.getClose() - Math.max(rangeSize * 2.0, risk * 1.5);
                if (risk > 0 && (c.getClose() - tp) / risk >= MIN_RR && risk / c.getClose() < 0.015) {
                    double strength = Math.min(1.0, (relVol / MIN_RELATIVE_VOLUME) * 0.5 + bodyRatio * 0.5);
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
