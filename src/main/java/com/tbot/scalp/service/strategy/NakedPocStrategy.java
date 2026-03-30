package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * Naked POC (Point of Control) Mean Reversion.
 *
 * POC = mid-price of the highest-volume candle in the recent window.
 * Represents the price where market participants transacted the most →
 * maximum consensus / value area center.
 *
 * A "naked" POC is one that price left without revisiting. Since price
 * seeks value (auction theory), it statistically returns to test POC.
 *
 * Distinct from VWAP Bounce: VWAP is the volume-weighted mean price;
 * POC is the volume-weighted mode — the single most-accepted price.
 */
@Component
public class NakedPocStrategy implements ScalpStrategy {

    private static final int POC_LOOKBACK = 60;   // candles to compute POC
    private static final int NAKED_LOOKBACK = 15;  // must not have revisited in N candles
    private static final double NAKED_PROXIMITY_ATR = 0.4;
    private static final double MIN_DIST_ATR = 1.5; // must be this far from POC
    private static final double MIN_BODY_RATIO = 0.30;
    private static final double MIN_RR = 1.5;
    private static final double MAX_SL_PCT = 0.015;

    @Override
    public String getName() {
        return "Naked POC";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < POC_LOOKBACK + NAKED_LOOKBACK + 5)
            return signals;

        for (int i = POC_LOOKBACK + NAKED_LOOKBACK; i < candles.size(); i++) {
            if (atr[i] <= 0 || rsi[i] <= 0)
                continue;

            Candle c = candles.get(i);

            // POC = mid-price of highest-volume candle in the lookback window
            // (excludes NAKED_LOOKBACK to avoid using too-recent candles)
            double pocPrice = 0;
            double maxVol = -1;
            int pocEnd = i - NAKED_LOOKBACK;
            int pocStart = pocEnd - POC_LOOKBACK;
            if (pocStart < 0)
                continue;
            for (int j = pocStart; j < pocEnd; j++) {
                Candle jc = candles.get(j);
                if (jc.getVolume() > maxVol) {
                    maxVol = jc.getVolume();
                    pocPrice = jc.midPrice();
                }
            }
            if (pocPrice <= 0)
                continue;

            // "Naked" check: price must not have touched POC in recent NAKED_LOOKBACK candles
            boolean revisited = false;
            for (int j = i - NAKED_LOOKBACK; j < i; j++) {
                Candle rc = candles.get(j);
                if (rc.getLow() <= pocPrice + NAKED_PROXIMITY_ATR * atr[i]
                        && rc.getHigh() >= pocPrice - NAKED_PROXIMITY_ATR * atr[i]) {
                    revisited = true;
                    break;
                }
            }
            if (revisited)
                continue;

            // Price must be meaningfully away from POC
            double distFromPoc = Math.abs(c.getClose() - pocPrice);
            if (distFromPoc < MIN_DIST_ATR * atr[i])
                continue;

            // Need reversal candle (first move back toward POC)
            double bodyRatio = c.range() > 0 ? c.body() / c.range() : 0;
            if (bodyRatio < MIN_BODY_RATIO)
                continue;

            double strength = Math.min(1.0, distFromPoc / (atr[i] * 3.0));

            // Price above POC → mean-revert down → SHORT
            if (c.getClose() > pocPrice && c.isBearish()) {
                double sl = c.getHigh() + 0.3 * atr[i];
                double risk = sl - c.getClose();
                double tp = pocPrice;
                double reward = c.getClose() - tp;
                if (risk > 0 && reward / risk >= MIN_RR && risk / c.getClose() < MAX_SL_PCT) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("SHORT")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName()).strength(strength)
                            .build());
                }
            }

            // Price below POC → mean-revert up → LONG
            if (c.getClose() < pocPrice && c.isBullish()) {
                double sl = c.getLow() - 0.3 * atr[i];
                double risk = c.getClose() - sl;
                double tp = pocPrice;
                double reward = tp - c.getClose();
                if (risk > 0 && reward / risk >= MIN_RR && risk / c.getClose() < MAX_SL_PCT) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName()).strength(strength)
                            .build());
                }
            }
        }
        return signals;
    }
}
