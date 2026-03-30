package com.tbot.scalp.service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderManagerService {

    private final ScalpConfig config;
    private final RiskManagementService riskService;
    private final PositionManagerService positionManager;
    private final TradeJournalService journalService;

    public List<TradeExecution> processSignals(List<Signal> signals) {
        List<TradeExecution> results = new ArrayList<>();

        double balance = config.getDryRunBalance(); // TODO: from exchange when live
        double equity = balance;
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

            // Validate
            List<String> rejections = riskService.validateSignal(signal, balance, equity,
                    positionManager.getOpenPairKeys(), totalMarginUsed);

            if (!rejections.isEmpty()) {
                // Contrary signal: score passed but position already open on this coin in opposite direction
                if (config.isContrarySignalEnabled()
                        && rejections.stream().noneMatch(r -> r.startsWith("Score"))
                        && rejections.stream().anyMatch(r -> r.startsWith("Position already open"))) {
                    positionManager.recordContrarySignal(signal.getPair(), signal.getDirection(),
                            config.getContrarySignalThreshold());
                }

                log.info("[REJECTED] {} {}: {}", signal.getPair(), signal.getTimeframe(), rejections);
                TradeExecution rejected = TradeExecution.builder()
                        .pair(signal.getPair()).direction(signal.getDirection())
                        .status("REJECTED").errorMessage(String.join("; ", rejections))
                        .dryRun(true).executionTimestamp(System.currentTimeMillis())
                        .exchange(config.getExchange())
                        .build();
                journalService.record(rejected, "RISK_REJECTED", signal.getScore(), signal.getTimeframe());
                continue;
            }

            // Execute (dry-run by default)
            double posSize = balance * config.getPositionSizePercent() / 100.0;
            double quantity = posSize * signal.getLeverage() / signal.getEntryPrice();

            TradeExecution exec = TradeExecution.builder()
                    .clientOrderId("scalp_" + signal.getPair() + "_" + signal.getTimestamp())
                    .pair(signal.getPair()).direction(signal.getDirection())
                    .fillPrice(signal.getEntryPrice())
                    .quantity(quantity).leverage(signal.getLeverage())
                    .stopLoss(signal.getStopLoss()).takeProfit(signal.getTakeProfit())
                    .status(config.isLiveTrading() ? "FILLED" : "DRY_RUN")
                    .dryRun(!config.isLiveTrading())
                    .executionTimestamp(System.currentTimeMillis())
                    .exchange(config.getExchange())
                    .build();

            journalService.record(exec, exec.getStatus(), signal.getScore(), signal.getTimeframe());

            if (exec.isSuccess()) {
                OpenPosition pos = OpenPosition.builder()
                        .pair(signal.getPair()).timeframe(signal.getTimeframe())
                        .direction(signal.getDirection())
                        .entryPrice(exec.getFillPrice())
                        .stopLoss(signal.getStopLoss()).takeProfit(signal.getTakeProfit())
                        .originalStopLoss(signal.getStopLoss())
                        .leverage(signal.getLeverage()).quantity(quantity)
                        .currentPrice(exec.getFillPrice())
                        .clientOrderId(exec.getClientOrderId())
                        .exchangeOrderId(exec.getExchangeOrderId())
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
}
