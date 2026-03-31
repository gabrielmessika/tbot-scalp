package com.tbot.scalp.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.AnalysisResult;
import com.tbot.scalp.model.BacktestSession;
import com.tbot.scalp.model.BacktestSummary;
import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.PortfolioBacktest;
import com.tbot.scalp.model.RawSignal;
import com.tbot.scalp.model.Signal;
import com.tbot.scalp.service.strategy.ScalpStrategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final ScalpConfig config;
    private final HyperliquidMarketDataService marketDataService;
    private final BinanceBacktestDataService binanceDataService;
    private final IndicatorService indicatorService;
    private final SignalScoringService scoringService;
    private final BacktestService backtestService;
    private final BacktestExportService exportService;
    private final List<ScalpStrategy> strategies;

    private final AtomicReference<AnalysisResult> lastResult = new AtomicReference<>();
    private volatile boolean backtestRunning = false;

    public AnalysisResult runBacktest() {
        if (backtestRunning) {
            log.warn("Backtest already running, skipping");
            return lastResult.get();
        }
        backtestRunning = true;
        long startMs = System.currentTimeMillis();
        List<AnalysisResult.LogEntry> logs = new ArrayList<>();

        try {
            boolean useBinance = config.isBacktestUseBinance();
            logEntry(logs, "INFO", "Starting backtest with %d strategies, %d coins, %d timeframes (data: %s)",
                    strategies.size(), config.getCoins().size(), config.getTimeframes().size(),
                    useBinance ? "Binance Futures" : "Hyperliquid");
            logEnabledStrategies(logs);

            List<BacktestService.SessionInput> sessions = new ArrayList<>();

            for (String sessionSpec : config.getBacktestSessions()) {
                int[] parsed = parseSession(sessionSpec);
                int daysAgo = parsed[0];
                int windowDays = parsed[1] > 0 ? parsed[1] : config.getBacktestDays();
                String name = sessionName(sessionSpec);
                String desc = sessionDescription(sessionSpec, daysAgo, windowDays);

                logEntry(logs, "INFO", "Session %s: fetching candles (-%dd, %dd window)", name, daysAgo, windowDays);

                List<Signal> allSignals = new ArrayList<>();
                Map<String, List<Candle>> candleMap = new HashMap<>();

                for (String coin : config.getCoins()) {
                    for (String tf : config.getTimeframes()) {
                        List<Candle> candles = useBinance
                                ? binanceDataService.fetchCandles(coin, tf, daysAgo, windowDays)
                                : marketDataService.fetchCandles(coin, tf, daysAgo, windowDays);
                        if (candles.size() < 50) {
                            logEntry(logs, "WARN", "%s %s: only %d candles, skipping", coin, tf, candles.size());
                            continue;
                        }

                        String key = coin + ":" + tf;
                        candleMap.put(key, candles);

                        List<Signal> signals = detectAndScore(candles, coin, tf);
                        allSignals.addAll(signals);
                        logEntry(logs, "INFO", "%s %s: %d candles → %d signals", coin, tf, candles.size(),
                                signals.size());
                    }
                }

                allSignals.sort(Comparator.comparingLong(Signal::getTimestamp));
                sessions.add(new BacktestService.SessionInput(name, desc, allSignals, candleMap));
                logEntry(logs, "INFO", "Session %s: %d total signals", name, allSignals.size());
            }

            BacktestSummary summary = backtestService.runBacktest(sessions);
            exportService.export(summary);

            logEntry(logs, "INFO", "Backtest complete in %dms", System.currentTimeMillis() - startMs);

            // Log summary
            for (BacktestSession bs : summary.getSessions()) {
                for (PortfolioBacktest pf : bs.getPortfolios()) {
                    logEntry(logs, "INFO", "  %s $%.0f: ROI=%.1f%% WR=%.1f%% Trades=%d MaxDD=%.1f%%",
                            bs.getSessionName(), pf.getInitialBalance(), pf.getRoi(),
                            pf.getWinRate(), pf.getTotalTrades(), pf.getMaxDrawdown());
                }
            }

            AnalysisResult result = AnalysisResult.builder()
                    .logs(logs).backtest(summary)
                    .analysisTimestamp(System.currentTimeMillis())
                    .analysisTimeMs(System.currentTimeMillis() - startMs)
                    .build();
            lastResult.set(result);
            return result;
        } catch (Exception e) {
            log.error("Backtest failed", e);
            logEntry(logs, "ERROR", "Backtest failed: %s", e.getMessage());
            AnalysisResult result = AnalysisResult.builder().logs(logs)
                    .analysisTimestamp(System.currentTimeMillis())
                    .analysisTimeMs(System.currentTimeMillis() - startMs).build();
            lastResult.set(result);
            return result;
        } finally {
            backtestRunning = false;
        }
    }

    public AnalysisResult runCurrentAnalysis() {
        long startMs = System.currentTimeMillis();
        List<AnalysisResult.LogEntry> logs = new ArrayList<>();
        List<Signal> alerts = new ArrayList<>();

        try {
            for (String coin : config.getCoins()) {
                for (String tf : config.getTimeframes()) {
                    List<Candle> candles = marketDataService.fetchRecentCandles(coin, tf, config.getLiveCandleCount());
                    if (candles.size() < 50)
                        continue;

                    // Exclude last (incomplete) candle — indicators on closed candles are stable
                    List<Candle> closedCandles = candles.subList(0, candles.size() - 1);
                    List<Signal> signals = detectAndScore(closedCandles, coin, tf);
                    // Filter to recent signals only
                    int maxAge = config.getMaxSignalAgeCandlesLive();
                    int minIdx = candles.size() - maxAge;
                    signals.stream()
                            .filter(s -> s.getCandleIndex() >= minIdx)
                            .forEach(s -> {
                                alerts.add(s);
                                logEntry(logs, "SIGNAL", s.getFormattedAlert());
                            });
                }
            }

            logEntry(logs, "INFO", "Analysis complete: %d actionable signals", alerts.size());
        } catch (Exception e) {
            logEntry(logs, "ERROR", "Analysis failed: %s", e.getMessage());
        }

        AnalysisResult result = AnalysisResult.builder()
                .logs(logs).alerts(alerts)
                .backtest(lastResult.get() != null ? lastResult.get().getBacktest() : null)
                .analysisTimestamp(System.currentTimeMillis())
                .analysisTimeMs(System.currentTimeMillis() - startMs)
                .build();
        lastResult.set(result);
        return result;
    }

    private List<Signal> detectAndScore(List<Candle> candles, String coin, String tf) {
        double[] atr = indicatorService.calculateATR(candles, config.getAtrPeriod());
        double[] rsi = indicatorService.calculateRSI(candles, config.getRsiPeriod());
        double[] adx = indicatorService.calculateADX(candles, config.getAtrPeriod());
        double[] vwap = indicatorService.calculateSessionVWAP(candles, 86_400_000L);
        double[] relVol = indicatorService.calculateRelativeVolume(candles, 20);
        double[] ema5 = indicatorService.calculateEMA(candles, 5);
        double[] ema13 = indicatorService.calculateEMA(candles, 13);
        double[] ema21 = indicatorService.calculateEMA(candles, 21);
        double[][] emas = new double[][] { ema5, ema13, ema21 };

        Map<String, List<RawSignal>> strategySignals = new LinkedHashMap<>();
        for (ScalpStrategy strategy : strategies) {
            if (!isStrategyEnabled(strategy.getName()))
                continue;
            List<RawSignal> raw = strategy.detect(candles, atr, rsi, emas, vwap);
            if (!raw.isEmpty()) {
                strategySignals.put(strategy.getName(), raw);
            }
        }

        int coinMaxLev = marketDataService.getMaxLeverage(coin);
        return scoringService.scoreSignals(strategySignals, candles, atr, rsi, adx, vwap, relVol,
                coin, tf, coinMaxLev > 0 ? coinMaxLev : 50);
    }

    private boolean isStrategyEnabled(String name) {
        return switch (name) {
            case "EMA Crossover" -> config.isEmaCrossEnabled();
            case "RSI Divergence" -> config.isRsiDivergenceEnabled();
            case "VWAP Bounce" -> config.isVwapBounceEnabled();
            case "Bollinger Squeeze" -> config.isBollingerSqueezeEnabled();
            case "Order Flow" -> config.isOrderFlowEnabled();
            case "Momentum Scalp" -> config.isMomentumScalpEnabled();
            case "Absorption Candle" -> config.isAbsorptionCandleEnabled();
            case "Naked POC" -> config.isNakedPocEnabled();
            case "Opening Range Breakout" -> config.isOpeningRangeBreakoutEnabled();
            default -> true;
        };
    }

    private void logEnabledStrategies(List<AnalysisResult.LogEntry> logs) {
        for (ScalpStrategy s : strategies) {
            logEntry(logs, "INFO", "  Strategy %s: %s (weight %.1f)",
                    s.getName(), isStrategyEnabled(s.getName()) ? "✅" : "❌",
                    config.getStrategyWeights().getOrDefault(s.getName(), 1.0));
        }
    }

    public AnalysisResult getLastResult() {
        return lastResult.get();
    }

    public boolean isBacktestRunning() {
        return backtestRunning;
    }

    // ===== Session parsing =====

    int[] parseSession(String spec) {
        // Format: "90" or "90:10" or "2025-04-07:20" or "2025-04-07:20:Label"
        String[] parts = spec.split(":");
        try {
            if (parts[0].contains("-")) {
                // Absolute date
                LocalDate date = LocalDate.parse(parts[0], DateTimeFormatter.ISO_LOCAL_DATE);
                long dateMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                int daysAgo = (int) ((System.currentTimeMillis() - dateMs) / 86_400_000L);
                int window = parts.length > 1 ? Integer.parseInt(parts[1]) : config.getBacktestDays();
                return new int[] { daysAgo, window };
            } else {
                int daysAgo = Integer.parseInt(parts[0]);
                int window = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
                return new int[] { daysAgo, window };
            }
        } catch (Exception e) {
            return new int[] { 30, config.getBacktestDays() };
        }
    }

    String sessionName(String spec) {
        String[] parts = spec.split(":");
        if (parts[0].contains("-")) {
            return parts.length > 2 ? parts[2] : "S" + parts[0];
        }
        // Support optional label for numeric specs too (e.g. "0:3:Current")
        if (parts.length > 2) {
            return parts[2];
        }
        int days = Integer.parseInt(parts[0]);
        return "J-" + days + (parts.length > 1 ? " (" + parts[1] + "j)" : "");
    }

    String sessionDescription(String spec, int daysAgo, int windowDays) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        Instant start = Instant.now().minusMillis((long) daysAgo * 86_400_000L);
        Instant end = start.plusMillis((long) windowDays * 86_400_000L);
        return fmt.format(start.atZone(ZoneOffset.UTC)) + " → " + fmt.format(end.atZone(ZoneOffset.UTC));
    }

    private void logEntry(List<AnalysisResult.LogEntry> logs, String level, String format, Object... args) {
        String msg = String.format(format, args);
        logs.add(AnalysisResult.LogEntry.builder()
                .timestamp(System.currentTimeMillis()).level(level).message(msg).build());
        if ("ERROR".equals(level))
            log.error(msg);
        else if ("WARN".equals(level))
            log.warn(msg);
        else
            log.info(msg);
    }
}
