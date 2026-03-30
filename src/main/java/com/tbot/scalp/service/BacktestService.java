package com.tbot.scalp.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.BacktestSession;
import com.tbot.scalp.model.BacktestSummary;
import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.PortfolioBacktest;
import com.tbot.scalp.model.Signal;
import com.tbot.scalp.model.TradeResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Walk-forward backtest engine for scalp signals.
 * Same architecture as t-bot: signal detection → walk-forward per signal →
 * portfolio simulation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private final ScalpConfig config;
    private final IndicatorService indicatorService;

    public BacktestSummary runBacktest(List<SessionInput> sessions) {
        long startTime = System.currentTimeMillis();
        BacktestSummary summary = new BacktestSummary();

        for (SessionInput session : sessions) {
            log.info("Backtesting session: {} ({} signals)", session.name(), session.signals().size());

            // Walk-forward each signal
            List<TradeResult> baseTrades = new ArrayList<>();
            for (Signal signal : session.signals()) {
                String key = signal.getPair() + ":" + signal.getTimeframe();
                List<Candle> candles = session.candleMap().get(key);
                if (candles == null || candles.isEmpty())
                    continue;

                TradeResult result = walkForward(signal, candles);
                baseTrades.add(result);
            }

            // Simulate portfolio for each balance
            List<PortfolioBacktest> portfolios = new ArrayList<>();
            for (double balance : config.getPortfolioBalances()) {
                PortfolioBacktest pf = simulatePortfolio(baseTrades, balance);
                portfolios.add(pf);
            }

            summary.getSessions().add(BacktestSession.builder()
                    .sessionName(session.name())
                    .sessionDescription(session.description())
                    .totalSignals(session.signals().size())
                    .portfolios(portfolios)
                    .build());
        }

        summary.setDurationMs(System.currentTimeMillis() - startTime);
        log.info("Backtest complete in {}ms across {} sessions", summary.getDurationMs(), sessions.size());
        return summary;
    }

    private TradeResult walkForward(Signal signal, List<Candle> candles) {
        int startIdx = signal.getCandleIndex() + 1;
        if (startIdx >= candles.size()) {
            return pendingResult(signal);
        }

        // Entry price — market (taker) or limit (maker)
        double entryPrice;
        if (config.isUseMakerOrders()) {
            // Limit order: filled at signal price (no slippage), but not always filled
            int hash = Math.abs((signal.getPair() + signal.getTimeframe() + signal.getCandleIndex()).hashCode());
            if ((hash % 100) >= (int) (config.getMakerFillRate() * 100)) {
                return pendingResult(signal); // not filled — simulate missed order
            }
            entryPrice = signal.getEntryPrice();
        } else {
            double slippage = config.getEntrySlippagePercent() / 100.0;
            entryPrice = "LONG".equals(signal.getDirection())
                    ? signal.getEntryPrice() * (1 + slippage)
                    : signal.getEntryPrice() * (1 - slippage);
        }

        // Adjust TP/SL proportionally to slippage
        double ratio = entryPrice / signal.getEntryPrice();
        double tp = signal.getTakeProfit() * ratio;
        double sl = signal.getStopLoss() * ratio;
        double originalSl = sl;

        ScalpConfig.TimeframeSettings tfSettings = config.getEffectiveSettings(signal.getTimeframe());
        int timeoutCandles = tfSettings.getTimeoutCandles() != null ? tfSettings.getTimeoutCandles() : 20;
        int trendCheckCandles = tfSettings.getTrendCheckCandles() != null ? tfSettings.getTrendCheckCandles() : 8;
        double bePercent = tfSettings.getBreakEvenTriggerPercent() != null ? tfSettings.getBreakEvenTriggerPercent()
                : 55.0;
        Double trailingMult = tfSettings.getTrailingStopAtrMult();

        boolean breakEvenApplied = false;
        int beLevel = 0;
        double[] atr = indicatorService.calculateATR(candles, config.getAtrPeriod());
        double[] ema5 = indicatorService.calculateEMA(candles, 5);
        double[] ema13 = indicatorService.calculateEMA(candles, 13);
        double[] rsi = indicatorService.calculateRSI(candles, config.getRsiPeriod());

        for (int i = startIdx; i < candles.size(); i++) {
            int elapsed = i - startIdx;
            Candle c = candles.get(i);

            // Check TP hit
            if ("LONG".equals(signal.getDirection())) {
                if (c.getHigh() >= tp) {
                    return buildResult(signal, entryPrice, tp, "TP Hit ✅", elapsed, breakEvenApplied,
                            candles.get(i).getTimestamp());
                }
                if (c.getLow() <= sl) {
                    return buildResult(signal, entryPrice, sl, "SL Hit ❌", elapsed, breakEvenApplied,
                            candles.get(i).getTimestamp());
                }
            } else {
                if (c.getLow() <= tp) {
                    return buildResult(signal, entryPrice, tp, "TP Hit ✅", elapsed, breakEvenApplied,
                            candles.get(i).getTimestamp());
                }
                if (c.getHigh() >= sl) {
                    return buildResult(signal, entryPrice, sl, "SL Hit ❌", elapsed, breakEvenApplied,
                            candles.get(i).getTimestamp());
                }
            }

            // Trailing stop (ATR-based)
            if (trailingMult != null && trailingMult > 0 && i < atr.length && atr[i] > 0) {
                double trailSl;
                if ("LONG".equals(signal.getDirection())) {
                    trailSl = c.getClose() - trailingMult * atr[i];
                    if (trailSl > sl)
                        sl = trailSl;
                } else {
                    trailSl = c.getClose() + trailingMult * atr[i];
                    if (trailSl < sl)
                        sl = trailSl;
                }
            }

            // Progressive break-even (3 levels)
            {
                double tpDist = Math.abs(tp - entryPrice);
                double progress;
                if ("LONG".equals(signal.getDirection())) {
                    progress = (c.getClose() - entryPrice) / tpDist * 100;
                } else {
                    progress = (entryPrice - c.getClose()) / tpDist * 100;
                }
                boolean isLong = "LONG".equals(signal.getDirection());
                double newSl;

                if (progress >= bePercent + 25 && beLevel < 3) {
                    newSl = isLong ? entryPrice + 0.5 * tpDist : entryPrice - 0.5 * tpDist;
                    if (isLong ? newSl > sl : newSl < sl)
                        sl = newSl;
                    beLevel = 3;
                    breakEvenApplied = true;
                } else if (progress >= bePercent + 15 && beLevel < 2) {
                    newSl = isLong ? entryPrice + 0.25 * tpDist : entryPrice - 0.25 * tpDist;
                    if (isLong ? newSl > sl : newSl < sl)
                        sl = newSl;
                    beLevel = 2;
                    breakEvenApplied = true;
                } else if (progress >= bePercent && beLevel < 1) {
                    newSl = entryPrice;
                    if (isLong ? newSl > sl : newSl < sl)
                        sl = newSl;
                    beLevel = 1;
                    breakEvenApplied = true;
                }
            }

            // Trend reversal check
            if (elapsed >= trendCheckCandles && i < ema5.length && i < ema13.length && i < rsi.length) {
                boolean trendBroken = false;
                if ("LONG".equals(signal.getDirection())) {
                    trendBroken = ema5[i] < ema13[i] && rsi[i] > 70;
                } else {
                    trendBroken = ema5[i] > ema13[i] && rsi[i] < 30;
                }
                if (trendBroken) {
                    return buildResult(signal, entryPrice, c.getClose(), "Trend Close ↩️", elapsed, breakEvenApplied,
                            c.getTimestamp());
                }
            }

            // Timeout
            if (elapsed >= timeoutCandles) {
                return buildResult(signal, entryPrice, c.getClose(), "Timeout ⏰", elapsed, breakEvenApplied,
                        c.getTimestamp());
            }
        }

        // Ran out of data
        Candle last = candles.get(candles.size() - 1);
        return buildResult(signal, entryPrice, last.getClose(), "Pending ⏳", candles.size() - startIdx,
                breakEvenApplied, last.getTimestamp());
    }

    public PortfolioBacktest simulatePortfolio(List<TradeResult> baseTrades, double initialBalance) {
        double balance = initialBalance;
        double peakBalance = initialBalance;
        double maxDrawdown = 0;
        int wins = 0, losses = 0, skipped = 0, pending = 0;
        double bestTrade = 0, worstTrade = 0;
        List<TradeResult> trades = new ArrayList<>();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        Map<String, Long> cooldowns = new HashMap<>();
        Set<String> openPositions = new HashSet<>();
        double dailyStartBalance = initialBalance;
        long dailyResetTs = 0;

        for (TradeResult base : baseTrades) {
            // Daily reset
            if (dailyResetTs == 0)
                dailyResetTs = base.getEntryTime();
            if (base.getEntryTime() - dailyResetTs > 86_400_000L) {
                dailyStartBalance = balance;
                dailyResetTs = base.getEntryTime();
                peakBalance = Math.max(peakBalance, balance);
            }

            String pairTf = base.getPair() + ":" + base.getTimeframe();

            // Check daily loss
            double dailyPnl = balance - dailyStartBalance;
            if (dailyPnl / dailyStartBalance * 100 < -config.getMaxDailyLossPercent()) {
                skipped++;
                String reason = "DAILY_LOSS_LIMIT";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Drawdown circuit breaker
            double dd = peakBalance > 0 ? (peakBalance - balance) / peakBalance * 100 : 0;
            if (dd > config.getMaxDrawdownPercent()) {
                skipped++;
                String reason = "DRAWDOWN_CIRCUIT_BREAKER";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Score threshold
            if (base.getScore() < config.getEffectiveThreshold(base.getTimeframe())) {
                skipped++;
                String reason = "LOW_SCORE";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // SL distance check
            double slDist = Math.abs(base.getEntryPrice() - base.getStopLoss()) / base.getEntryPrice() * 100;
            if (slDist > config.getEffectiveMaxSl(base.getTimeframe())) {
                skipped++;
                String reason = "MAX_SL_EXCEEDED";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Cooldown
            Long cdExpiry = cooldowns.get(pairTf);
            if (cdExpiry != null && base.getEntryTime() < cdExpiry) {
                skipped++;
                String reason = "COOLDOWN";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Duplicate pair
            if (openPositions.contains(pairTf)) {
                skipped++;
                String reason = "DUPLICATE_PAIR";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Max positions (throttled by drawdown)
            int maxPos = config.getMaxOpenPositions();
            if (dd >= config.getDrawdownThrottleSevere())
                maxPos = 1;
            else if (dd >= config.getDrawdownThrottleStart())
                maxPos = Math.max(1, maxPos / 2);
            if (openPositions.size() >= maxPos) {
                skipped++;
                String reason = "MAX_POSITIONS";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Position sizing — compounding uses current balance, flat uses initial balance
            double sizeBase = config.isBacktestCompounding() ? balance : initialBalance;
            double posSize = sizeBase * config.getPositionSizePercent() / 100.0;
            posSize = Math.max(posSize, config.getMinPositionSize());
            if (posSize > balance) {
                skipped++;
                String reason = "INSUFFICIENT_BALANCE";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Max loss per trade
            double maxLossPercent = slDist * base.getLeverage() * config.getPositionSizePercent() / 100.0;
            if (maxLossPercent > config.getMaxLossPerTradePercent() * 3) { // 3x relaxed for backtest
                skipped++;
                String reason = "MAX_LOSS_PER_TRADE";
                skipReasons.merge(reason, 1, Integer::sum);
                trades.add(asSkipped(base, reason, balance));
                continue;
            }

            // Execute trade
            openPositions.add(pairTf);
            double pnlUsd = 0;
            double feeUsd = 0;
            if (!"Pending ⏳".equals(base.getResult()) && !"Skipped 🚫".equals(base.getResult())) {
                double pnlPct = base.getPnlPercent();
                pnlUsd = posSize * pnlPct / 100.0;
                // feeUsd: round-trip fee in dollars (negative = rebate for maker)
                double feePercent = config.isUseMakerOrders()
                        ? config.getMakerFeePercent() / 100.0
                        : config.getTakerFeePercent() / 100.0;
                feeUsd = feePercent * 2 * base.getLeverage() * posSize;
                balance += pnlUsd;

                if (balance > peakBalance)
                    peakBalance = balance;
                double currentDD = (peakBalance - balance) / peakBalance * 100;
                if (currentDD > maxDrawdown)
                    maxDrawdown = currentDD;

                if (pnlUsd > 0)
                    wins++;
                else
                    losses++;
                if (pnlUsd > bestTrade)
                    bestTrade = pnlUsd;
                if (pnlUsd < worstTrade)
                    worstTrade = pnlUsd;
            } else {
                pending++;
            }

            openPositions.remove(pairTf);

            // Cooldown
            long candleDuration = config.getTimeframeDurationMs(base.getTimeframe());
            cooldowns.put(pairTf, base.getExitTime() + config.getCooldownMultiplier() * candleDuration);

            TradeResult tr = TradeResult.builder()
                    .entryTime(base.getEntryTime()).exitTime(base.getExitTime())
                    .pair(base.getPair()).timeframe(base.getTimeframe())
                    .direction(base.getDirection()).strategies(base.getStrategies())
                    .entryPrice(base.getEntryPrice()).exitPrice(base.getExitPrice())
                    .stopLoss(base.getStopLoss()).takeProfit(base.getTakeProfit())
                    .leverage(base.getLeverage()).positionSizeUsd(posSize)
                    .score(base.getScore())
                    .result(base.getResult()).pnl(pnlUsd)
                    .pnlPercent(base.getPnlPercent())
                    .feeUsd(Math.round(feeUsd * 100.0) / 100.0)
                    .candlesElapsed(base.getCandlesElapsed())
                    .breakEvenApplied(base.isBreakEvenApplied())
                    .balanceAfter(balance)
                    .build();
            trades.add(tr);
        }

        int total = wins + losses;
        double roi = initialBalance > 0 ? (balance - initialBalance) / initialBalance * 100 : 0;
        double winRate = total > 0 ? (double) wins / total * 100 : 0;

        return PortfolioBacktest.builder()
                .initialBalance(initialBalance).finalBalance(Math.round(balance * 100.0) / 100.0)
                .roi(Math.round(roi * 100.0) / 100.0)
                .winRate(Math.round(winRate * 10.0) / 10.0)
                .totalTrades(total).wins(wins).losses(losses).pending(pending).skipped(skipped)
                .maxDrawdown(Math.round(maxDrawdown * 100.0) / 100.0)
                .bestTrade(Math.round(bestTrade * 100.0) / 100.0)
                .worstTrade(Math.round(worstTrade * 100.0) / 100.0)
                .trades(trades).skipReasons(skipReasons)
                .build();
    }

    private TradeResult buildResult(Signal signal, double entryPrice, double exitPrice,
            String result, int elapsed, boolean beApplied, long exitTime) {
        // Exit slippage — only for market (taker) orders; limit exits have no slippage
        double adjustedExit;
        if (config.isUseMakerOrders()) {
            adjustedExit = exitPrice;
        } else {
            double exitSlip = config.getExitSlippagePercent() / 100.0;
            adjustedExit = "LONG".equals(signal.getDirection())
                    ? exitPrice * (1 - exitSlip)
                    : exitPrice * (1 + exitSlip);
        }

        double pnl;
        if ("LONG".equals(signal.getDirection())) {
            pnl = (adjustedExit - entryPrice) / entryPrice * 100 * signal.getLeverage();
        } else {
            pnl = (entryPrice - adjustedExit) / entryPrice * 100 * signal.getLeverage();
        }

        // Round-trip fees: taker (cost) or maker (rebate, can be negative)
        double feePercent = config.isUseMakerOrders()
                ? config.getMakerFeePercent() / 100.0
                : config.getTakerFeePercent() / 100.0;
        double feeCostPct = feePercent * 2 * signal.getLeverage() * 100; // as % of capital
        pnl -= feeCostPct;

        return TradeResult.builder()
                .entryTime(signal.getTimestamp()).exitTime(exitTime)
                .pair(signal.getPair()).timeframe(signal.getTimeframe())
                .feeUsd(0) // set later in simulatePortfolio when posSize is known
                .direction(signal.getDirection()).strategies(signal.getStrategies())
                .entryPrice(entryPrice).exitPrice(exitPrice)
                .stopLoss(signal.getStopLoss()).takeProfit(signal.getTakeProfit())
                .leverage(signal.getLeverage())
                .score(signal.getScore())
                .result(result).pnlPercent(Math.round(pnl * 100.0) / 100.0)
                .candlesElapsed(elapsed).breakEvenApplied(beApplied)
                .build();
    }

    private TradeResult pendingResult(Signal signal) {
        return TradeResult.builder()
                .entryTime(signal.getTimestamp()).exitTime(signal.getTimestamp())
                .pair(signal.getPair()).timeframe(signal.getTimeframe())
                .direction(signal.getDirection()).strategies(signal.getStrategies())
                .entryPrice(signal.getEntryPrice()).exitPrice(signal.getEntryPrice())
                .stopLoss(signal.getStopLoss()).takeProfit(signal.getTakeProfit())
                .leverage(signal.getLeverage()).score(signal.getScore())
                .result("Pending ⏳").pnlPercent(0)
                .build();
    }

    private TradeResult asSkipped(TradeResult base, String reason, double balance) {
        return TradeResult.builder()
                .entryTime(base.getEntryTime()).exitTime(base.getEntryTime())
                .pair(base.getPair()).timeframe(base.getTimeframe())
                .direction(base.getDirection()).strategies(base.getStrategies())
                .entryPrice(base.getEntryPrice()).exitPrice(base.getEntryPrice())
                .stopLoss(base.getStopLoss()).takeProfit(base.getTakeProfit())
                .leverage(base.getLeverage()).score(base.getScore())
                .result("Skipped 🚫").pnl(0).pnlPercent(0)
                .skipReason(reason).balanceAfter(balance)
                .build();
    }

    public record SessionInput(String name, String description, List<Signal> signals,
            Map<String, List<Candle>> candleMap) {
    }
}
