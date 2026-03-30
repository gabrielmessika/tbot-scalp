package com.tbot.scalp.service;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Shared rate limiter for all Hyperliquid /info and /exchange API calls.
 * Both {@link HyperliquidMarketDataService} and
 * {@link HyperliquidExecutionService}
 * use this single instance so that candle fetches, price lookups, and execution
 * queries
 * all share the same budget.
 * <p>
 * Hyperliquid budget: 1200 weight/min (per IP).
 * <p>
 * Actual weights (from official docs):
 * <ul>
 * <li>candleSnapshot: 20 (base) + additional per 60 items returned</li>
 * <li>allMids, clearinghouseState, orderStatus: 2</li>
 * <li>All other /info requests (openOrders, meta, etc.): 20</li>
 * </ul>
 * Ms per weight unit: 60000 / 1200 = 50ms; with 10% safety margin → 55ms.
 */
@Slf4j
@Component
public class HyperliquidRateLimiter {

    public static final int INFO_LIGHT_WEIGHT = 2;
    public static final int INFO_HEAVY_WEIGHT = 20;
    public static final int CANDLE_BASE_WEIGHT = 20;
    private static final double MS_PER_WEIGHT = 55.0;

    private long nextAllowedMs = 0;

    /** Acquire for a candleSnapshot: weight = 20 + ceil(estimatedCandles / 60). */
    public synchronized void acquireCandle(int estimatedCandleCount) {
        int additional = Math.max(1, (estimatedCandleCount + 59) / 60);
        acquire(CANDLE_BASE_WEIGHT + additional);
    }

    /**
     * Acquire for a light /info request (weight 2): allMids, clearinghouseState.
     */
    public synchronized void acquireInfo() {
        acquire(INFO_LIGHT_WEIGHT);
    }

    /** Acquire for a heavy /info request (weight 20): meta, openOrders, etc. */
    public synchronized void acquireInfoHeavy() {
        acquire(INFO_HEAVY_WEIGHT);
    }

    /** Acquire for /exchange calls (weight 20). */
    public synchronized void acquireExchange() {
        acquire(INFO_HEAVY_WEIGHT);
    }

    private void acquire(int weight) {
        long now = System.currentTimeMillis();
        long waitMs = nextAllowedMs - now;
        if (waitMs > 0) {
            log.debug("Rate limit: waiting {}ms before next request (weight {})", waitMs, weight);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        nextAllowedMs = Math.max(System.currentTimeMillis(), nextAllowedMs) + (long) (weight * MS_PER_WEIGHT);
    }
}
