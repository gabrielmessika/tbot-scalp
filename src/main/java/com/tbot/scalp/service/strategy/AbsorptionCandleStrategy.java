package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * Absorption Candle — high volume + low range at structural level.
 * Detects institutional players absorbing aggressive order flow at key levels.
 *
 * Mechanism: absorption score = relativeVolume / relativeRange.
 * A high score means large volume transacted over a small price movement →
 * passive institutional orders defended that price level.
 *
 * Distinct from relative-volume scoring (which rewards high vol + big move —
 * the opposite of absorption).
 */
@Component
public class AbsorptionCandleStrategy implements ScalpStrategy {

    private static final int AVG_PERIOD = 20;
    private static final int SWING_PERIOD = 15;
    private static final double MIN_ABSORPTION_MULT = 3.0;
    private static final double MIN_BODY_RATIO = 0.30;
    private static final double LEVEL_PROXIMITY_ATR = 0.75;
    private static final double VWAP_PROXIMITY_ATR = 0.50;
    private static final double MIN_RR = 1.5;
    private static final double MAX_SL_PCT = 0.015;

    @Override
    public String getName() {
        return "Absorption Candle";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < AVG_PERIOD + SWING_PERIOD + 5)
            return signals;

        for (int i = AVG_PERIOD + SWING_PERIOD; i < candles.size(); i++) {
            if (atr[i] <= 0 || rsi[i] <= 0)
                continue;

            Candle c = candles.get(i);
            double range = c.range();
            if (range <= 0)
                continue;

            // Average volume and ATR-normalized range over lookback
            double avgVol = 0;
            for (int j = i - AVG_PERIOD; j < i; j++)
                avgVol += candles.get(j).getVolume();
            avgVol /= AVG_PERIOD;
            if (avgVol <= 0)
                continue;

            // Absorption score: high relative volume over low relative range
            double relVol = c.getVolume() / avgVol;
            double relRange = range / atr[i];
            double absorptionScore = relVol / relRange;
            if (absorptionScore < MIN_ABSORPTION_MULT)
                continue;

            // Need directional close (not a doji)
            double bodyRatio = c.body() / range;
            if (bodyRatio < MIN_BODY_RATIO)
                continue;

            // Structural levels from recent swing
            double swingLow = Double.MAX_VALUE, swingHigh = -Double.MAX_VALUE;
            for (int j = i - SWING_PERIOD; j < i; j++) {
                swingLow = Math.min(swingLow, candles.get(j).getLow());
                swingHigh = Math.max(swingHigh, candles.get(j).getHigh());
            }

            boolean nearSwingLow = Math.abs(c.getLow() - swingLow) <= LEVEL_PROXIMITY_ATR * atr[i];
            boolean nearSwingHigh = Math.abs(c.getHigh() - swingHigh) <= LEVEL_PROXIMITY_ATR * atr[i];
            boolean nearVwap = vwap != null && vwap[i] > 0
                    && Math.abs(c.getClose() - vwap[i]) <= VWAP_PROXIMITY_ATR * atr[i];

            double strength = Math.min(1.0, absorptionScore / (MIN_ABSORPTION_MULT * 2));

            // LONG: absorption at support (swing low or VWAP touched from below)
            if (c.isBullish() && (nearSwingLow || (nearVwap && c.getLow() <= vwap[i]))) {
                double sl = Math.min(c.getLow(), swingLow) - 0.3 * atr[i];
                double risk = c.getClose() - sl;
                double tp = c.getClose() + risk * MIN_RR;
                if (risk > 0 && risk / c.getClose() < MAX_SL_PCT) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName()).strength(strength)
                            .build());
                }
            }

            // SHORT: absorption at resistance (swing high or VWAP touched from above)
            if (c.isBearish() && (nearSwingHigh || (nearVwap && c.getHigh() >= vwap[i]))) {
                double sl = Math.max(c.getHigh(), swingHigh) + 0.3 * atr[i];
                double risk = sl - c.getClose();
                double tp = c.getClose() - risk * MIN_RR;
                if (risk > 0 && risk / c.getClose() < MAX_SL_PCT) {
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
