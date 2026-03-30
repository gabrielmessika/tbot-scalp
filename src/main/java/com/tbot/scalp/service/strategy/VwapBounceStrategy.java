package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * VWAP Bounce Scalp — price bounces off session VWAP with volume confirmation.
 * LONG: price touches VWAP from above, shows buying pressure (bullish candle +
 * volume).
 * SHORT: price touches VWAP from below, shows selling pressure.
 * VWAP is institutional anchor — bounces from it are high-probability scalps.
 */
@Component
public class VwapBounceStrategy implements ScalpStrategy {

    private static final double VWAP_PROXIMITY_ATR = 0.5; // within 0.5 ATR of VWAP
    private static final double MIN_BODY_RATIO = 0.35;
    private static final double MIN_RR = 1.5;

    @Override
    public String getName() {
        return "VWAP Bounce";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < 20 || vwap == null)
            return signals;

        for (int i = 5; i < candles.size(); i++) {
            if (atr[i] <= 0 || vwap[i] <= 0)
                continue;

            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double distToVwap = Math.abs(c.getClose() - vwap[i]);
            double proximity = distToVwap / atr[i];

            if (proximity > VWAP_PROXIMITY_ATR)
                continue;
            double bodyRatio = c.range() > 0 ? c.body() / c.range() : 0;
            if (bodyRatio < MIN_BODY_RATIO)
                continue;

            // Bullish VWAP bounce: price was below or at VWAP, bounces up
            boolean touchedFromBelow = prev.getLow() <= vwap[i] || c.getLow() <= vwap[i];
            if (touchedFromBelow && c.isBullish() && c.getClose() > vwap[i]) {
                double sl = Math.min(c.getLow(), vwap[i]) - 0.3 * atr[i];
                double risk = c.getClose() - sl;
                double tp = c.getClose() + risk * 1.5;
                if (risk > 0 && (tp - c.getClose()) / risk >= MIN_RR) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName())
                            .strength(Math.min(1.0, (1.0 - proximity) * 0.6 + bodyRatio * 0.4))
                            .build());
                }
            }

            // Bearish VWAP bounce: price was above or at VWAP, bounces down
            boolean touchedFromAbove = prev.getHigh() >= vwap[i] || c.getHigh() >= vwap[i];
            if (touchedFromAbove && c.isBearish() && c.getClose() < vwap[i]) {
                double sl = Math.max(c.getHigh(), vwap[i]) + 0.3 * atr[i];
                double risk = sl - c.getClose();
                double tp = c.getClose() - risk * 1.5;
                if (risk > 0 && (c.getClose() - tp) / risk >= MIN_RR) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("SHORT")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName())
                            .strength(Math.min(1.0, (1.0 - proximity) * 0.6 + bodyRatio * 0.4))
                            .build());
                }
            }
        }
        return signals;
    }
}
