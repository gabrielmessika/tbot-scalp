package com.tbot.scalp.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.OpenPosition;
import com.tbot.scalp.model.Signal;
import com.tbot.scalp.model.TradeExecution;
import com.tbot.scalp.model.TradeOrder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderManagerService {

    private final ScalpConfig config;
    private final RiskManagementService riskService;
    private final PositionManagerService positionManager;
    private final HyperliquidExecutionService executionService;
    private final TradeJournalService journalService;

    /**
     * Process signals: validate risk, then execute (dry-run, limit maker, or market
     * taker).
     * In live mode with maker orders, returns PENDING_FILL executions — fills are
     * detected later by checkPendingOrders().
     */
    public List<TradeExecution> processSignals(List<Signal> signals) {
        List<TradeExecution> results = new ArrayList<>();

        double balance = getEffectiveBalance();
        double equity = getEffectiveEquity();
        riskService.initEquity(equity);

        double totalMarginUsed = positionManager.getOpenPositions().stream()
                .mapToDouble(p -> p.getQuantity() * p.getEntryPrice() / p.getLeverage())
                .sum();

        // Portfolio risk check
        RiskManagementService.RiskStatus riskStatus = riskService.checkPortfolioRisk(balance, equity);
        if (riskStatus.isDailyLimitReached()) {
            log.warn("[RISK] Daily loss limit reached, halting");
            return results;
        }
        if (riskStatus.isDrawdownCircuitBreaker()) {
            log.warn("[RISK] Drawdown circuit breaker triggered, halting");
            return results;
        }

        for (Signal signal : signals) {
            String pairTf = signal.getPair() + ":" + signal.getTimeframe();

            // Skip on cooldown
            if (positionManager.isOnCooldown(pairTf)) {
                log.debug("[SKIP] {} on cooldown", pairTf);
                continue;
            }

            // Validate — use equity for drawdown check (t-bot bug #12)
            List<String> rejections = riskService.validateSignal(signal, balance, equity,
                    positionManager.getOpenPairKeys(), totalMarginUsed);

            if (!rejections.isEmpty()) {
                // Contrary signal: score passed but position already open on this coin in
                // opposite direction
                if (config.isContrarySignalEnabled()
                        && rejections.stream().noneMatch(r -> r.startsWith("Score"))
                        && rejections.stream().anyMatch(r -> r.startsWith("Position already open"))) {
                    positionManager.recordContrarySignal(signal.getPair(), signal.getDirection(),
                            config.getContrarySignalThreshold());
                }

                log.info("[REJECTED] {} {}: {}", signal.getPair(), signal.getTimeframe(), rejections);
                TradeExecution rejected = TradeExecution.builder()
                        .pair(signal.getPair()).direction(signal.getDirection())
                        .timeframe(signal.getTimeframe())
                        .status("REJECTED").errorMessage(String.join("; ", rejections))
                        .dryRun(true).executionTimestamp(System.currentTimeMillis())
                        .exchange(config.getExchange())
                        .build();
                journalService.record(rejected, "RISK_REJECTED", signal.getScore(), signal.getTimeframe());
                continue;
            }

            // Position sizing
            double posSize = balance * config.getPositionSizePercent() / 100.0;
            if (posSize < config.getMinPositionSize()) {
                log.debug("[SKIP] {} position size ${} < min ${}", pairTf, posSize, config.getMinPositionSize());
                continue;
            }
            double quantity = posSize * signal.getLeverage() / signal.getEntryPrice();
            String clientOrderId = "scalp_" + signal.getPair() + "_" + signal.getTimestamp();

            TradeExecution exec;

            if (config.isLiveTrading()) {
                // === LIVE EXECUTION ===
                TradeOrder order = TradeOrder.builder()
                        .clientOrderId(clientOrderId)
                        .pair(signal.getPair())
                        .timeframe(signal.getTimeframe())
                        .direction(signal.getDirection())
                        .entryPrice(signal.getEntryPrice())
                        .stopLoss(signal.getStopLoss())
                        .takeProfit(signal.getTakeProfit())
                        .leverage(signal.getLeverage())
                        .quantity(quantity)
                        .positionSizeUsd(posSize)
                        .score(signal.getScore())
                        .signalTimestamp(signal.getTimestamp())
                        .build();

                if (config.isUseMakerOrders()) {
                    // Limit GTC order — maker rebate, fill detected later
                    exec = executionService.placeLimitOrder(order);
                } else {
                    // IOC market order — immediate fill
                    exec = executionService.placeMarketEntry(order);
                }
            } else {
                // === DRY-RUN ===
                exec = TradeExecution.builder()
                        .clientOrderId(clientOrderId)
                        .pair(signal.getPair()).direction(signal.getDirection())
                        .timeframe(signal.getTimeframe())
                        .fillPrice(signal.getEntryPrice())
                        .quantity(quantity).leverage(signal.getLeverage())
                        .stopLoss(signal.getStopLoss()).takeProfit(signal.getTakeProfit())
                        .status("DRY_RUN")
                        .dryRun(true)
                        .executionTimestamp(System.currentTimeMillis())
                        .exchange(config.getExchange())
                        .build();
            }

            journalService.record(exec, exec.getStatus(), signal.getScore(), signal.getTimeframe());

            // Only open position tracking for immediate fills (DRY_RUN or FILLED)
            // PENDING_FILL positions are opened later when fill is detected in
            // checkPendingOrders()
            if (exec.isSuccess()) {
                OpenPosition pos = OpenPosition.builder()
                        .pair(signal.getPair()).timeframe(signal.getTimeframe())
                        .direction(signal.getDirection())
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
                        .dryRun(exec.isDryRun())
                        .openTimestamp(System.currentTimeMillis())
                        .score(signal.getScore())
                        .build();
                positionManager.addPosition(pos);
            }

            results.add(exec);
        }

        return results;
    }

    /**
     * Get effective balance: real from exchange in live mode, simulated otherwise.
     */
    public double getEffectiveBalance() {
        if (config.isLiveTrading()) {
            double b = executionService.getAvailableBalance();
            return b > 0 ? b : config.getDryRunBalance();
        }
        return config.getDryRunBalance() + positionManager.getRealizedPnl();
    }

    /**
     * Get effective equity: real from exchange in live mode, simulated otherwise.
     * In dry-run, includes unrealized P&L from open positions (t-bot parity).
     */
    public double getEffectiveEquity() {
        if (config.isLiveTrading()) {
            double e = executionService.getEquity();
            return e > 0 ? e : getEffectiveBalance();
        }
        double unrealized = positionManager.getOpenPositions().stream()
                .mapToDouble(p -> p.getPositionSizeUsd() * p.getCurrentPnlPercent() / 100.0)
                .sum();
        return getEffectiveBalance() + unrealized;
    }

    /**
     * Get unrealized P&L across all open positions.
     */
    public double getUnrealizedPnl() {
        return positionManager.getOpenPositions().stream()
                .mapToDouble(p -> p.getPositionSizeUsd() * p.getCurrentPnlPercent() / 100.0)
                .sum();
    }

    /**
     * Get total margin used across all open positions.
     */
    public double getTotalMarginUsed() {
        return positionManager.getOpenPositions().stream()
                .mapToDouble(p -> p.getQuantity() * p.getEntryPrice() / p.getLeverage())
                .sum();
    }

    /**
     * Get enriched risk status with all fields for UI display.
     */
    public RiskManagementService.RiskStatus getRiskStatus() {
        double balance = getEffectiveBalance();
        double equity = getEffectiveEquity();
        RiskManagementService.RiskStatus status = riskService.checkPortfolioRisk(balance, equity);

        // Enrich with margin/position/breakdown data
        double usedMargin = getTotalMarginUsed();
        status.setUsedMargin(usedMargin);
        status.setUsedMarginPercent(equity > 0 ? (usedMargin / equity) * 100 : 0);
        status.setOpenPositions(positionManager.getOpenPositions().size());
        status.setMaxPositions(config.getMaxOpenPositions());
        status.setBaseBalance(config.getDryRunBalance());
        status.setRealizedPnl(positionManager.getRealizedPnl());
        status.setUnrealizedPnl(getUnrealizedPnl());
        status.setOpenPairs(positionManager.getOpenPairKeys());

        return status;
    }
}
