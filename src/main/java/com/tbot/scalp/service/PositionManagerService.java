package com.tbot.scalp.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.OpenPosition;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionManagerService {

    private final ScalpConfig config;
    private final IndicatorService indicatorService;
    private final TradeHistoryService historyService;

    private final Map<String, OpenPosition> openPositions = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Integer> contrarySignalCounts = new ConcurrentHashMap<>();
    private final Map<String, String> lastContraryDirection = new ConcurrentHashMap<>();

    public void addPosition(OpenPosition position) {
        String key = position.getPair() + ":" + position.getTimeframe();
        openPositions.put(key, position);
        log.info("[POSITION] Opened {} {} {} @ {} lev={}x SL={} TP={}",
                position.getDirection(), position.getPair(), position.getTimeframe(),
                position.getEntryPrice(), position.getLeverage(),
                position.getStopLoss(), position.getTakeProfit());
    }

    public void checkPositions(Map<String, List<Candle>> candleCache) {
        for (var entry : openPositions.entrySet()) {
            OpenPosition pos = entry.getValue();
            String key = pos.getPair() + ":" + pos.getTimeframe();
            List<Candle> candles = candleCache.get(key);
            if (candles == null || candles.isEmpty())
                continue;

            Candle last = candles.get(candles.size() - 1);
            pos.updatePrice(last.getClose());

            // Calculate elapsed candles
            long candleDuration = config.getTimeframeDurationMs(pos.getTimeframe());
            if (candleDuration > 0) {
                pos.setCandlesElapsed((int) ((System.currentTimeMillis() - pos.getOpenTimestamp()) / candleDuration));
            }

            ScalpConfig.TimeframeSettings tfSettings = config.getEffectiveSettings(pos.getTimeframe());

            // Level 0: ATR trailing stop
            if (tfSettings.getTrailingStopAtrMult() != null && tfSettings.getTrailingStopAtrMult() > 0) {
                double[] atr = indicatorService.calculateATR(candles, config.getAtrPeriod());
                int lastIdx = candles.size() - 1;
                if (lastIdx < atr.length && atr[lastIdx] > 0) {
                    double trailSl;
                    if ("LONG".equals(pos.getDirection())) {
                        trailSl = last.getClose() - tfSettings.getTrailingStopAtrMult() * atr[lastIdx];
                        if (trailSl > pos.getStopLoss()) {
                            pos.setStopLoss(trailSl);
                            log.debug("[TRAILING] {} SL updated to {}", pos.getPair(), trailSl);
                        }
                    } else {
                        trailSl = last.getClose() + tfSettings.getTrailingStopAtrMult() * atr[lastIdx];
                        if (trailSl < pos.getStopLoss()) {
                            pos.setStopLoss(trailSl);
                            log.debug("[TRAILING] {} SL updated to {}", pos.getPair(), trailSl);
                        }
                    }
                }
            }

            // Level 1: Progressive break-even (3 levels)
            {
                double bePercent = tfSettings.getBreakEvenTriggerPercent() != null
                        ? tfSettings.getBreakEvenTriggerPercent()
                        : 50.0;
                double progress = pos.getTpProgressPercent();
                double tpDist = Math.abs(pos.getTakeProfit() - pos.getEntryPrice());
                boolean isLong = "LONG".equals(pos.getDirection());
                double newSl = pos.getStopLoss();

                if (progress >= bePercent + 25 && pos.getBeLevel() < 3) {
                    // Level 3: lock 50% of TP distance
                    newSl = isLong ? pos.getEntryPrice() + 0.5 * tpDist : pos.getEntryPrice() - 0.5 * tpDist;
                    if (isLong ? newSl > pos.getStopLoss() : newSl < pos.getStopLoss()) {
                        pos.setStopLoss(newSl);
                        pos.setBeLevel(3);
                        pos.setBreakEvenApplied(true);
                        log.info("[BE-L3] {} SL locked at 50% TP: {}", pos.getPair(), newSl);
                    }
                } else if (progress >= bePercent + 15 && pos.getBeLevel() < 2) {
                    // Level 2: lock 25% of TP distance
                    newSl = isLong ? pos.getEntryPrice() + 0.25 * tpDist : pos.getEntryPrice() - 0.25 * tpDist;
                    if (isLong ? newSl > pos.getStopLoss() : newSl < pos.getStopLoss()) {
                        pos.setStopLoss(newSl);
                        pos.setBeLevel(2);
                        pos.setBreakEvenApplied(true);
                        log.info("[BE-L2] {} SL locked at 25% TP: {}", pos.getPair(), newSl);
                    }
                } else if (progress >= bePercent && pos.getBeLevel() < 1) {
                    // Level 1: break-even (SL to entry)
                    newSl = pos.getEntryPrice();
                    if (isLong ? newSl > pos.getStopLoss() : newSl < pos.getStopLoss()) {
                        pos.setStopLoss(newSl);
                        pos.setBeLevel(1);
                        pos.setBreakEvenApplied(true);
                        log.info("[BE-L1] {} SL moved to entry {}", pos.getPair(), newSl);
                    }
                }
            }

            // Level 2: Trend reversal
            int trendCheck = tfSettings.getTrendCheckCandles() != null ? tfSettings.getTrendCheckCandles() : 8;
            if (pos.getCandlesElapsed() >= trendCheck && candles.size() > 13) {
                double[] ema5 = indicatorService.calculateEMA(candles, 5);
                double[] ema13 = indicatorService.calculateEMA(candles, 13);
                double[] rsi = indicatorService.calculateRSI(candles, config.getRsiPeriod());
                int idx = candles.size() - 1;
                boolean trendBroken = false;
                if ("LONG".equals(pos.getDirection())) {
                    trendBroken = ema5[idx] < ema13[idx] && rsi[idx] > 70;
                } else {
                    trendBroken = ema5[idx] > ema13[idx] && rsi[idx] < 30;
                }
                if (trendBroken) {
                    closePosition(key, last.getClose(), "TREND_REVERSAL");
                    continue;
                }
            }

            // Level 3: Timeout
            int timeout = tfSettings.getTimeoutCandles() != null ? tfSettings.getTimeoutCandles() : 20;
            if (pos.getCandlesElapsed() >= timeout) {
                closePosition(key, last.getClose(), "TIMEOUT");
                continue;
            }

            // Check SL/TP hit on current candle
            if ("LONG".equals(pos.getDirection())) {
                if (last.getLow() <= pos.getStopLoss()) {
                    closePosition(key, pos.getStopLoss(), "SL_HIT");
                } else if (last.getHigh() >= pos.getTakeProfit()) {
                    closePosition(key, pos.getTakeProfit(), "TP_HIT");
                }
            } else {
                if (last.getHigh() >= pos.getStopLoss()) {
                    closePosition(key, pos.getStopLoss(), "SL_HIT");
                } else if (last.getLow() <= pos.getTakeProfit()) {
                    closePosition(key, pos.getTakeProfit(), "TP_HIT");
                }
            }
        }
    }

    public void closePosition(String key, double exitPrice, String reason) {
        OpenPosition pos = openPositions.remove(key);
        if (pos == null)
            return;

        contrarySignalCounts.remove(pos.getPair());
        lastContraryDirection.remove(pos.getPair());

        pos.setCloseReason(reason);
        historyService.recordClose(pos, exitPrice, reason, pos.getCandlesElapsed());

        long cooldownMs = config.getCooldownMultiplier() * config.getTimeframeDurationMs(pos.getTimeframe());
        cooldowns.put(key, System.currentTimeMillis() + cooldownMs);

        double pnl;
        if ("LONG".equals(pos.getDirection())) {
            pnl = (exitPrice - pos.getEntryPrice()) / pos.getEntryPrice() * 100 * pos.getLeverage();
        } else {
            pnl = (pos.getEntryPrice() - exitPrice) / pos.getEntryPrice() * 100 * pos.getLeverage();
        }
        log.info("[POSITION] Closed {} {} {} @ {} reason={} PnL={}%",
                pos.getDirection(), pos.getPair(), pos.getTimeframe(),
                exitPrice, reason, String.format("%.2f", pnl));
    }

    public boolean isOnCooldown(String pairTf) {
        Long expiry = cooldowns.get(pairTf);
        if (expiry == null)
            return false;
        if (System.currentTimeMillis() > expiry) {
            cooldowns.remove(pairTf);
            return false;
        }
        return true;
    }

    public boolean hasPosition(String pairTf) {
        return openPositions.containsKey(pairTf);
    }

    public List<OpenPosition> getOpenPositions() {
        return new ArrayList<>(openPositions.values());
    }

    public Map<String, Long> getActiveCooldowns() {
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(e -> e.getValue() < now);
        Map<String, Long> active = new LinkedHashMap<>();
        cooldowns.forEach((k, v) -> active.put(k, (v - now) / 1000));
        return active;
    }

    public List<String> getOpenPairKeys() {
        return new ArrayList<>(openPositions.keySet());
    }

    /**
     * Records a contrary signal for a coin. Returns true if the threshold is
     * reached
     * and the existing position was auto-closed (CONTRARY_SIGNALS).
     * Only counts signals whose direction is opposite to the open position.
     */
    public boolean recordContrarySignal(String pair, String signalDirection, int threshold) {
        OpenPosition existing = openPositions.values().stream()
                .filter(p -> p.getPair().equals(pair))
                .findFirst().orElse(null);

        if (existing == null)
            return false;
        if (existing.getDirection().equals(signalDirection))
            return false;

        // Reset counter if contrary direction changed
        String lastDir = lastContraryDirection.get(pair);
        if (!signalDirection.equals(lastDir)) {
            contrarySignalCounts.put(pair, 0);
            lastContraryDirection.put(pair, signalDirection);
        }

        int count = contrarySignalCounts.merge(pair, 1, (a, b) -> a + b);
        log.info("[CONTRARY] {} {}/{} (open={} contrary={})",
                pair, count, threshold, existing.getDirection(), signalDirection);

        if (count >= threshold) {
            String key = existing.getPair() + ":" + existing.getTimeframe();
            closePosition(key, existing.getCurrentPrice(), "CONTRARY_SIGNALS");
            return true;
        }
        return false;
    }
}
