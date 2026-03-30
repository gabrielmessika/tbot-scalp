package com.tbot.scalp.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.config.StartupRunner;
import com.tbot.scalp.model.AnalysisResult;
import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.Signal;
import com.tbot.scalp.model.TradeExecution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Scheduled tasks for live trading loop.
 *
 * Three independent tasks on separate schedules:
 * 1. Analysis + signal execution (every 60s for 1m TF, aligned to candle close)
 * 2. Pending order polling (every 10s — check limit order fills + expire stale
 * orders)
 * 3. Exchange sync (every 15s — detect TP/SL hits, price refresh)
 *
 * All tasks are wrapped in try-catch to prevent scheduler thread death (t-bot
 * bug #14).
 * Spring scheduler pool.size=3 ensures these run independently.
 */
@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class ScheduledTaskService {

    private final ScalpConfig config;
    private final StartupRunner startupRunner;
    private final AnalysisService analysisService;
    private final OrderManagerService orderManager;
    private final PositionManagerService positionManager;
    private final HyperliquidMarketDataService marketDataService;

    /**
     * Main analysis loop: detect signals, execute orders.
     * Runs every 60s (aligned to 1m candle boundaries).
     * For 3m timeframe, signals are detected every minute but only new candles
     * trigger signals.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void scheduledAnalysis() {
        if (!startupRunner.isStartupComplete())
            return;
        if (!config.isAutoTrade())
            return;

        try {
            log.debug("[SCHEDULER] Running analysis cycle...");
            AnalysisResult result = analysisService.runCurrentAnalysis();

            if (result != null && result.getAlerts() != null && !result.getAlerts().isEmpty()) {
                List<Signal> signals = result.getAlerts();
                log.info("[SCHEDULER] {} actionable signals found, processing...", signals.size());
                List<TradeExecution> executions = orderManager.processSignals(signals);

                long filled = executions.stream().filter(e -> "FILLED".equals(e.getStatus())).count();
                long pending = executions.stream().filter(e -> "PENDING_FILL".equals(e.getStatus())).count();
                long rejected = executions.stream().filter(e -> "REJECTED".equals(e.getStatus())).count();
                long dryRun = executions.stream().filter(e -> "DRY_RUN".equals(e.getStatus())).count();

                if (filled + pending + dryRun > 0) {
                    log.info("[SCHEDULER] Execution results: filled={} pending={} dryRun={} rejected={}",
                            filled, pending, dryRun, rejected);
                }
            }
        } catch (Exception e) {
            // CRITICAL: catch ALL exceptions to prevent scheduler thread death (t-bot bug
            // #14)
            log.error("[SCHEDULER] Analysis cycle failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Poll pending limit orders for fills and expiration.
     * Runs every 10s — fast enough to detect fills before next candle.
     *
     * Pending GTC limit orders that are not filled within 1 candle duration
     * are automatically cancelled to avoid lingering in the order book.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 30_000)
    public void checkPendingOrders() {
        if (!startupRunner.isStartupComplete())
            return;
        if (!config.isLiveTrading())
            return;
        if (!positionManager.hasPendingOrders())
            return;

        try {
            log.debug("[SCHEDULER] Checking {} pending orders...", positionManager.pendingOrderCount());
            positionManager.processPendingFills();
        } catch (Exception e) {
            log.error("[SCHEDULER] checkPendingOrders failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Sync with exchange: detect positions closed by triggers, update prices.
     * Also checks position lifecycle (trailing stop, break-even, timeout).
     * Runs every 15s.
     *
     * Separated from analysis to avoid coupling lifecycle checks to signal
     * detection.
     */
    @Scheduled(fixedDelay = 15_000, initialDelay = 45_000)
    public void syncAndCheckPositions() {
        if (!startupRunner.isStartupComplete())
            return;
        if (!config.isAutoTrade())
            return;

        try {
            // Sync with exchange first (detect trigger hits)
            if (config.isLiveTrading()) {
                positionManager.syncWithExchange();
            }

            // Then check position lifecycle (trailing, BE, trend, timeout)
            if (!positionManager.getOpenPositions().isEmpty()) {
                Map<String, List<Candle>> candleCache = fetchCandleCacheForPositions();
                positionManager.checkPositions(candleCache);
            }
        } catch (Exception e) {
            log.error("[SCHEDULER] syncAndCheckPositions failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Fetch recent candles for all open positions.
     * Uses a minimal window (2 days of data for indicators).
     */
    private Map<String, List<Candle>> fetchCandleCacheForPositions() {
        Map<String, List<Candle>> cache = new HashMap<>();
        for (var pos : positionManager.getOpenPositions()) {
            String key = pos.getPair() + ":" + pos.getTimeframe();
            if (!cache.containsKey(key)) {
                try {
                    List<Candle> candles = marketDataService.fetchCandles(pos.getPair(), pos.getTimeframe(), 0, 2);
                    cache.put(key, candles);
                } catch (Exception e) {
                    log.warn("[SCHEDULER] Failed to fetch candles for {}: {}", key, e.getMessage());
                }
            }
        }
        return cache;
    }
}
