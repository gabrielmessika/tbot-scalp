package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * RSI Divergence Scalp — detects bullish/bearish divergence between price and
 * RSI.
 * Bullish: price makes lower low but RSI makes higher low → reversal up.
 * Bearish: price makes higher high but RSI makes lower high → reversal down.
 * Classic scalping reversal pattern, effective on 1m-15m.
 */
@Component
public class RsiDivergenceStrategy implements ScalpStrategy {

    private static final int LOOKBACK = 10;
    private static final double RSI_OVERSOLD = 35;
    private static final double RSI_OVERBOUGHT = 65;
    private static final double MIN_RR = 1.5;

    @Override
    public String getName() {
        return "RSI Divergence";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < LOOKBACK + 5)
            return signals;

        for (int i = LOOKBACK + 2; i < candles.size(); i++) {
            if (atr[i] <= 0 || rsi[i] <= 0)
                continue;

            Candle c = candles.get(i);

            // Find swing low for bullish divergence
            int prevSwingLow = findSwingLow(candles, rsi, i - 2, LOOKBACK);
            if (prevSwingLow > 0 && rsi[i] <= RSI_OVERSOLD) {
                double prevLow = candles.get(prevSwingLow).getLow();
                double currentLow = findRecentLow(candles, i, 3);

                // Price lower low, RSI higher low → bullish divergence
                if (currentLow < prevLow && rsi[i] > rsi[prevSwingLow]) {
                    double sl = currentLow - 0.5 * atr[i];
                    double risk = c.getClose() - sl;
                    double tp = c.getClose() + risk * 1.5;
                    if (risk > 0 && risk / c.getClose() < 0.02) { // max 2% SL
                        signals.add(RawSignal.builder()
                                .candleIndex(i).direction("LONG")
                                .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                                .strategyName(getName())
                                .strength(Math.min(1.0, Math.abs(rsi[i] - rsi[prevSwingLow]) / 20.0))
                                .build());
                    }
                }
            }

            // Find swing high for bearish divergence
            int prevSwingHigh = findSwingHigh(candles, rsi, i - 2, LOOKBACK);
            if (prevSwingHigh > 0 && rsi[i] >= RSI_OVERBOUGHT) {
                double prevHigh = candles.get(prevSwingHigh).getHigh();
                double currentHigh = findRecentHigh(candles, i, 3);

                // Price higher high, RSI lower high → bearish divergence
                if (currentHigh > prevHigh && rsi[i] < rsi[prevSwingHigh]) {
                    double sl = currentHigh + 0.5 * atr[i];
                    double risk = sl - c.getClose();
                    double tp = c.getClose() - risk * 1.5;
                    if (risk > 0 && risk / c.getClose() < 0.02) {
                        signals.add(RawSignal.builder()
                                .candleIndex(i).direction("SHORT")
                                .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                                .strategyName(getName())
                                .strength(Math.min(1.0, Math.abs(rsi[prevSwingHigh] - rsi[i]) / 20.0))
                                .build());
                    }
                }
            }
        }
        return signals;
    }

    private int findSwingLow(List<Candle> candles, double[] rsi, int endIdx, int lookback) {
        int startIdx = Math.max(0, endIdx - lookback);
        int bestIdx = -1;
        double bestLow = Double.MAX_VALUE;
        for (int j = startIdx; j < endIdx; j++) {
            if (candles.get(j).getLow() < bestLow && rsi[j] > 0 && rsi[j] < RSI_OVERSOLD + 10) {
                bestLow = candles.get(j).getLow();
                bestIdx = j;
            }
        }
        return bestIdx;
    }

    private int findSwingHigh(List<Candle> candles, double[] rsi, int endIdx, int lookback) {
        int startIdx = Math.max(0, endIdx - lookback);
        int bestIdx = -1;
        double bestHigh = Double.MIN_VALUE;
        for (int j = startIdx; j < endIdx; j++) {
            if (candles.get(j).getHigh() > bestHigh && rsi[j] > 0 && rsi[j] > RSI_OVERBOUGHT - 10) {
                bestHigh = candles.get(j).getHigh();
                bestIdx = j;
            }
        }
        return bestIdx;
    }

    private double findRecentLow(List<Candle> candles, int idx, int window) {
        double low = Double.MAX_VALUE;
        for (int j = Math.max(0, idx - window); j <= idx; j++) {
            low = Math.min(low, candles.get(j).getLow());
        }
        return low;
    }

    private double findRecentHigh(List<Candle> candles, int idx, int window) {
        double high = Double.MIN_VALUE;
        for (int j = Math.max(0, idx - window); j <= idx; j++) {
            high = Math.max(high, candles.get(j).getHigh());
        }
        return high;
    }
}
