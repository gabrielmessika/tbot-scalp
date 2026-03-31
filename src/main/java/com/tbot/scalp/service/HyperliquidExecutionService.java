package com.tbot.scalp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.OpenPosition;
import com.tbot.scalp.model.TradeExecution;
import com.tbot.scalp.model.TradeOrder;

import lombok.extern.slf4j.Slf4j;

/**
 * Live order execution on Hyperliquid for tbot-scalp.
 *
 * Key differences from t-bot:
 * - Entry orders are LIMIT GTC (maker rebate) instead of IOC (taker)
 * - Pending limit orders are tracked and cancelled after N candles if unfilled
 * - TP/SL trigger orders placed after fill confirmation
 * - SL cancel+replace on break-even trigger
 *
 * Known issues from t-bot (pre-empted here):
 * - RestTemplate must have 30s timeout (blocks scheduler thread indefinitely
 * otherwise)
 * - getOpenPositions() must propagate exceptions (not swallow) to avoid false
 * SL_HIT
 * - Trigger order responses must be checked for "err" status
 * - Rate limit 429: candleSnapshot=20+, meta/openOrders=20, allMids=2
 */
@Slf4j
@Service
@SuppressWarnings("unchecked")
public class HyperliquidExecutionService {

    private final ScalpConfig config;
    private final HyperliquidSigner signer;
    private final HyperliquidMarketDataService marketDataService;
    private final HyperliquidRateLimiter rateLimiter;
    private final RestTemplate restTemplate;

    // Pending limit orders: clientOrderId → PendingOrder
    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    public HyperliquidExecutionService(ScalpConfig config, HyperliquidMarketDataService marketDataService,
            HyperliquidRateLimiter rateLimiter) {
        this.config = config;
        this.marketDataService = marketDataService;
        this.rateLimiter = rateLimiter;

        // RestTemplate with 30s timeout — CRITICAL: default has no timeout,
        // which blocks the scheduler thread indefinitely on slow/hanging requests
        // (t-bot bug #14)
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);

