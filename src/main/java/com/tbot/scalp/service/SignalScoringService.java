package com.tbot.scalp.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;
import com.tbot.scalp.model.Signal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Signal scoring for scalp signals. 8-component scoring system:
 * 1. Strategy weight
 * 2. Timeframe bonus
 * 3. R:R ratio
 * 4. ADX trend confirmation
 * 5. Volume relative
 * 6. RSI position (not extreme)
 * 7. VWAP alignment
 * 8. Pair scoring bonus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalScoringService {

    private final ScalpConfig config;

    public List<Signal> scoreSignals(Map<String, List<RawSignal>> strategySignals,
            List<Candle> candles, double[] atr, double[] rsi,
            double[] adx, double[] vwap, double[] relativeVolume,
            String pair, String timeframe, int coinMaxLeverage) {
        List<Signal> scored = new ArrayList<>();

        // Flatten and group by proximity + direction
        List<RawSignal> all = new ArrayList<>();
        strategySignals.values().forEach(all::addAll);

        // Group by candleIndex proximity (±2 candles) and same direction
        List<List<RawSignal>> groups = groupByConfluence(all);

        for (List<RawSignal> group : groups) {
            RawSignal primary = group.stream()
                    .max(Comparator.comparingDouble(RawSignal::getStrength))
                    .orElse(group.get(0));

            int idx = primary.getCandleIndex();
            if (idx < 0 || idx >= candles.size())
                continue;

            // Adjust SL if too tight: must be at least max(minSlPercent, minSlAtrMult ×
            // ATR)
            double entryPrice = primary.getEntryPrice();
            double rawSl = primary.getSuggestedSl();
            double rawSlDist = entryPrice > 0 ? Math.abs(entryPrice - rawSl) / entryPrice * 100 : 0;
            double atrPct = (idx < atr.length && atr[idx] > 0 && candles.get(idx).getClose() > 0)
                    ? atr[idx] / candles.get(idx).getClose() * 100
                    : 0;
            double minSlPct = Math.max(config.getEffectiveMinSl(timeframe), config.getMinSlAtrMult() * atrPct);
            double adjustedSl = rawSl;
            if (rawSlDist < minSlPct && minSlPct > 0) {
                adjustedSl = "LONG".equals(primary.getDirection())
                        ? entryPrice * (1.0 - minSlPct / 100.0)
                        : entryPrice * (1.0 + minSlPct / 100.0);
                log.debug("{} {} SL widened {}→{} ({}%→{}%, ATR={}%)",
                        pair, primary.getDirection(),
                        String.format("%.4f", rawSl), String.format("%.4f", adjustedSl),
                        String.format("%.3f", rawSlDist), String.format("%.3f", minSlPct),
                        String.format("%.3f", atrPct));
            }

            double score = 0;
            List<String> strategies = new ArrayList<>();

            // 1. Strategy weight (sum of all strategies in group)
            for (RawSignal rs : group) {
                double weight = config.getStrategyWeights().getOrDefault(rs.getStrategyName(), 1.0);
                score += weight;
                if (!strategies.contains(rs.getStrategyName())) {
                    strategies.add(rs.getStrategyName());
                }
            }

            // 2. Timeframe bonus
            ScalpConfig.TimeframeSettings tfSettings = config.getEffectiveSettings(timeframe);
            if (tfSettings.getScoringBonus() != null) {
                score += tfSettings.getScoringBonus();
            }

            // 3. R:R ratio (using adjusted SL)
            double risk = "LONG".equals(primary.getDirection())
                    ? entryPrice - adjustedSl
                    : adjustedSl - entryPrice;
            double reward = "LONG".equals(primary.getDirection())
                    ? primary.getSuggestedTp() - entryPrice
                    : entryPrice - primary.getSuggestedTp();
            double rr = risk > 0 ? reward / risk : 0;

            if (rr >= 3.0)
                score += 1.0;
            else if (rr >= 2.0)
                score += 0.5;
            else if (rr < 1.0)
                score -= 1.0;
            else if (rr < 1.5)
                score -= 0.5;

            // 4. ADX trend confirmation
            if (idx < adx.length && adx[idx] > 0) {
                if (adx[idx] > 35)
                    score += 1.0;
                else if (adx[idx] > 20)
                    score += 0.5;
                else if (adx[idx] < 12)
                    score -= 0.5;
            }

            // 5. Volume relative
            if (idx < relativeVolume.length) {
                if (relativeVolume[idx] > 2.5)
                    score += 1.0;
                else if (relativeVolume[idx] > 1.5)
                    score += 0.5;
            }

            // 6. RSI position (neutral zone is ideal for continuation scalps)
            if (idx < rsi.length && rsi[idx] > 0) {
                if ("LONG".equals(primary.getDirection()) && rsi[idx] >= 35 && rsi[idx] <= 60)
                    score += 0.5;
                if ("SHORT".equals(primary.getDirection()) && rsi[idx] >= 40 && rsi[idx] <= 65)
                    score += 0.5;
                // Extreme zones → penalty for continuation, but OK for divergence reversal
                if ("LONG".equals(primary.getDirection()) && rsi[idx] > 75 && !strategies.contains("RSI Divergence"))
                    score -= 0.5;
                if ("SHORT".equals(primary.getDirection()) && rsi[idx] < 25 && !strategies.contains("RSI Divergence"))
                    score -= 0.5;
            }

            // 7. VWAP alignment
            if (idx < vwap.length && vwap[idx] > 0 && atr[idx] > 0) {
                double priceVsVwap = candles.get(idx).getClose() - vwap[idx];
                boolean vwapAligned = ("LONG".equals(primary.getDirection()) && priceVsVwap > 0)
                        || ("SHORT".equals(primary.getDirection()) && priceVsVwap < 0);
                if (vwapAligned)
                    score += 0.5;
            }

            // 8. Pair scoring bonus
            double pairBonus = config.getPairScoring().getOrDefault(pair, 0.0);
            score += pairBonus;

            // Filter below minimum
            if (score < config.getMinScore())
                continue;

            // SL distance (using adjusted SL)
            double slDist = entryPrice > 0 ? Math.abs(entryPrice - adjustedSl) / entryPrice * 100 : 0;
            double effectiveMaxSl = config.getEffectiveMaxSl(timeframe);
            if (slDist > effectiveMaxSl)
                continue;

            // Determine leverage — risk-based: target maxLossPerTrade% of portfolio
            // leverage = targetLoss% / (slDist% × positionSize%) × 100
            int effectiveMaxLev = Math.min(config.getMaxLeverage(), coinMaxLeverage);
            double targetLeverage = (config.getMaxLossPerTradePercent() * 100.0)
                    / (slDist * config.getPositionSizePercent());
            int leverage = Math.max(config.getMinLeverage(),
                    Math.min(effectiveMaxLev, (int) targetLeverage));

            String confidence = score >= config.getEffectiveThreshold(timeframe)
                    ? "Very Confident 🟢"
                    : "Risky 🟡";

            scored.add(Signal.builder()
                    .pair(pair).timeframe(timeframe)
                    .direction(primary.getDirection())
                    .entryPrice(entryPrice)
                    .stopLoss(adjustedSl)
                    .takeProfit(primary.getSuggestedTp())
                    .score(Math.round(score * 10.0) / 10.0)
                    .confidence(confidence)
                    .strategies(strategies)
                    .riskReward(Math.round(rr * 10.0) / 10.0)
                    .leverage(leverage)
                    .timestamp(candles.get(idx).getTimestamp())
                    .candleIndex(idx)
                    .alertTimestamp(System.currentTimeMillis())
                    .build());
        }

        scored.sort(Comparator.comparingDouble(Signal::getScore).reversed());
        return scored;
    }

    private List<List<RawSignal>> groupByConfluence(List<RawSignal> signals) {
        List<List<RawSignal>> groups = new ArrayList<>();
        boolean[] used = new boolean[signals.size()];

        for (int i = 0; i < signals.size(); i++) {
            if (used[i])
                continue;
            List<RawSignal> group = new ArrayList<>();
            group.add(signals.get(i));
            used[i] = true;

            for (int j = i + 1; j < signals.size(); j++) {
                if (used[j])
                    continue;
                if (signals.get(i).getDirection().equals(signals.get(j).getDirection())
                        && Math.abs(signals.get(i).getCandleIndex() - signals.get(j).getCandleIndex()) <= 2) {
                    group.add(signals.get(j));
                    used[j] = true;
                }
            }
            groups.add(group);
        }
        return groups;
    }
}
