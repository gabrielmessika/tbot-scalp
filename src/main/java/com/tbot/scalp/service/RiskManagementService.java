package com.tbot.scalp.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.Signal;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiskManagementService {

    private final ScalpConfig config;

    private volatile double peakEquity = 0;
    private volatile double dailyStartBalance = 0;
    private volatile long dailyResetTimestamp = 0;

    public List<String> validateSignal(Signal signal, double balance, double equity,
            List<String> openPairs, double totalMarginUsed) {
        List<String> rejections = new ArrayList<>();

        // 1. Score threshold
        double threshold = config.getEffectiveThreshold(signal.getTimeframe());
        if (signal.getScore() < threshold) {
            rejections.add(String.format("Score %.1f < threshold %.1f", signal.getScore(), threshold));
        }

        // 2. SL mandatory
        if (signal.getStopLoss() <= 0) {
            rejections.add("Stop-loss is required");
        }

        // 3. SL distance (min + max)
        double slDist = Math.abs(signal.getEntryPrice() - signal.getStopLoss()) / signal.getEntryPrice() * 100;
        double effectiveMaxSl = config.getEffectiveMaxSl(signal.getTimeframe());
        double effectiveMinSl = config.getEffectiveMinSl(signal.getTimeframe());
        if (slDist > effectiveMaxSl) {
            rejections.add(String.format("SL distance %.2f%% > max %.2f%%", slDist, effectiveMaxSl));
        }
        if (slDist < effectiveMinSl) {
            rejections.add(
                    String.format("SL distance %.2f%% < min %.2f%% (too tight, noise risk)", slDist, effectiveMinSl));
        }

        // 4. Available balance
        double posSize = balance * config.getPositionSizePercent() / 100.0;
        double marginNeeded = posSize / signal.getLeverage();
        if (balance < marginNeeded) {
            rejections.add(String.format("Insufficient balance: need $%.2f, have $%.2f", marginNeeded, balance));
        }

        // 5. Max open positions
        int maxPos = getDrawdownThrottledMaxPositions(config.getMaxOpenPositions(), equity);
        if (openPairs.size() >= maxPos) {
            rejections.add(String.format("Max open positions reached (%d/%d)", openPairs.size(), maxPos));
        }

        // 6. No duplicate pair+TF
        String key = signal.getPair() + ":" + signal.getTimeframe();
        if (openPairs.contains(key)) {
            rejections.add("Position already open for " + key);
        }

        // 7. Max margin usage
        double maxMargin = config.getMaxMarginUsagePercent() / 100.0 * equity;
        if (totalMarginUsed + marginNeeded > maxMargin) {
            rejections.add(String.format("Margin usage would exceed max %.0f%%", config.getMaxMarginUsagePercent()));
        }

        // 8. Max loss per trade
        double maxLossPercent = slDist * signal.getLeverage() * config.getPositionSizePercent() / 100.0;
        if (maxLossPercent > config.getMaxLossPerTradePercent()) {
            rejections.add(String.format("Trade risk %.2f%% > max %.2f%%", maxLossPercent,
                    config.getMaxLossPerTradePercent()));
        }

        return rejections;
    }

    public RiskStatus checkPortfolioRisk(double balance, double equity) {
        long now = System.currentTimeMillis();

        // Daily reset
        if (now - dailyResetTimestamp > 86_400_000L) {
            dailyStartBalance = equity;
            peakEquity = equity;
            dailyResetTimestamp = now;
        }

        if (equity > peakEquity) {
            // Guard against API artifacts (e.g. spot/perps race returning inflated equity).
            // A single-cycle jump > 5% of peak is almost certainly an API glitch, not a
            // real gain — accepting it would set peakEquity too high and trigger a false
            // drawdown throttle for the rest of the session.
            if (equity <= peakEquity * 1.05) {
                peakEquity = equity;
            } else {
                log.warn("[RISK] Equity spike ignored: {} → {} (+{}%) exceeds 5% single-cycle limit — likely API artifact, peakEquity unchanged",
                        String.format("%.2f", peakEquity), String.format("%.2f", equity),
                        String.format("%.1f", (equity - peakEquity) / peakEquity * 100));
            }
        }

        double dailyPnl = equity - dailyStartBalance;
        double dailyPnlPercent = dailyStartBalance > 0 ? (dailyPnl / dailyStartBalance) * 100 : 0;
        double drawdown = peakEquity > 0 ? ((peakEquity - equity) / peakEquity) * 100 : 0;

        return RiskStatus.builder()
                .availableBalance(balance)
                .totalEquity(equity)
                .dailyPnl(dailyPnl)
                .dailyPnlPercent(dailyPnlPercent)
                .dailyLimitReached(dailyPnlPercent < -config.getMaxDailyLossPercent())
                .peakEquity(peakEquity)
                .currentDrawdown(drawdown)
                .maxDrawdownPercent(config.getMaxDrawdownPercent())
                .drawdownCircuitBreaker(drawdown > config.getMaxDrawdownPercent())
                .build();
    }

    private int getDrawdownThrottledMaxPositions(int maxOpen, double equity) {
        double drawdown = peakEquity > 0 ? ((peakEquity - equity) / peakEquity) * 100 : 0;
        if (drawdown >= config.getDrawdownThrottleSevere())
            return 1;
        if (drawdown >= config.getDrawdownThrottleStart())
            return Math.max(1, maxOpen / 2);
        return maxOpen;
    }

    public double getPeakEquity() {
        return peakEquity;
    }

    public double getDailyStartBalance() {
        return dailyStartBalance;
    }

    public long getDailyResetTimestamp() {
        return dailyResetTimestamp;
    }

    public void initEquity(double equity) {
        if (peakEquity == 0) {
            peakEquity = equity;
            dailyStartBalance = equity;
            dailyResetTimestamp = System.currentTimeMillis();
            log.info("[RISK] initEquity: peakEquity={}, dailyStartBalance={}",
                    String.format("%.2f", equity), String.format("%.2f", equity));
        }
    }

    @Data
    @lombok.Builder
    public static class RiskStatus {
        private double availableBalance;
        private double totalEquity;
        private double dailyPnl;
        private double dailyPnlPercent;
        private boolean dailyLimitReached;
        private double peakEquity;
        private double currentDrawdown;
        private double maxDrawdownPercent;
        private boolean drawdownCircuitBreaker;
        // Enriched fields (t-bot parity)
        private double usedMargin;
        private double usedMarginPercent;
        private int openPositions;
        private int maxPositions;
        private double baseBalance;
        private double realizedPnl;
        private double unrealizedPnl;
        private List<String> openPairs;
    }
}