        String pk = config.getHyperliquidPrivateKey();
        if (pk != null && !pk.isBlank()) {
            this.signer = new HyperliquidSigner(pk);
            log.info("[EXECUTION] HyperliquidExecutionService initialized, wallet={}",
                    signer.getAddress());
        } else {
            this.signer = null;
            log.warn("[EXECUTION] No private key configured — live execution disabled");
        }
    }

    // ==================== Balance ====================

    /**
     * Get available balance (free collateral) from exchange.
     * Returns Double.NaN on API failure to distinguish from legitimate
     * zero/negative balance.
     */
    public double getAvailableBalance() {
        try {
            String wallet = walletAddress();
            if (wallet == null) {
                if (config.isLiveTrading()) {
                    log.error("[EXECUTION] Live mode but no wallet configured — cannot fetch balance");
                    return Double.NaN;
                }
                return config.getDryRunBalance();
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "clearinghouseState");
            req.put("user", wallet);
            Map<String, Object> res = postInfo(req);
            if (res == null)
                return Double.NaN;
            Map<String, Object> marginSummary = (Map<String, Object>) res.get("marginSummary");
            if (marginSummary == null)
                return Double.NaN;
            double accountValue = toDouble(marginSummary.get("accountValue"));
            double totalMarginUsed = toDouble(marginSummary.get("totalMarginUsed"));

            // Unified account (Portfolio Margin): USDC sits in spot, used as perps
            // collateral.
            // On unified, spot available = what can be used for new positions.
            double[] spotInfo = getSpotUsdcInfo(); // [total, hold]
            double spotTotal = spotInfo[0];
            double spotBalance = spotTotal - spotInfo[1]; // total - hold
            if (spotBalance > 0 || spotTotal > 0) {
                log.debug("[EXECUTION] Unified balance: spotAvailable={}", String.format("%.2f", spotBalance));
                return Math.max(0, spotBalance);
            }

            // Non-unified: perps balance is what's available
            double perpsBalance = accountValue - totalMarginUsed;
            return perpsBalance;
        } catch (Exception e) {
            log.warn("[EXECUTION] Failed to fetch balance: {}", e.getMessage());
            return Double.NaN;
        }
    }

    /**
     * Get total equity (accountValue) from exchange.
     * Returns Double.NaN on API failure to distinguish from legitimate zero
     * balance.
     */
    public double getEquity() {
        try {
            String wallet = walletAddress();
            if (wallet == null) {
                if (config.isLiveTrading()) {
                    log.error("[EXECUTION] Live mode but no wallet configured — cannot fetch equity");
                    return Double.NaN;
                }
                return config.getDryRunBalance();
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "clearinghouseState");
            req.put("user", wallet);
            Map<String, Object> res = postInfo(req);
            if (res == null)
                return Double.NaN;
            Map<String, Object> marginSummary = (Map<String, Object>) res.get("marginSummary");
            if (marginSummary == null)
                return Double.NaN;
            double accountValue = toDouble(marginSummary.get("accountValue"));
            double totalMarginUsed = toDouble(marginSummary.get("totalMarginUsed"));

            // Unified account (Portfolio Margin): USDC sits in spot, used as perps
            // collateral.
            // Single API call to get both total and hold — avoids race condition
            // where two separate calls could return inconsistent hold values.
            // spotTotal includes spotHold (collateral allocated to perps).
            // accountValue on perps side ≈ spotHold + unrealized PnL.
            // Correct equity = spotAvailable (total - hold) + perps accountValue
            double[] spotInfo = getSpotUsdcInfo(); // [total, hold]
            double spotTotal = spotInfo[0];
            double spotHold = spotInfo[1];
            double spotBalance = spotTotal - spotHold;

            if (spotTotal > 0) {
                // Cross-validate: if perps has margin used but hold=0, the spot API
                // may be lagging (race at startup). Using spotBalance would equal
                // spotTotal, causing double-counting with accountValue.
                if (spotHold == 0 && totalMarginUsed > 0) {
                    log.warn("[EXECUTION] Equity race detected: spotHold=0 but marginUsed={} — "
                            + "using spotTotal={} as conservative equity (ignoring unrealized PnL)",
                            String.format("%.2f", totalMarginUsed),
                            String.format("%.2f", spotTotal));
                    return spotTotal;
                }
                double equity = spotBalance + accountValue;
                log.debug("[EXECUTION] Equity: spotBalance={} (total={} - hold={}) + accountValue={} = {}",
                        String.format("%.2f", spotBalance),
                        String.format("%.2f", spotTotal),
                        String.format("%.2f", spotHold),
                        String.format("%.2f", accountValue),
                        String.format("%.2f", equity));
                return equity;
            }

            // Fallback: no spot detected — use accountValue alone (non-unified account)
            if (accountValue > 0) {
                log.debug("[EXECUTION] Equity (non-unified fallback): accountValue={}",
                        String.format("%.2f", accountValue));
                return accountValue;
            }

            // Both perps and spot returned 0 — likely API issue on unified account
            // Return NaN to avoid false 100% drawdown (same pattern as getAvailableBalance)
            if (config.isLiveTrading()) {
                log.warn("[EXECUTION] Equity=0 on live account — likely API issue, returning NaN");
                return Double.NaN;
            }
            return 0;
        } catch (Exception e) {
            log.warn("[EXECUTION] Failed to fetch equity: {}", e.getMessage());
            return Double.NaN;
        }
    }

    /**
     * Fetch current mid price for a coin (delegates to marketDataService).
     */
    public double fetchCurrentPrice(String coin) {
        return marketDataService.fetchCurrentPrice(coin);
    }

    // ==================== Place Limit Order (Maker) ====================

    /**
     * Place a GTC limit order at the signal price (maker — no slippage, rebate).
     * If the limit crosses the book (price better than market), it fills
     * immediately.
     *
     * Returns:
     * - PENDING_FILL if order rests in the book (polled later by
     * checkPendingOrders)
     * - FILLED if order fills immediately (TP/SL placed right away)
     * - REJECTED on error
     */
    public TradeExecution placeLimitOrder(TradeOrder order) {
        if (signer == null) {
            return buildRejected(order, "No private key configured");
        }

        String coin = toHyperliquidCoin(order.getPair());
        boolean isBuy = "LONG".equals(order.getDirection());

        try {
            int assetIndex = marketDataService.getAssetIndex(order.getPair());
            int szDecimals = marketDataService.getSzDecimals(order.getPair());

            // Cap leverage to exchange max
            int maxLev = marketDataService.getMaxLeverage(order.getPair());
            int leverage = Math.min(order.getLeverage(), maxLev > 0 ? maxLev : 50);
            setLeverage(assetIndex, leverage);

            // Quantity rounding
            double qty = BigDecimal.valueOf(order.getQuantity())
                    .setScale(szDecimals, RoundingMode.DOWN).doubleValue();
            // Ensure $10 minimum notional
            double midPrice = marketDataService.fetchCurrentPrice(order.getPair());
            if (qty * midPrice < 10.0) {
                qty = BigDecimal.valueOf(order.getQuantity())
                        .setScale(szDecimals, RoundingMode.UP).doubleValue();
            }
            if (qty <= 0) {
                return buildRejected(order, "Quantity rounds to 0");
            }

            // Limit price = signal entry price (we want to be maker, so post at signal
            // price)
            double limitPx = order.getEntryPrice();

            long nonce = Instant.now().toEpochMilli();
            Map<String, Object> orderSpec = new LinkedHashMap<>();
            orderSpec.put("a", assetIndex);
            orderSpec.put("b", isBuy);
            orderSpec.put("p", formatPrice(limitPx, szDecimals));
            orderSpec.put("s", formatSize(qty, szDecimals));
            orderSpec.put("r", false);
            orderSpec.put("t", orderedMap("limit", orderedMap("tif", "Gtc"))); // GTC = maker

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "order");
            action.put("orders", List.of(orderSpec));
            action.put("grouping", "na");

            Map<String, Object> response = postExchange(action, nonce);
            if (response == null)
                return buildRejected(order, "API returned null");
            if ("err".equals(response.get("status"))) {
                return buildRejected(order, "API error: " + response.get("response"));
            }

            // Check if immediately filled or resting
            OrderResult orderResult = parseOrderResult(response);
            if (orderResult == null)
                return buildRejected(order, "Could not parse order response");
            if (orderResult.error != null)
                return buildRejected(order, orderResult.error);

            if (orderResult.immediatelyFilled) {
                // Limit order crossed the book — filled immediately (acts like taker)
                log.info("[EXECUTION] Limit order IMMEDIATELY FILLED {} {} @ {} oid={}",
                        order.getDirection(), coin, limitPx, orderResult.oid);

                // Place TP/SL trigger orders
                String[] triggerOids = placeTriggerOrders(assetIndex, isBuy, qty, szDecimals,
                        order.getTakeProfit(), order.getStopLoss(), order.getPair());

                return TradeExecution.builder()
                        .clientOrderId(order.getClientOrderId())
                        .exchangeOrderId(orderResult.oid)
                        .pair(order.getPair())
                        .direction(order.getDirection())
                        .timeframe(order.getTimeframe())
                        .fillPrice(limitPx)
                        .quantity(qty)
                        .leverage(leverage)
                        .stopLoss(order.getStopLoss())
                        .takeProfit(order.getTakeProfit())
                        .tpTriggerId(triggerOids != null ? triggerOids[0] : null)
                        .slTriggerId(triggerOids != null ? triggerOids[1] : null)
                        .status("FILLED")
                        .dryRun(false)
                        .executionTimestamp(System.currentTimeMillis())
                        .exchange("hyperliquid")
                        .score(order.getScore())
                        .build();
            } else {
                // Order rests in the book — track as pending, poll for fill later
                long candleDuration = config.getTimeframeDurationMs(order.getTimeframe());
                long expiresAt = System.currentTimeMillis() + candleDuration; // cancel after 1 candle

                PendingOrder pending = new PendingOrder(order, orderResult.oid, qty, leverage, expiresAt);
                pendingOrders.put(order.getClientOrderId(), pending);

                log.info("[EXECUTION] Limit order RESTING {} {} @ {} oid={} expires in {}s",
                        order.getDirection(), coin, limitPx, orderResult.oid, candleDuration / 1000);

                return TradeExecution.builder()
                        .clientOrderId(order.getClientOrderId())
                        .exchangeOrderId(orderResult.oid)
                        .pair(order.getPair())
                        .direction(order.getDirection())
                        .timeframe(order.getTimeframe())
                        .fillPrice(limitPx)
                        .quantity(qty)
                        .leverage(leverage)
                        .stopLoss(order.getStopLoss())
                        .takeProfit(order.getTakeProfit())
                        .status("PENDING_FILL")
                        .dryRun(false)
                        .executionTimestamp(System.currentTimeMillis())
                        .exchange("hyperliquid")
                        .score(order.getScore())
                        .build();
            }

        } catch (Exception e) {
            log.error("[EXECUTION] placeLimitOrder failed for {}: {}", coin, e.getMessage());
            return buildRejected(order, e.getMessage());
        }
    }

    // ==================== Poll Pending Orders ====================

    /**
     * Called periodically (every ~10s in live mode).
     * Checks fill status of pending limit orders.
     * - Filled: places TP/SL triggers, returns filled TradeExecution
     * - Expired (> 1 candle): cancels on exchange, removes from pending
     * - Still resting: no action
     *
     * Returns list of newly filled orders for caller to open positions.
     */
    public List<TradeExecution> checkPendingOrders() {
        List<TradeExecution> filled = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();

        for (var entry : List.copyOf(pendingOrders.entrySet())) {
            String clientOid = entry.getKey();
            PendingOrder pending = entry.getValue();

            // Check fill BEFORE expiry — order may have filled in the last window
            try {
                double fillPrice = checkOrderFilled(pending.order.getPair(), pending.oid);

                // Not yet filled — check if expired
                if (fillPrice <= 0) {
                    if (now > pending.expiresAt) {
                        log.info("[EXECUTION] Limit order expired unfilled, cancelling: {} {}",
                                pending.order.getPair(), pending.oid);
                        cancelOrder(pending.order.getPair(), pending.oid);
                        pendingOrders.remove(clientOid);
                    }
                    continue;
                }

                pendingOrders.remove(clientOid);
                log.info("[EXECUTION] Limit order FILLED {} {} @ fill={} (signal={})",
                        pending.order.getDirection(), pending.order.getPair(),
                        fillPrice, pending.order.getEntryPrice());

                // Recalculate TP/SL relative to actual fill price (t-bot bug #2)
                double signalEntry = pending.order.getEntryPrice();
                double adjTp = pending.order.getTakeProfit();
                double adjSl = pending.order.getStopLoss();
                if (signalEntry > 0 && Math.abs(fillPrice - signalEntry) / signalEntry > 0.005) {
                    double ratio = fillPrice / signalEntry;
                    adjTp = pending.order.getTakeProfit() * ratio;
                    adjSl = pending.order.getStopLoss() * ratio;
                    log.info("[EXECUTION] Adjusted TP/SL for fill drift: TP={} SL={}", adjTp, adjSl);
                }

                // Place TP/SL trigger orders
                int szDecimals = marketDataService.getSzDecimals(pending.order.getPair());
                String[] triggerOids = placeTriggerOrders(
                        marketDataService.getAssetIndex(pending.order.getPair()),
                        "LONG".equals(pending.order.getDirection()),
                        pending.filledQty, szDecimals, adjTp, adjSl, pending.order.getPair());

                filled.add(TradeExecution.builder()
                        .clientOrderId(clientOid)
                        .exchangeOrderId(pending.oid)
                        .pair(pending.order.getPair())
                        .timeframe(pending.order.getTimeframe())
                        .direction(pending.order.getDirection())
                        .fillPrice(fillPrice)
                        .quantity(pending.filledQty)
                        .leverage(pending.leverage)
                        .stopLoss(adjSl)
                        .takeProfit(adjTp)
                        .tpTriggerId(triggerOids != null ? triggerOids[0] : null)
                        .slTriggerId(triggerOids != null ? triggerOids[1] : null)
                        .status("FILLED")
                        .dryRun(false)
                        .executionTimestamp(System.currentTimeMillis())
                        .exchange("hyperliquid")
                        .score(pending.order.getScore())
                        .build());

            } catch (Exception e) {
                log.warn("[EXECUTION] checkOrderFilled error for {}: {}", pending.order.getPair(), e.getMessage());
            }
        }
        return filled;
    }

    /**
     * Returns actual fill price if order is fully filled, 0 otherwise.
     * Checks via userFillsByTime (recent fills).
     * Matches by OID only — matching by coin would false-match fills from other
     * orders.
     */
    private double checkOrderFilled(String pair, String oid) {
        try {
            String wallet = walletAddress();
            if (wallet == null)
                return 0;
            long since = System.currentTimeMillis() - 300_000L; // last 5 min
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "userFillsByTime");
            req.put("user", wallet);
            req.put("startTime", since);
            List<Map<String, Object>> fills = postInfoListHeavy(req);
            if (fills == null)
                return 0;
            for (Map<String, Object> fill : fills) {
                String fillOid = String.valueOf(fill.getOrDefault("oid", ""));
                if (oid.equals(fillOid)) {
                    double px = toDouble(fill.get("px"));
                    if (px > 0)
                        return px;
                }
            }
        } catch (Exception e) {
            log.warn("[EXECUTION] checkOrderFilled failed: {}", e.getMessage());
        }
        return 0;
    }

    // ==================== Place Market Entry (Taker / IOC) ====================

    /**
     * Place an IOC limit order at market price (taker — immediate fill, slippage).
     * Returns FILLED on success, REJECTED otherwise.
     */
    public TradeExecution placeMarketEntry(TradeOrder order) {
        if (signer == null)
            return buildRejected(order, "No private key configured");
        boolean isBuy = "LONG".equals(order.getDirection());
        try {
            int assetIndex = marketDataService.getAssetIndex(order.getPair());
            int szDecimals = marketDataService.getSzDecimals(order.getPair());
            int maxLev = marketDataService.getMaxLeverage(order.getPair());
            int leverage = Math.min(order.getLeverage(), maxLev > 0 ? maxLev : 50);
            setLeverage(assetIndex, leverage);

            double midPrice = marketDataService.fetchCurrentPrice(order.getPair());
            double slippedPx = isBuy ? midPrice * 1.002 : midPrice * 0.998; // 0.2% slippage

            double qty = BigDecimal.valueOf(order.getQuantity())
                    .setScale(szDecimals, RoundingMode.DOWN).doubleValue();
            if (qty <= 0)
                return buildRejected(order, "Quantity rounds to 0");

            long nonce = Instant.now().toEpochMilli();
            Map<String, Object> orderSpec = new LinkedHashMap<>();
            orderSpec.put("a", assetIndex);
            orderSpec.put("b", isBuy);
            orderSpec.put("p", formatPrice(slippedPx, szDecimals));
            orderSpec.put("s", formatSize(qty, szDecimals));
            orderSpec.put("r", false);
            orderSpec.put("t", orderedMap("limit", orderedMap("tif", "Ioc")));

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "order");
            action.put("orders", List.of(orderSpec));
            action.put("grouping", "na");

            Map<String, Object> response = postExchange(action, nonce);
            if (response == null)
                return buildRejected(order, "API returned null");
            if ("err".equals(response.get("status")))
                return buildRejected(order, "API error: " + response.get("response"));

            String oid = extractOid(response);
            if (oid == null)
                return buildRejected(order, "Could not extract OID");

            // Place TP/SL immediately (market entry fills right away)
            double adjTp = order.getTakeProfit();
            double adjSl = order.getStopLoss();
            if (midPrice > 0 && Math.abs(midPrice - order.getEntryPrice()) / order.getEntryPrice() > 0.005) {
                double ratio = midPrice / order.getEntryPrice();
                adjTp = order.getTakeProfit() * ratio;
                adjSl = order.getStopLoss() * ratio;
            }
            String[] triggerOids = placeTriggerOrders(assetIndex, isBuy, qty, szDecimals, adjTp, adjSl,
                    order.getPair());

            log.info("[EXECUTION] Market entry FILLED {} {} @ ~{}", order.getDirection(), order.getPair(), midPrice);
            return TradeExecution.builder()
                    .clientOrderId(order.getClientOrderId())
                    .exchangeOrderId(oid)
                    .pair(order.getPair())
                    .direction(order.getDirection())
                    .timeframe(order.getTimeframe())
                    .fillPrice(midPrice)
                    .quantity(qty)
                    .leverage(leverage)
                    .stopLoss(adjSl)
                    .takeProfit(adjTp)
                    .tpTriggerId(triggerOids != null ? triggerOids[0] : null)
                    .slTriggerId(triggerOids != null ? triggerOids[1] : null)
                    .status("FILLED")
                    .dryRun(false)
                    .executionTimestamp(System.currentTimeMillis())
                    .exchange("hyperliquid")
                    .score(order.getScore())
                    .build();
        } catch (Exception e) {
            log.error("[EXECUTION] placeMarketEntry failed for {}: {}", order.getPair(), e.getMessage());
            return buildRejected(order, e.getMessage());
        }
    }

    // ==================== Close Position ====================

    /**
     * Close a position with a market order (IOC limit with slippage).
     */
    public boolean closePosition(OpenPosition pos) {
        if (signer == null)
            return false;
        String coin = toHyperliquidCoin(pos.getPair());
        boolean isBuy = "SHORT".equals(pos.getDirection()); // opposite to close
        try {
            // Cancel existing TP/SL triggers first
            if (pos.getTpTriggerId() != null)
                cancelOrder(pos.getPair(), pos.getTpTriggerId());
            if (pos.getSlTriggerId() != null)
                cancelOrder(pos.getPair(), pos.getSlTriggerId());

            int assetIndex = marketDataService.getAssetIndex(pos.getPair());
            int szDecimals = marketDataService.getSzDecimals(pos.getPair());
            double midPrice = marketDataService.fetchCurrentPrice(pos.getPair());
            double slippedPx = isBuy ? midPrice * 1.01 : midPrice * 0.99;

            long nonce = Instant.now().toEpochMilli();
            Map<String, Object> orderSpec = new LinkedHashMap<>();
            orderSpec.put("a", assetIndex);
            orderSpec.put("b", isBuy);
            orderSpec.put("p", formatPrice(slippedPx, szDecimals));
            orderSpec.put("s", formatSize(pos.getQuantity(), szDecimals));
            orderSpec.put("r", true); // reduce-only
            orderSpec.put("t", orderedMap("limit", orderedMap("tif", "Ioc")));

            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "order");
            action.put("orders", List.of(orderSpec));
            action.put("grouping", "na");

            Map<String, Object> resp = postExchange(action, nonce);
            if (resp == null || "err".equals(resp.get("status"))) {
                log.error("[EXECUTION] closePosition failed for {}: {}", coin,
                        resp != null ? resp.get("response") : "null");
                return false;
            }
            log.info("[EXECUTION] Closed {} {} @ ~{}", pos.getDirection(), coin, midPrice);
            return true;
        } catch (Exception e) {
            log.error("[EXECUTION] closePosition exception for {}: {}", coin, e.getMessage());
            return false;
        }
    }

    // ==================== Break-Even SL Update ====================

    /**
     * Cancel old SL trigger, place new one at break-even price.
     * Returns new SL trigger OID or null on failure.
     */
    public String updateStopLoss(OpenPosition pos, double newSlPrice) {
        if (signer == null)
            return null;
        try {
            // Cancel old SL trigger (t-bot bug #7: BE not updated on exchange)
            if (pos.getSlTriggerId() != null) {
                boolean cancelled = cancelOrder(pos.getPair(), pos.getSlTriggerId());
                if (!cancelled) {
                    log.warn("[EXECUTION] Could not cancel old SL trigger {} for {}", pos.getSlTriggerId(),
                            pos.getPair());
                }
            }

            int assetIndex = marketDataService.getAssetIndex(pos.getPair());
            int szDecimals = marketDataService.getSzDecimals(pos.getPair());
            boolean isLong = "LONG".equals(pos.getDirection());

            String[] oids = placeTriggerOrders(assetIndex, isLong, pos.getQuantity(),
                    szDecimals, 0, newSlPrice, pos.getPair());

            String newOid = oids != null ? oids[1] : null;
            log.info("[EXECUTION] SL updated for {} from {} to {} newOid={}", pos.getPair(),
                    pos.getSlTriggerId(), newSlPrice, newOid);
            return newOid;
        } catch (Exception e) {
            log.error("[EXECUTION] updateStopLoss failed for {}: {}", pos.getPair(), e.getMessage());
            return null;
        }
    }

    // ==================== Cancel Order ====================

    public boolean cancelOrder(String pair, String oid) {
        if (signer == null || oid == null)
            return false;
        try {
            int assetIndex = marketDataService.getAssetIndex(pair);
            long nonce = Instant.now().toEpochMilli();
            Map<String, Object> cancel = new LinkedHashMap<>();
            cancel.put("a", assetIndex);
            cancel.put("o", Long.parseLong(oid));
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "cancel");
            action.put("cancels", List.of(cancel));
            Map<String, Object> resp = postExchange(action, nonce);
            if (resp == null || "err".equals(resp.get("status"))) {
                log.warn("[EXECUTION] cancelOrder failed for {} oid={}: {}", pair, oid,
                        resp != null ? resp.get("response") : "null");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[EXECUTION] cancelOrder exception for {} oid={}: {}", pair, oid, e.getMessage());
            return false;
        }
    }

    // ==================== Exchange Position Sync ====================

    /**
     * Fetch open positions directly from exchange.
     * Used at startup for position recovery and for periodic reconciliation.
     * IMPORTANT: throws exception on API error — never return empty list on error
     * (t-bot bug #13: empty list was interpreted as "all positions closed" → false
     * SL_HIT)
     */
    public List<Map<String, Object>> getExchangePositions() throws Exception {
        String wallet = walletAddress();
        if (wallet == null)
            return List.of();
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("type", "clearinghouseState");
        req.put("user", wallet);
        Map<String, Object> res = postInfo(req);
        if (res == null)
            throw new RuntimeException("clearinghouseState returned null");
        List<Map<String, Object>> positions = (List<Map<String, Object>>) res.get("assetPositions");
        if (positions == null)
            return List.of();
        return positions.stream()
                .filter(p -> {
                    Map<String, Object> pos = (Map<String, Object>) p.get("position");
                    return pos != null && toDouble(pos.get("szi")) != 0;
                })
                .toList();
    }

    /**
     * Fetch open trigger orders (TP/SL) for a specific coin from exchange.
     * Returns [tpPrice, slPrice] — 0 if not found.
     * Uses frontendOpenOrders (heavy weight 20).
     */
    public double[] getTriggerPricesForCoin(String coin) {
        double[] result = { 0, 0 }; // [0]=TP, [1]=SL
        try {
            String wallet = walletAddress();
            if (wallet == null)
                return result;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "frontendOpenOrders");
            req.put("user", wallet);
            List<Map<String, Object>> orders = postInfoListHeavy(req);
            if (orders == null)
                return result;
            for (Map<String, Object> order : orders) {
                String orderCoin = String.valueOf(order.getOrDefault("coin", ""));
                if (!coin.equals(orderCoin))
                    continue;
                String orderType = String.valueOf(order.getOrDefault("orderType", ""));
                if (!"Trigger".equals(orderType))
                    continue;
                double triggerPx = toDouble(order.get("triggerPx"));
                // Detect TP vs SL by triggerCondition or tpsl field
                String tpsl = String.valueOf(order.getOrDefault("tpsl", ""));
                if ("tp".equals(tpsl) && triggerPx > 0) {
                    result[0] = triggerPx;
                } else if ("sl".equals(tpsl) && triggerPx > 0) {
                    result[1] = triggerPx;
                }
            }
        } catch (Exception e) {
            log.warn("[EXECUTION] getTriggerPricesForCoin({}) failed: {}", coin, e.getMessage());
        }
        return result;
    }

    /**
     * Returns trigger OIDs grouped by coin: coin → [tpOid, slOid].
     * Used by recoverPositions to restore trigger tracking after restart.
     */
    public Map<String, String[]> getTriggerOidsByCoin() {
        Map<String, String[]> result = new LinkedHashMap<>();
        try {
            String wallet = walletAddress();
            if (wallet == null)
                return result;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "frontendOpenOrders");
            req.put("user", wallet);
            List<Map<String, Object>> orders = postInfoListHeavy(req);
            if (orders == null)
                return result;
            for (Map<String, Object> order : orders) {
                String orderType = String.valueOf(order.getOrDefault("orderType", ""));
                if (!"Trigger".equals(orderType))
                    continue;
                String coin = String.valueOf(order.getOrDefault("coin", ""));
                String oid = String.valueOf(order.getOrDefault("oid", ""));
                String tpsl = String.valueOf(order.getOrDefault("tpsl", ""));
                if (coin.isEmpty() || oid.isBlank())
                    continue;
                String[] oids = result.computeIfAbsent(coin, k -> new String[2]);
                if ("tp".equals(tpsl))
                    oids[0] = oid;
                else if ("sl".equals(tpsl))
                    oids[1] = oid;
            }
        } catch (Exception e) {
            log.warn("[EXECUTION] getTriggerOidsByCoin failed: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Returns OIDs of all open trigger orders (TP/SL) on the exchange.
     * Used by syncWithExchange to determine which trigger was executed.
     */
    public Set<String> getOpenTriggerOrderIds() {
        Set<String> oids = new HashSet<>();
        try {
            String wallet = walletAddress();
            if (wallet == null)
                return oids;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "frontendOpenOrders");
            req.put("user", wallet);
            List<Map<String, Object>> orders = postInfoListHeavy(req);
            if (orders == null)
                return oids;
            for (Map<String, Object> order : orders) {
                String orderType = String.valueOf(order.getOrDefault("orderType", ""));
                if ("Trigger".equals(orderType)) {
                    String oid = String.valueOf(order.getOrDefault("oid", ""));
                    if (!oid.isBlank())
                        oids.add(oid);
                }
            }
        } catch (Exception e) {
            log.warn("[EXECUTION] getOpenTriggerOrderIds failed: {}", e.getMessage());
        }
        return oids;
    }

    /**
     * Fetch recent closing fill prices from exchange (userFillsByTime).
     * Returns map of coin → most recent close fill price.
     */
    public Map<String, Double> getRecentCloseFillPrices(long sinceMs) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            String wallet = walletAddress();
            if (wallet == null)
                return result;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "userFillsByTime");
            req.put("user", wallet);
            req.put("startTime", sinceMs);
            List<Map<String, Object>> fills = postInfoListHeavy(req);
            if (fills == null)
                return result;
            for (Map<String, Object> fill : fills) {
                String dir = String.valueOf(fill.getOrDefault("dir", ""));
                if (dir.startsWith("Close")) {
                    String coin = String.valueOf(fill.get("coin"));
                    double px = toDouble(fill.get("px"));
                    if (px > 0)
                        result.put(coin, px);
                }
            }
            log.debug("[EXECUTION] Fetched {} recent close fill prices since {}", result.size(), sinceMs);
        } catch (Exception e) {
            log.warn("[EXECUTION] getRecentCloseFillPrices failed: {}", e.getMessage());
        }
        return result;
    }

    // ==================== Closed Trades from Exchange ====================

    /**
     * Fetch all closed trades from HL for the last 30 days via userFillsByTime.
     * Returns trade records with real closedPnl, fees, and fill prices.
     * Close reasons default to UNKNOWN and are enriched from local tracking data.
     */
    public List<Map<String, Object>> getClosedTrades() {
        try {
            String wallet = walletAddress();
            if (wallet == null)
                return List.of();

            long startTime = Instant.now().minusSeconds(30L * 24 * 3600).toEpochMilli();

            // Apply liveStartDate filter
            String startDate = config.getLiveStartDate();
            if (startDate != null && !startDate.isBlank()) {
                try {
                    long cutoff = java.time.LocalDate.parse(startDate)
                            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    if (cutoff > startTime)
                        startTime = cutoff;
                } catch (Exception ignored) {
                }
            }

            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "userFillsByTime");
            req.put("user", wallet);
            req.put("startTime", startTime);
            List<Map<String, Object>> fills = postInfoListHeavy(req);
            if (fills == null || fills.isEmpty())
                return List.of();

            // Separate closing and opening fills
            List<Map<String, Object>> closingFills = new ArrayList<>();
            Map<String, List<Map<String, Object>>> openFillsByCoin = new LinkedHashMap<>();
            for (Map<String, Object> fill : fills) {
                String dir = String.valueOf(fill.getOrDefault("dir", ""));
                if (dir.startsWith("Close")) {
                    closingFills.add(fill);
                } else if (dir.startsWith("Open")) {
                    String coin = String.valueOf(fill.get("coin"));
                    openFillsByCoin.computeIfAbsent(coin, k -> new ArrayList<>()).add(fill);
                }
            }
            if (closingFills.isEmpty())
                return List.of();

            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault());

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> fill : closingFills) {
                try {
                    result.add(buildTradeFromFill(fill, openFillsByCoin, fmt));
                } catch (Exception e) {
                    log.warn("[EXECUTION] Failed to parse fill: {}", e.getMessage());
                }
            }

            // Sort newest first
            result.sort((a, b) -> {
                long ta = a.get("closeTimestamp") instanceof Number n ? n.longValue() : 0;
                long tb = b.get("closeTimestamp") instanceof Number n ? n.longValue() : 0;
                return Long.compare(tb, ta);
            });

            log.debug("[EXECUTION] Fetched {} closed trades from exchange", result.size());
            return result.size() > 500 ? result.subList(0, 500) : result;
        } catch (Exception e) {
            log.warn("[EXECUTION] Failed to fetch closed trades: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Build a trade record from a closing fill.
     * Entry price is reverse-computed from closedPnl.
     */
    private Map<String, Object> buildTradeFromFill(Map<String, Object> fill,
            Map<String, List<Map<String, Object>>> openFillsByCoin,
            java.time.format.DateTimeFormatter fmt) {

        String coin = String.valueOf(fill.get("coin"));
        double exitPrice = toDouble(fill.get("px"));
        double closedPnl = toDouble(fill.get("closedPnl"));
        double size = toDouble(fill.get("sz"));
        double fee = toDouble(fill.get("fee"));
        long closeTime = ((Number) fill.get("time")).longValue();
        String dir = String.valueOf(fill.get("dir"));
        boolean isLiquidation = Boolean.TRUE.equals(fill.get("liquidation"));
        String oid = String.valueOf(fill.getOrDefault("oid", ""));

        boolean wasLong = dir.contains("Long");
        String direction = wasLong ? "LONG" : "SHORT";

        // Compute entry price from closedPnl
        double entryPrice = 0;
        if (size > 0) {
            entryPrice = wasLong
                    ? exitPrice - closedPnl / size
                    : exitPrice + closedPnl / size;
        }

        String closeReason = isLiquidation ? "LIQUIDATION" : "UNKNOWN";

        // P&L percent (unleveraged price move)
        double pnlPercent = entryPrice > 0
                ? (wasLong ? (exitPrice - entryPrice) / entryPrice : (entryPrice - exitPrice) / entryPrice) * 100
                : 0;

        // Net P&L (closedPnl already includes the raw P&L, subtract fee for net)
        double pnlUsd = closedPnl - fee;

        // Find matching opening fill for openDate
        String openDate = "";
        List<Map<String, Object>> opens = openFillsByCoin.get(coin);
        if (opens != null) {
            for (int i = opens.size() - 1; i >= 0; i--) {
                long openTime = ((Number) opens.get(i).get("time")).longValue();
                if (openTime < closeTime) {
                    openDate = fmt.format(Instant.ofEpochMilli(openTime));
                    break;
                }
            }
        }

        Map<String, Object> trade = new LinkedHashMap<>();
        trade.put("openDate", openDate);
        trade.put("closeDate", fmt.format(Instant.ofEpochMilli(closeTime)));
        trade.put("closeTimestamp", closeTime);
        trade.put("pair", coin);
        trade.put("timeframe", "");
        trade.put("direction", direction);
        trade.put("entryPrice", Math.round(entryPrice * 100000.0) / 100000.0);
        trade.put("exitPrice", exitPrice);
        trade.put("stopLoss", 0.0);
        trade.put("takeProfit", 0.0);
        trade.put("leverage", 0);
        trade.put("quantity", size);
        trade.put("positionSizeUsd", Math.round(entryPrice * size * 100.0) / 100.0);
        trade.put("score", 0.0);
        trade.put("closeReason", closeReason);
        trade.put("pnlPercent", Math.round(pnlPercent * 100.0) / 100.0);
        trade.put("pnlUsd", Math.round(pnlUsd * 100.0) / 100.0);
        trade.put("feeUsd", Math.round(fee * 100.0) / 100.0);
        trade.put("candlesElapsed", 0);
        trade.put("breakEvenApplied", false);
        trade.put("dryRun", false);
        trade.put("exchange", "hyperliquid");
        trade.put("clientOrderId", oid);
        return trade;
    }

    public boolean hasPendingOrders() {
        return !pendingOrders.isEmpty();
    }

    public int pendingOrderCount() {
        return pendingOrders.size();
    }

    /**
     * Returns details of all pending limit orders for UI display.
     */
    public List<Map<String, Object>> getPendingOrderDetails() {
        List<Map<String, Object>> result = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (var entry : pendingOrders.entrySet()) {
            PendingOrder p = entry.getValue();
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("clientOrderId", entry.getKey());
            detail.put("exchangeOrderId", p.oid());
            detail.put("pair", p.order().getPair());
            detail.put("direction", p.order().getDirection());
            detail.put("timeframe", p.order().getTimeframe());
            detail.put("limitPrice", p.order().getEntryPrice());
            detail.put("stopLoss", p.order().getStopLoss());
            detail.put("takeProfit", p.order().getTakeProfit());
            detail.put("leverage", p.leverage());
            detail.put("quantity", p.filledQty());
            detail.put("positionSizeUsd", p.order().getPositionSizeUsd());
            detail.put("score", p.order().getScore());
            detail.put("placedAt", p.order().getSignalTimestamp());
            detail.put("expiresAt", p.expiresAt());
            detail.put("remainingMs", Math.max(0, p.expiresAt() - now));
            result.add(detail);
        }
        return result;
    }

    // ==================== Trigger Orders ====================

    private String[] placeTriggerOrders(int assetIndex, boolean mainIsBuy,
            double size, int szDecimals, double tp, double sl, String pair) {
        String[] oids = new String[2]; // [0]=TP, [1]=SL
        String sizeWire = formatSize(size, szDecimals);

        if (tp > 0) {
            try {
                // TP as GTC limit order (maker rebate instead of taker fee)
                long nonce = Instant.now().toEpochMilli();
                Map<String, Object> tpOrder = new LinkedHashMap<>();
                tpOrder.put("a", assetIndex);
                tpOrder.put("b", !mainIsBuy);
                tpOrder.put("p", formatPrice(tp, szDecimals));
                tpOrder.put("s", sizeWire);
                tpOrder.put("r", true); // reduce-only
                tpOrder.put("t", orderedMap("limit", orderedMap("tif", "Gtc")));
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "order");
                action.put("orders", List.of(tpOrder));
                action.put("grouping", "na");
                Map<String, Object> resp = postExchange(action, nonce);
                oids[0] = extractTriggerOid(resp, "TP", tp, pair);
            } catch (Exception e) {
                log.error("[EXECUTION] CRITICAL: Failed TP order for {}: {}", pair, e.getMessage());
            }
        }

        if (sl > 0) {
            try {
                // SL as trigger limit with 0.1% buffer below trigger (maker-friendly, reduce
                // slippage)
                // For LONG: sell limit at sl * 0.999 triggers when price falls to sl
                // For SHORT: buy limit at sl * 1.001 triggers when price rises to sl
                double slLimitPx = mainIsBuy ? sl * 0.999 : sl * 1.001;
                long nonce = Instant.now().toEpochMilli();
                Map<String, Object> slOrder = new LinkedHashMap<>();
                slOrder.put("a", assetIndex);
                slOrder.put("b", !mainIsBuy);
                slOrder.put("p", formatPrice(slLimitPx, szDecimals));
                slOrder.put("s", sizeWire);
                slOrder.put("r", true);
                Map<String, Object> slTrigger = new LinkedHashMap<>();
                slTrigger.put("isMarket", false);
                slTrigger.put("triggerPx", formatPrice(sl, szDecimals));
                slTrigger.put("tpsl", "sl");
                slOrder.put("t", orderedMap("trigger", slTrigger));
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("type", "order");
                action.put("orders", List.of(slOrder));
                action.put("grouping", "na");
                Map<String, Object> resp = postExchange(action, nonce);
                oids[1] = extractTriggerOid(resp, "SL", sl, pair);
            } catch (Exception e) {
                log.error("[EXECUTION] CRITICAL: Failed SL trigger for {}: {}", pair, e.getMessage());
            }
        }
        return oids;
    }

    private String extractTriggerOid(Map<String, Object> resp, String type, double price, String pair) {
        if (resp == null) {
            log.error("[EXECUTION] {} trigger for {} — API returned null", type, pair);
            return null;
        }
        if ("err".equals(resp.get("status"))) {
            log.error("[EXECUTION] {} trigger REJECTED for {} at {}: {}", type, pair, price, resp.get("response"));
            return null;
        }
        try {
            Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) resp.get("response")).get("data");
            List<Map<String, Object>> statuses = (List<Map<String, Object>>) data.get("statuses");
            if (statuses != null && !statuses.isEmpty()) {
                Map<String, Object> s = statuses.get(0);
                if (s.containsKey("error")) {
                    log.error("[EXECUTION] {} trigger inner error for {}: {}", type, pair, s.get("error"));
                    return null;
                }
                Map<String, Object> resting = (Map<String, Object>) s.get("resting");
                if (resting != null)
                    return String.valueOf(resting.get("oid"));
            }
        } catch (Exception e) {
            log.warn("[EXECUTION] Could not parse {} trigger OID for {}: {}", type, pair, e.getMessage());
        }
        return null;
    }

    // ==================== Leverage ====================

    private void setLeverage(int assetIndex, int leverage) {
        try {
            long nonce = Instant.now().toEpochMilli();
            Map<String, Object> action = new LinkedHashMap<>();
            action.put("type", "updateLeverage");
            action.put("asset", assetIndex);
            action.put("isCross", true);
            action.put("leverage", leverage);
            postExchange(action, nonce);
        } catch (Exception e) {
            log.warn("[EXECUTION] setLeverage failed for asset {}: {}", assetIndex, e.getMessage());
        }
    }

    // ==================== HTTP ====================

    private Map<String, Object> postExchange(Map<String, Object> action, long nonce) {
        if (signer == null)
            throw new RuntimeException("No signer — private key not configured");
        rateLimiter.acquireExchange();
        String url = config.getHyperliquidApiUrl() + "/exchange";
        Map<String, Object> signed = signer.buildSignedRequest(action, nonce);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForObject(url, new HttpEntity<>(signed, headers), Map.class);
    }

    private Map<String, Object> postInfo(Map<String, Object> body) {
        rateLimiter.acquireInfo();
        String url = config.getHyperliquidApiUrl() + "/info";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForObject(url, new HttpEntity<>(body, headers), Map.class);
    }

    private List<Map<String, Object>> postInfoList(Map<String, Object> body) {
        rateLimiter.acquireInfo();
        String url = config.getHyperliquidApiUrl() + "/info";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForObject(url, new HttpEntity<>(body, headers), List.class);
    }

    private List<Map<String, Object>> postInfoListHeavy(Map<String, Object> body) {
        rateLimiter.acquireInfoHeavy();
        String url = config.getHyperliquidApiUrl() + "/info";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForObject(url, new HttpEntity<>(body, headers), List.class);
    }

    // ==================== Formatting ====================

    private String formatPrice(double price, int szDecimals) {
        String sigFig = String.format("%.5g", price);
        double rounded = Double.parseDouble(sigFig);
        int maxDecimals = Math.max(0, 6 - szDecimals);
        BigDecimal bd = BigDecimal.valueOf(rounded).setScale(maxDecimals, RoundingMode.HALF_UP);
        return bd.stripTrailingZeros().toPlainString();
    }

    private String formatSize(double size, int szDecimals) {
        BigDecimal bd = BigDecimal.valueOf(size).setScale(szDecimals, RoundingMode.DOWN);
        return bd.stripTrailingZeros().toPlainString();
    }

    private String extractOid(Map<String, Object> response) {
        OrderResult r = parseOrderResult(response);
        return r != null ? r.oid : null;
    }

    /**
     * Parse Hyperliquid order response — distinguishes resting (in book) from
     * immediate fill.
     */
    private OrderResult parseOrderResult(Map<String, Object> response) {
        try {
            Map<String, Object> data = (Map<String, Object>) ((Map<String, Object>) response.get("response"))
                    .get("data");
            List<Map<String, Object>> statuses = (List<Map<String, Object>>) data.get("statuses");
            if (statuses == null || statuses.isEmpty())
                return null;
            Map<String, Object> s = statuses.get(0);
            if (s.containsKey("error")) {
                return new OrderResult(null, false, String.valueOf(s.get("error")));
            }
            // GTC order rests in book
            Map<String, Object> resting = (Map<String, Object>) s.get("resting");
            if (resting != null)
                return new OrderResult(String.valueOf(resting.get("oid")), false, null);
            // Immediately filled (price was better than limit)
            Map<String, Object> filled = (Map<String, Object>) s.get("filled");
            if (filled != null)
                return new OrderResult(String.valueOf(filled.get("oid")), true, null);
        } catch (Exception e) {
            log.warn("[EXECUTION] parseOrderResult failed: {}", e.getMessage());
        }
        return null;
    }

    private static Map<String, Object> orderedMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /**
     * Spot USDC available balance (total - hold) for unified/Portfolio Margin
     * accounts.
     */
    @SuppressWarnings("unchecked")
    private double[] getSpotUsdcInfo() {
        String wallet = walletAddress();
        if (wallet == null)
            return new double[] { 0, 0 };
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("type", "spotClearinghouseState");
            req.put("user", wallet);
            Map<String, Object> res = postInfo(req);
            if (res == null)
                return new double[] { 0, 0 };
            List<Map<String, Object>> balances = (List<Map<String, Object>>) res.get("balances");
            if (balances == null)
                return new double[] { 0, 0 };
            for (Map<String, Object> bal : balances) {
                if ("USDC".equals(bal.get("coin"))) {
                    double total = toDouble(bal.get("total"));
                    double hold = toDouble(bal.get("hold"));
                    return new double[] { total, hold };
                }
            }
        } catch (Exception e) {
            log.warn("[EXECUTION] Failed to fetch spot info: {}", e.getMessage());
        }
        return new double[] { 0, 0 };
    }

    private String walletAddress() {
        String configured = config.getHyperliquidWalletAddress();
        if (configured != null && !configured.isBlank())
            return configured;
        return signer != null ? signer.getAddress() : null;
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

    private static String toHyperliquidCoin(String pair) {
        return pair; // HyperliquidMarketDataService.toHyperliquidCoin already handles this
    }

    // ==================== Helper ====================

    private TradeExecution buildRejected(TradeOrder order, String reason) {
        return TradeExecution.builder()
                .clientOrderId(order.getClientOrderId())
                .pair(order.getPair())
                .direction(order.getDirection())
                .timeframe(order.getTimeframe())
                .fillPrice(order.getEntryPrice())
                .quantity(order.getQuantity())
                .leverage(order.getLeverage())
                .stopLoss(order.getStopLoss())
                .takeProfit(order.getTakeProfit())
                .status("REJECTED")
                .errorMessage(reason)
                .dryRun(false)
                .executionTimestamp(System.currentTimeMillis())
                .exchange("hyperliquid")
                .score(order.getScore())
                .build();
    }

    // ==================== Inner Classes ====================

    private record PendingOrder(TradeOrder order, String oid, double filledQty, int leverage, long expiresAt) {
    }

    private record OrderResult(String oid, boolean immediatelyFilled, String error) {
    }
}
