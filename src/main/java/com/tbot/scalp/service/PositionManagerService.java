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
import com.tbot.scalp.model.TradeExecution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionManagerService {

    private final ScalpConfig config;
    private final IndicatorService indicatorService;
    private final TradeHistoryService historyService;
    private final HyperliquidExecutionService executionService;
    private final TradeJournalService journalService;

    private final Map<String, OpenPosition> openPositions = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<String, Integer> contrarySignalCounts = new ConcurrentHashMap<>();
    private final Map<String, String> lastContraryDirection = new ConcurrentHashMap<>();

    public void addPosition(OpenPosition position) {
        String key = position.getPair() + ":" + position.getTimeframe();
        openPositions.put(key, position);
        log.info("[POSITION] Opened {} {} {} @ {} lev={}x SL={} TP={} tp={} sl={}",
                position.getDirection(), position.getPair(), position.getTimeframe(),
                position.getEntryPrice(), position.getLeverage(),
                position.getStopLoss(), position.getTakeProfit(),
                position.getTpTriggerId(), position.getSlTriggerId());
    }

    /**
     * Process pending limit order fills — called periodically in live mode.
     * When a limit order fills, we track it as an open position.
     */
    public void processPendingFills() {
        if (!config.isLiveTrading())
            return;

        List<TradeExecution> filled = executionService.checkPendingOrders();
        for (TradeExecution exec : filled) {
            OpenPosition pos = OpenPosition.builder()
                    .pair(exec.getPair()).timeframe(exec.getTimeframe())
                    .direction(exec.getDirection())
                    .entryPrice(exec.getFillPrice())
                    .stopLoss(exec.getStopLoss()).takeProfit(exec.getTakeProfit())
                    .originalStopLoss(exec.getStopLoss())
                    .leverage(exec.getLeverage()).quantity(exec.getQuantity())
                    .currentPrice(exec.getFillPrice())
                    .clientOrderId(exec.getClientOrderId())
                    .exchangeOrderId(exec.getExchangeOrderId())
                    .tpTriggerId(exec.getTpTriggerId())
                    .slTriggerId(exec.getSlTriggerId())
                    .exchange(config.getExchange())
                    .dryRun(false)
                    .openTimestamp(System.currentTimeMillis())
                    .score(exec.getScore())
                    .build();
            addPosition(pos);

            journalService.record(exec, "FILLED", exec.getScore(), exec.getTimeframe());
            log.info("[FILL] Limit order filled: {} {} @ {}",
                    exec.getDirection(), exec.getPair(), exec.getFillPrice());
        }
    }

    public void checkPositions(Map<String, List<Candle>> candleCache) {
        for (var entry : List.copyOf(openPositions.entrySet())) {
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
                            double oldSl = pos.getStopLoss();
                            pos.setStopLoss(trailSl);
                            updateExchangeStopLoss(pos, trailSl, "TRAILING");
                            log.debug("[TRAILING] {} SL {} → {}", pos.getPair(), oldSl, trailSl);
                        }
                    } else {
                        trailSl = last.getClose() + tfSettings.getTrailingStopAtrMult() * atr[lastIdx];
                        if (trailSl < pos.getStopLoss()) {
                            double oldSl = pos.getStopLoss();
                            pos.setStopLoss(trailSl);
                            updateExchangeStopLoss(pos, trailSl, "TRAILING");
                            log.debug("[TRAILING] {} SL {} → {}", pos.getPair(), oldSl, trailSl);
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
                    newSl = isLong ? pos.getEntryPrice() + 0.5 * tpDist : pos.getEntryPrice() - 0.5 * tpDist;
                    if (isLong ? newSl > pos.getStopLoss() : newSl < pos.getStopLoss()) {
                        pos.setStopLoss(newSl);
                        pos.setBeLevel(3);
                        pos.setBreakEvenApplied(true);
                        updateExchangeStopLoss(pos, newSl, "BE-L3");
                        log.info("[BE-L3] {} SL locked at 50% TP: {}", pos.getPair(), newSl);
                    }
                } else if (progress >= bePercent + 15 && pos.getBeLevel() < 2) {
                    newSl = isLong ? pos.getEntryPrice() + 0.25 * tpDist : pos.getEntryPrice() - 0.25 * tpDist;
                    if (isLong ? newSl > pos.getStopLoss() : newSl < pos.getStopLoss()) {
                        pos.setStopLoss(newSl);
                        pos.setBeLevel(2);
                        pos.setBreakEvenApplied(true);
                        updateExchangeStopLoss(pos, newSl, "BE-L2");
                        log.info("[BE-L2] {} SL locked at 25% TP: {}", pos.getPair(), newSl);
                    }
                } else if (progress >= bePercent && pos.getBeLevel() < 1) {
                    newSl = pos.getEntryPrice();
                    if (isLong ? newSl > pos.getStopLoss() : newSl < pos.getStopLoss()) {
                        pos.setStopLoss(newSl);
                        pos.setBeLevel(1);
                        pos.setBreakEvenApplied(true);
                        updateExchangeStopLoss(pos, newSl, "BE-L1");
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

            // Check SL/TP hit on current candle (dry-run only — live relies on exchange
            // triggers)
            if (pos.isDryRun()) {
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
    }

    /**
     * Sync with exchange — detect positions closed by TP/SL triggers.
     * Called periodically (every ~15s in live mode).
     *
     * CRITICAL: If exchange returns 0 positions but we track N > 0,
     * this could be an API error (429 rate limit) — DO NOT treat as "all closed".
     * (t-bot bug #13)
     */
    public void syncWithExchange() {
        if (!config.isLiveTrading())
            return;
        if (openPositions.isEmpty())
            return;

        try {
            List<Map<String, Object>> exchangePositions = executionService.getExchangePositions();

            // Safety guard: if exchange returns 0 but we track >0, might be API error
            if (exchangePositions.isEmpty() && openPositions.size() > 0) {
                log.warn("[SYNC] Exchange returned 0 positions but tracking {}. " +
                        "Possible API error — skipping sync to avoid false SL_HIT",
                        openPositions.size());
                return;
            }

            // Build set of coins that have positions on exchange
            java.util.Set<String> exchangeCoins = new java.util.HashSet<>();
            for (Map<String, Object> ep : exchangePositions) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pos = (Map<String, Object>) ep.get("position");
                if (pos != null) {
                    exchangeCoins.add(String.valueOf(pos.get("coin")));
                }
            }

            // Check each tracked position: if not on exchange, it was closed (TP/SL hit)
            for (var entry : List.copyOf(openPositions.entrySet())) {
                OpenPosition pos = entry.getValue();
                String coin = HyperliquidMarketDataService.toHyperliquidCoin(pos.getPair());

                if (!exchangeCoins.contains(coin)) {
                    // Position no longer on exchange — closed by trigger order
                    log.info("[SYNC] Position {} {} no longer on exchange — trigger hit",
                            pos.getDirection(), pos.getPair());

                    // Determine if TP or SL hit based on current price
                    double currentPrice = pos.getCurrentPrice();
                    String reason;
                    double exitPrice;
                    if ("LONG".equals(pos.getDirection())) {
                        if (currentPrice >= pos.getTakeProfit() * 0.998) {
                            reason = "TP_HIT";
                            exitPrice = pos.getTakeProfit();
                        } else {
                            reason = "SL_HIT";
                            exitPrice = pos.getStopLoss();
                        }
                    } else {
                        if (currentPrice <= pos.getTakeProfit() * 1.002) {
                            reason = "TP_HIT";
                            exitPrice = pos.getTakeProfit();
                        } else {
                            reason = "SL_HIT";
                            exitPrice = pos.getStopLoss();
                        }
                    }

                    // Remove from tracking (bypass closePosition to avoid re-closing on exchange)
                    openPositions.remove(entry.getKey());
                    contrarySignalCounts.remove(pos.getPair());
                    lastContraryDirection.remove(pos.getPair());

                    pos.setCloseReason(reason);
                    historyService.recordClose(pos, exitPrice, reason, pos.getCandlesElapsed());

                    long cooldownMs = config.getCooldownMultiplier()
                            * config.getTimeframeDurationMs(pos.getTimeframe());
                    cooldowns.put(entry.getKey(), System.currentTimeMillis() + cooldownMs);

                    double pnl = calcPnlPercent(pos, exitPrice);
                    log.info("[SYNC] Closed {} {} {} @ {} reason={} PnL={}%",
                            pos.getDirection(), pos.getPair(), pos.getTimeframe(),
                            exitPrice, reason, String.format("%.2f", pnl));
                }
            }
        } catch (Exception e) {
            // Don't swallow — log and continue (t-bot bug #13)
            log.error("[SYNC] syncWithExchange failed: {} — positions still tracked", e.getMessage());
        }
    }

    /**
     * Recover positions from exchange at startup.
     * Rebuilds tracking with conservative parameters.
     */
    public void recoverPositions() {
        if (!config.isLiveTrading())
            return;

        try {
            List<Map<String, Object>> exchangePositions = executionService.getExchangePositions();
            if (exchangePositions.isEmpty()) {
                log.info("[RECOVERY] No positions on exchange to recover");
                return;
            }

            for (Map<String, Object> ep : exchangePositions) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pos = (Map<String, Object>) ep.get("position");
                if (pos == null)
                    continue;

                String coin = String.valueOf(pos.get("coin"));
                double szi = toDouble(pos.get("szi"));
                if (szi == 0)
                    continue;

                String direction = szi > 0 ? "LONG" : "SHORT";
                double qty = Math.abs(szi);
                double entryPx = toDouble(pos.get("entryPx"));

                // Leverage: Hyperliquid returns it in different formats depending on context
                int leverage = 1;
                Object levObj = pos.get("leverage");
                if (levObj instanceof Number n) {
                    leverage = Math.max(1, n.intValue());
                } else if (levObj instanceof Map<?, ?> levMap) {
                    // {type: "cross", value: 10}
                    leverage = Math.max(1, (int) toDouble(levMap.get("value")));
                } else if (levObj instanceof String s) {
                    try { leverage = Math.max(1, Integer.parseInt(s)); } catch (Exception ignored) {}
                }

                // Use first configured timeframe as default
                String tf = config.getTimeframes().isEmpty() ? "3m" : config.getTimeframes().get(0);

                // Conservative SL/TP based on maxSlPercent
                double maxSl = config.getEffectiveMaxSl(tf) / 100.0;
                double slDist = entryPx * maxSl;
                double sl, tp;
                if ("LONG".equals(direction)) {
                    sl = entryPx - slDist;
                    tp = entryPx + slDist * 2;
                } else {
                    sl = entryPx + slDist;
                    tp = entryPx - slDist * 2;
                }

                double currentPrice = entryPx; // will be updated on next price refresh

                // Detect if break-even already applied (SL near entry = BE)
                boolean beApplied = Math.abs(sl - entryPx) / entryPx < 0.002;
                double originalSl = beApplied ? sl * (direction.equals("LONG") ? 0.98 : 1.02) : sl;

                OpenPosition recovered = OpenPosition.builder()
                        .pair(coin).timeframe(tf)
                        .direction(direction)
                        .entryPrice(entryPx)
                        .stopLoss(sl).takeProfit(tp)
                        .originalStopLoss(originalSl)
                        .leverage(leverage).quantity(qty)
                        .currentPrice(currentPrice)
                        .exchange("hyperliquid")
                        .dryRun(false)
                        .openTimestamp(System.currentTimeMillis())
                        .breakEvenApplied(beApplied)
                        .beLevel(beApplied ? 1 : 0)
                        .build();

                String key = coin + ":" + tf;
                openPositions.put(key, recovered);
                log.info("[RECOVERY] Recovered {} {} {} @ {} qty={} lev={}x SL={} TP={}",
                        direction, coin, tf, entryPx, qty, leverage, sl, tp);
            }
            log.info("[RECOVERY] Recovered {} positions from exchange", openPositions.size());
        } catch (Exception e) {
            log.error("[RECOVERY] Failed to recover positions: {}", e.getMessage());
        }
    }

    public void closePosition(String key, double exitPrice, String reason) {
        OpenPosition pos = openPositions.remove(key);
        if (pos == null)
            return;

        contrarySignalCounts.remove(pos.getPair());
        lastContraryDirection.remove(pos.getPair());

        // Close on exchange if live (t-bot bug #1: checkNaturalClose without
        // closePosition)
        if (config.isLiveTrading() && !pos.isDryRun()) {
            boolean closed = executionService.closePosition(pos);
            if (!closed) {
                log.error("[POSITION] FAILED to close {} {} on exchange — manual intervention needed",
                        pos.getDirection(), pos.getPair());
            }
        }

        pos.setCloseReason(reason);
        historyService.recordClose(pos, exitPrice, reason, pos.getCandlesElapsed());

        long cooldownMs = config.getCooldownMultiplier() * config.getTimeframeDurationMs(pos.getTimeframe());
        cooldowns.put(key, System.currentTimeMillis() + cooldownMs);

        double pnl = calcPnlPercent(pos, exitPrice);
        log.info("[POSITION] Closed {} {} {} @ {} reason={} PnL={}%",
                pos.getDirection(), pos.getPair(), pos.getTimeframe(),
                exitPrice, reason, String.format("%.2f", pnl));
    }

    /**
     * Update SL trigger order on the exchange (cancel old + place new).
     * Used for trailing stop and break-even updates.
     * (t-bot bug #7: BE not updated on exchange)
     */
    private void updateExchangeStopLoss(OpenPosition pos, double newSlPrice, String reason) {
        if (!config.isLiveTrading() || pos.isDryRun())
            return;

        String newOid = executionService.updateStopLoss(pos, newSlPrice);
        if (newOid != null) {
            pos.setSlTriggerId(newOid);
            log.info("[SL-UPDATE] {} {} SL updated on exchange to {} ({}), newOid={}",
                    pos.getDirection(), pos.getPair(), newSlPrice, reason, newOid);
        } else {
            log.warn(
                    "[SL-UPDATE] Failed to update SL on exchange for {} — local SL updated but exchange may have stale SL",
                    pos.getPair());
        }
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

    public double getRealizedPnl() {
        return historyService.getRealizedPnlUsd();
    }

    public boolean hasPendingOrders() {
        return executionService.hasPendingOrders();
    }

    public int pendingOrderCount() {
        return executionService.pendingOrderCount();
    }

    /**
     * Records a contrary signal for a coin. Returns true if the threshold is
     * reached and the existing position was auto-closed (CONTRARY_SIGNALS).
     */
    public boolean recordContrarySignal(String pair, String signalDirection, int threshold) {
        OpenPosition existing = openPositions.values().stream()
                .filter(p -> p.getPair().equals(pair))
                .findFirst().orElse(null);

        if (existing == null)
            return false;
        if (existing.getDirection().equals(signalDirection))
            return false;

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

    private static double calcPnlPercent(OpenPosition pos, double exitPrice) {
        if ("LONG".equals(pos.getDirection())) {
            return (exitPrice - pos.getEntryPrice()) / pos.getEntryPrice() * 100 * pos.getLeverage();
        } else {
            return (pos.getEntryPrice() - exitPrice) / pos.getEntryPrice() * 100 * pos.getLeverage();
        }
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n)
            return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
