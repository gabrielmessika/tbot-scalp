package com.tbot.scalp.service.strategy;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

/**
 * Order Flow Imbalance Scalp — detects aggressive buying/selling via volume and
 * candle patterns.
 * Uses candle volume clusters and wick analysis to infer order flow imbalance.
 * In backtest mode, uses candle data as proxy for order flow (no live L2 book
 * data).
 * 
 * Detects: consecutive high-volume directional candles with small wicks =
 * aggressive flow.
 */
@Component
public class OrderFlowStrategy implements ScalpStrategy {

    private static final int CLUSTER_SIZE = 3;
    private static final double MIN_AVG_BODY_RATIO = 0.55;
    private static final double MAX_WICK_RATIO = 0.3;
    private static final double MIN_VOLUME_MULT = 1.3;
    private static final double MIN_RR = 1.5;

    @Override
    public String getName() {
        return "Order Flow";
    }

    @Override
    public List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap) {
        List<RawSignal> signals = new ArrayList<>();
        if (candles.size() < CLUSTER_SIZE + 20)
            return signals;

        for (int i = CLUSTER_SIZE + 10; i < candles.size(); i++) {
            if (atr[i] <= 0)
                continue;

            // Analyze the last CLUSTER_SIZE candles
            int bullCount = 0, bearCount = 0;
            double totalBodyRatio = 0, totalWickRatio = 0, totalVolume = 0;

            for (int j = i - CLUSTER_SIZE + 1; j <= i; j++) {
                Candle c = candles.get(j);
                double range = c.range();
                if (range <= 0)
                    continue;

                totalBodyRatio += c.body() / range;
                double wick = c.isBullish() ? c.lowerWick() : c.upperWick();
                totalWickRatio += wick / range;
                totalVolume += c.getVolume();

                if (c.isBullish())
                    bullCount++;
                else
                    bearCount++;
            }

            double avgBodyRatio = totalBodyRatio / CLUSTER_SIZE;
            double avgWickRatio = totalWickRatio / CLUSTER_SIZE;
            if (avgBodyRatio < MIN_AVG_BODY_RATIO || avgWickRatio > MAX_WICK_RATIO)
                continue;

            // Volume check: cluster vol > average of prior candles
            double priorVolAvg = 0;
            for (int j = i - CLUSTER_SIZE - 10; j < i - CLUSTER_SIZE; j++) {
                if (j >= 0)
                    priorVolAvg += candles.get(j).getVolume();
            }
            priorVolAvg /= 10;
            double avgClusterVol = totalVolume / CLUSTER_SIZE;
            if (priorVolAvg > 0 && avgClusterVol / priorVolAvg < MIN_VOLUME_MULT)
                continue;

            Candle c = candles.get(i);

            // Strong bullish flow
            if (bullCount == CLUSTER_SIZE) {
                double sl = candles.get(i - CLUSTER_SIZE + 1).getLow() - 0.3 * atr[i];
                double risk = c.getClose() - sl;
                double tp = c.getClose() + risk * 1.5;
                if (risk > 0 && risk / c.getClose() < 0.015 && (tp - c.getClose()) / risk >= MIN_RR) {
                    double strength = Math.min(1.0, avgBodyRatio * 0.4 +
                            (avgClusterVol / Math.max(priorVolAvg, 1)) * 0.2 + 0.3);
                    signals.add(RawSignal.builder()
                            .candleIndex(i).direction("LONG")
                            .entryPrice(c.getClose()).suggestedSl(sl).suggestedTp(tp)
                            .strategyName(getName()).strength(strength)
                            .build());
                }
            }

            // Strong bearish flow
            if (bearCount == CLUSTER_SIZE) {
                double sl = candles.get(i - CLUSTER_SIZE + 1).getHigh() + 0.3 * atr[i];
                double risk = sl - c.getClose();
                double tp = c.getClose() - risk * 1.5;
                if (risk > 0 && risk / c.getClose() < 0.015 && (c.getClose() - tp) / risk >= MIN_RR) {
                    double strength = Math.min(1.0, avgBodyRatio * 0.4 +
                            (avgClusterVol / Math.max(priorVolAvg, 1)) * 0.2 + 0.3);
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
