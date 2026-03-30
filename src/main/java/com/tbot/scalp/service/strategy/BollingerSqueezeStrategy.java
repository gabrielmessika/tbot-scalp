package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;
import com.tbot.scalp.service.IndicatorService;

import lombok.RequiredArgsConstructor;

/**
 * Bollinger Band Squeeze Scalp — detects low-volatility squeezes followed by
 * breakout.
 * When bandwidth contracts to a minimum, a big move is imminent.
 * Entry on the first directional breakout candle after squeeze.
 * Widely used in crypto scalping (Keltner+BB squeeze variant).
 */
@Component
@RequiredArgsConstructor
public class BollingerSqueezeStrategy implements ScalpStrategy {

    private static final int BB_PERIOD = 20;
    private static final double BB_STD = 2.0;
    private static final int SQUEEZE_LOOKBACK = 20;
    private static final double MIN_BODY_RATIO = 0.45;
    private static final double MIN_RR = 1.5;

    private final IndicatorService indicatorService;

    @Override
    public String getName() {
        return "Bollinger Squeeze";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < BB_PERIOD + SQUEEZE_LOOKBACK + 5)
            return signals;

        double[] bandwidth = indicatorService.calculateBollingerBandwidth(candles, BB_PERIOD, BB_STD);
        double[] bbUpper = indicatorService.calculateBollingerUpper(candles, BB_PERIOD, BB_STD);
        double[] bbLower = indicatorService.calculateBollingerLower(candles, BB_PERIOD, BB_STD);

        for (int i = BB_PERIOD + SQUEEZE_LOOKBACK; i < candles.size(); i++) {
            if (atr[i] <= 0 || bandwidth[i] <= 0)
                continue;

            // Check if we're coming out of a squeeze
            double minBw = Double.MAX_VALUE;
            for (int j = i - SQUEEZE_LOOKBACK; j < i; j++) {
                if (bandwidth[j] > 0)
                    minBw = Math.min(minBw, bandwidth[j]);
            }

            // Bandwidth at minimum of lookback = squeeze was active
            boolean wasSqueezed = bandwidth[i - 1] <= minBw * 1.1;
            // Now expanding
            boolean expanding = bandwidth[i] > bandwidth[i - 1] * 1.05;

            if (!wasSqueezed || !expanding)
                continue;

            Candle c = candles.get(i);
            double bodyRatio = c.range() > 0 ? c.body() / c.range() : 0;
            if (bodyRatio < MIN_BODY_RATIO)
                continue;

            // Breakout above upper BB → LONG
            if (c.getClose() > bbUpper[i] && c.isBullish()) {
                double sl = (bbUpper[i] + bbLower[i]) / 2.0 - 0.3 * atr[i]; // midline
                double risk = c.getClose() - sl;
                double tp = c.getClose() + risk * 1.5;
                if (risk > 0 && risk / c.getClose() < 0.015 && (tp - c.getClose()) / risk >= MIN_RR) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName())
                            .strength(Math.min(1.0, bodyRatio * 0.5 + (bandwidth[i] / bandwidth[i - 1] - 1) * 2))
                            .build());
                }
            }

            // Breakout below lower BB → SHORT
            if (c.getClose() < bbLower[i] && c.isBearish()) {
                double sl = (bbUpper[i] + bbLower[i]) / 2.0 + 0.3 * atr[i];
                double risk = sl - c.getClose();
                double tp = c.getClose() - risk * 1.5;
                if (risk > 0 && risk / c.getClose() < 0.015 && (c.getClose() - tp) / risk >= MIN_RR) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("SHORT")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName())
                            .strength(Math.min(1.0, bodyRatio * 0.5 + (bandwidth[i] / bandwidth[i - 1] - 1) * 2))
                            .build());
                }
            }
        }
        return signals;
    }
}
