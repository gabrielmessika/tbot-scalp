package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * EMA Crossover Scalp — detects fast EMA (5) crossing slow EMA (13).
 * Confirmed by: volume spike, body size, no extreme RSI.
 * Inspired by classic scalping systems used on 1m-5m charts.
 */
@Component
public class EmaCrossStrategy implements ScalpStrategy {

    private static final int FAST_EMA = 0; // emas[0] = EMA5
    private static final int SLOW_EMA = 1; // emas[1] = EMA13
    private static final double MIN_BODY_RATIO = 0.4;
    private static final double RSI_LONG_MAX = 72;
    private static final double RSI_SHORT_MIN = 28;
    private static final double MIN_RR = 1.5;

    @Override
    public String getName() {
        return "EMA Crossover";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < 20 || emas[FAST_EMA].length < 20)
            return signals;

        for (int i = 2; i < candles.size(); i++) {
            if (atr[i] <= 0 || rsi[i] <= 0)
                continue;

            Candle c = candles.get(i);
            double fastNow = emas[FAST_EMA][i];
            double fastPrev = emas[FAST_EMA][i - 1];
            double slowNow = emas[SLOW_EMA][i];
            double slowPrev = emas[SLOW_EMA][i - 1];

            double bodyRatio = c.range() > 0 ? c.body() / c.range() : 0;
            if (bodyRatio < MIN_BODY_RATIO)
                continue;

            // Bullish cross: fast crosses above slow
            boolean bullCross = fastPrev <= slowPrev && fastNow > slowNow;
            // Bearish cross: fast crosses below slow
            boolean bearCross = fastPrev >= slowPrev && fastNow < slowNow;

            if (bullCross && c.isBullish() && rsi[i] < RSI_LONG_MAX) {
                double sl = c.getLow() - 0.5 * atr[i];
                double risk = c.getClose() - sl;
                double tp = c.getClose() + risk * 1.5;
                if (risk > 0 && (tp - c.getClose()) / risk >= MIN_RR) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName())
                            .strength(Math.min(1.0, bodyRatio * 0.7 + 0.3))
                            .build());
                }
            }

            if (bearCross && c.isBearish() && rsi[i] > RSI_SHORT_MIN) {
                double sl = c.getHigh() + 0.5 * atr[i];
                double risk = sl - c.getClose();
                double tp = c.getClose() - risk * 1.5;
                if (risk > 0 && (c.getClose() - tp) / risk >= MIN_RR) {
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("SHORT")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName())
                            .strength(Math.min(1.0, bodyRatio * 0.7 + 0.3))
                            .build());
                }
            }
        }
        return signals;
    }
}
