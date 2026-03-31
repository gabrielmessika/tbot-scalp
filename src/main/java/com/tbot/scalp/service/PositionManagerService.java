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

            // Collect vanished positions
            List<Map.Entry<String, OpenPosition>> vanished = new ArrayList<>();
            for (var entry : List.copyOf(openPositions.entrySet())) {
                String coin = HyperliquidMarketDataService.toHyperliquidCoin(entry.getValue().getPair());
                if (!exchangeCoins.contains(coin)) {
                    vanished.add(entry);
                }
            }

            if (!vanished.isEmpty()) {
                // Query trigger orders + real fill prices once for ALL vanished positions
                java.util.Set<String> openTriggerOids = executionService.getOpenTriggerOrderIds();
                long sinceMs = java.time.Instant.now().minusSeconds(2 * 3600).toEpochMilli();
                Map<String, Double> realFillPrices = executionService.getRecentCloseFillPrices(sinceMs);

                for (var entry : vanished) {
                    OpenPosition pos = entry.getValue();
                    String coin = HyperliquidMarketDataService.toHyperliquidCoin(pos.getPair());

                    log.info("[SYNC] Position {} {} no longer on exchange — determining close reason",
                            pos.getDirection(), pos.getPair());

                    // 1. Determine close reason from trigger OIDs (authoritative)
                    String reason = inferCloseReason(pos, openTriggerOids);

                    // 2. Use real fill price from exchange if available
                    Double realFill = realFillPrices.get(coin);
                    double exitPrice;
                    if (realFill != null && realFill > 0) {
                        exitPrice = realFill;
                        log.info("[SYNC] {} {} real fill from exchange: {} (SL={}, TP={})",
                                pos.getPair(), reason, exitPrice, pos.getStopLoss(), pos.getTakeProfit());
                    } else {
                        // Fallback to theoretical level based on reason
                        exitPrice = "TP_HIT".equals(reason) ? pos.getTakeProfit() : pos.getStopLoss();
                        log.info("[SYNC] {} {} no real fill found, using theoretical: {}",
                                pos.getPair(), reason, exitPrice);
                    }

                    // Remove from tracking (bypass closePosition to avoid re-closing on exchange)
                    openPositions.remove(entry.getKey());
                    contrarySignalCounts.remove(pos.getPair());
                    lastContraryDirection.remove(pos.getPair());

                    pos.setCloseReason(reason);
                    historyService.recordClose(pos, pos.getClientOrderId(), exitPrice, reason,
                            pos.getCandlesElapsed());

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
     * Determine close reason by checking which trigger orders are still pending.
     * When HL executes TP, it cancels SL (and vice versa).
     * - TP trigger still pending → SL was executed → SL_HIT
     * - SL trigger still pending → TP was executed → TP_HIT
     * - Neither/both → fallback to price proximity
     */
    private String inferCloseReason(OpenPosition pos, java.util.Set<String> openTriggerOids) {
        String tpOid = pos.getTpTriggerId();
        String slOid = pos.getSlTriggerId();

        if (tpOid != null || slOid != null) {
            boolean tpStillOpen = tpOid != null && openTriggerOids.contains(tpOid);
            boolean slStillOpen = slOid != null && openTriggerOids.contains(slOid);

            if (slStillOpen && !tpStillOpen) {
                log.debug("[SYNC] {} — TP gone, SL {} still open → TP_HIT", pos.getPair(), slOid);
                return "TP_HIT";
            }
            if (tpStillOpen && !slStillOpen) {
                log.debug("[SYNC] {} — SL gone, TP {} still open → SL_HIT", pos.getPair(), tpOid);
                return "SL_HIT";
            }
            log.debug("[SYNC] {} — TP open={}, SL open={} — falling back to price inference",
                    pos.getPair(), tpStillOpen, slStillOpen);
        }

        // Fallback: infer from current price proximity
        double distToTp = Math.abs(pos.getCurrentPrice() - pos.getTakeProfit());
        double distToSl = Math.abs(pos.getCurrentPrice() - pos.getStopLoss());
        String reason = distToTp <= distToSl ? "TP_HIT" : "SL_HIT";
        log.debug("[SYNC] {} price={} distTP={} distSL={} → {}",
                pos.getPair(), pos.getCurrentPrice(), distToTp, distToSl, reason);
        return reason;
    }

    /**
     * Recover positions from exchange at startup.
     * Rebuilds tracking with conservative parameters.
     * Only recovers positions that have a matching journal entry (= opened by the
     * bot).
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

            // Build set of coins that the bot has journal entries for (FILLED or
            // PENDING_FILL)
            java.util.Set<String> botCoins = new java.util.HashSet<>();
            List<Map<String, Object>> journalEntries = journalService.readAllParsed();
            for (Map<String, Object> je : journalEntries) {
                String status = String.valueOf(je.getOrDefault("status", ""));
                if ("FILLED".equals(status) || "PENDING_FILL".equals(status) || "DRY_RUN".equals(status)) {
                    botCoins.add(String.valueOf(je.getOrDefault("pair", "")));
                }
            }
            log.info("[RECOVERY] Known bot coins from journal: {}", botCoins);

            for (Map<String, Object> ep : exchangePositions) {
                @SuppressWarnings("unchecked")
                Map<String, Object> pos = (Map<String, Object>) ep.get("position");
                if (pos == null)
                    continue;

                String coin = String.valueOf(pos.get("coin"));
                double szi = toDouble(pos.get("szi"));
                if (szi == 0)
                    continue;

                // Skip positions not opened by the bot (no journal entry)
                if (!botCoins.contains(coin)) {
                    log.info("[RECOVERY] Skipping pre-bot position {} (no journal entry)", coin);
                    continue;
                }

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
                    try {
                        leverage = Math.max(1, Integer.parseInt(s));
                    } catch (Exception ignored) {
                    }
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

                // Fetch real trigger orders from exchange to override conservative SL/TP
                // (t-bot bug #9: break-even lost at restart if we don't use real trigger
                // prices)
                String slTriggerId = null;
                String tpTriggerId = null;
                try {
                    double[] triggers = executionService.getTriggerPricesForCoin(coin);
                    if (triggers[0] > 0)
                        tp = triggers[0];
                    if (triggers[1] > 0)
                        sl = triggers[1];
                } catch (Exception e) {
                    log.warn("[RECOVERY] Could not fetch trigger prices for {}: {}", coin, e.getMessage());
                }

                double currentPrice = entryPx; // will be updated on next price refresh

                // Detect if break-even already applied (real SL near entry = BE)
                boolean beApplied = Math.abs(sl - entryPx) / entryPx < 0.002;
                double originalSl = beApplied ? (direction.equals("LONG") ? entryPx - slDist : entryPx + slDist) : sl;

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
        historyService.recordClose(pos, pos.getClientOrderId(), exitPrice, reason, pos.getCandlesElapsed());

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

    public List<Map<String, Object>> getPendingOrderDetails() {
        return executionService.getPendingOrderDetails();
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
