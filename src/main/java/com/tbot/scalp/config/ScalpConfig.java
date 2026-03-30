package com.tbot.scalp.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Data;

@Data
@Configuration
@ConfigurationProperties(prefix = "scalp")
public class ScalpConfig {

    // ===== MARKET DATA =====
    private List<String> coins = List.of("BTC", "ETH", "SOL", "HYPE", "SUI", "DOGE", "AVAX", "LINK", "ADA", "XRP",
            "WIF", "ARB", "OP", "APT", "SEI", "INJ", "TIA", "JUP", "ONDO",
            "RENDER", "FET", "WLD", "NEAR", "AAVE", "MKR", "PENDLE", "STX", "FIL", "ATOM");
    private List<String> timeframes = List.of("1m", "3m");
    private String exchange = "hyperliquid";

    // ===== BACKTEST =====
    private boolean backtestUseBinance = true;
    private int backtestDays = 7;
    private List<String> backtestSessions = List.of("90:7", "60:7", "45:7", "30:7", "14:7", "7:7", "3:3", "0:3");
    private List<Double> portfolioBalances = List.of(200.0, 500.0, 1000.0, 2500.0);
    private double entrySlippagePercent = 0.03; // tighter for scalp

    // ===== POSITION SIZING =====
    private double positionSizePercent = 15.0;
    private double minPositionSize = 11.0;
    private double dryRunBalance = 1000.0;
    private int minLeverage = 5;
    private int maxLeverage = 50;
    private double maxSlPercent = 1.5;

    // ===== EXECUTION =====
    private boolean autoTrade = false;
    private boolean liveTrading = false;
    private int maxOpenPositions = 8;
    private double maxMarginUsagePercent = 60.0;
    private double maxLossPerTradePercent = 3.0;
    private double maxDailyLossPercent = 10.0;
    private double maxDrawdownPercent = 20.0;
    private double drawdownThrottleStart = 7.0;
    private double drawdownThrottleSevere = 12.0;

    // ===== SCORING =====
    private double confidentThreshold = 11.0;
    private double minScore = 4.0;
    private int atrPeriod = 14;
    private int rsiPeriod = 14;

    // ===== STRATEGIES =====
    private boolean emaCrossEnabled = true;
    private boolean rsiDivergenceEnabled = true;
    private boolean vwapBounceEnabled = true;
    private boolean bollingerSqueezeEnabled = true;
    private boolean orderFlowEnabled = true;
    private boolean momentumScalpEnabled = true;

    private Map<String, Double> strategyWeights = new HashMap<>(Map.of(
            "EMA Crossover", 2.0,
            "RSI Divergence", 2.5,
            "VWAP Bounce", 3.0,
            "Bollinger Squeeze", 2.5,
            "Order Flow", 3.5,
            "Momentum Scalp", 3.0));

    // ===== TIMEFRAME SETTINGS =====
    private Map<String, TimeframeSettings> timeframeSettings = new HashMap<>();

    // ===== TRAILING STOP =====
    private double trailingStopAtrMult = 1.5;

    // ===== COOLDOWN =====
    private int cooldownMultiplier = 2; // 2× candle duration

    // ===== CONTRARY SIGNALS =====
    private boolean contrarySignalEnabled = true;
    private int contrarySignalThreshold = 3;

    // ===== PAIR SCORING =====
    private Map<String, Double> pairScoring = new HashMap<>();

    // ===== API =====
    private String hyperliquidApiUrl = "https://api.hyperliquid.xyz";
    private String hyperliquidWsUrl = "wss://api.hyperliquid.xyz/ws";
    private String hyperliquidPrivateKey = "";
    private String hyperliquidWalletAddress = "";

    // ===== STARTUP =====
    private boolean startupBacktest = true;
    private boolean startupChecks = true;
    private int maxSignalAgeCandlesLive = 3;

    @Data
    public static class TimeframeSettings {
        private Double scoringBonus;
        private Double confidentThreshold;
        private Integer trendCheckCandles;
        private Integer timeoutCandles;
        private Double breakEvenTriggerPercent;
        private Double trailingStopAtrMult;
        private Double maxSlPercent;

        public static TimeframeSettings defaultsFor(String tf) {
            TimeframeSettings s = new TimeframeSettings();
            switch (tf) {
                case "1m" -> {
                    s.scoringBonus = 0.5;
                    s.trendCheckCandles = 8;
                    s.timeoutCandles = 15;
                    s.breakEvenTriggerPercent = 55.0;
                    s.trailingStopAtrMult = 1.0;
                    s.maxSlPercent = 0.5;
                }
                case "3m" -> {
                    s.scoringBonus = 0.8;
                    s.trendCheckCandles = 8;
                    s.timeoutCandles = 20;
                    s.breakEvenTriggerPercent = 50.0;
                    s.trailingStopAtrMult = 1.2;
                    s.maxSlPercent = 0.8;
                }
                case "5m" -> {
                    s.scoringBonus = 1.0;
                    s.trendCheckCandles = 10;
                    s.timeoutCandles = 24;
                    s.breakEvenTriggerPercent = 45.0;
                    s.trailingStopAtrMult = 1.5;
                    s.maxSlPercent = 1.0;
                }
                case "15m" -> {
                    s.scoringBonus = 0.8;
                    s.trendCheckCandles = 8;
                    s.timeoutCandles = 16;
                    s.breakEvenTriggerPercent = 50.0;
                    s.trailingStopAtrMult = 2.0;
                    s.maxSlPercent = 1.5;
                }
                default -> {
                    s.scoringBonus = 0.5;
                    s.trendCheckCandles = 6;
                    s.timeoutCandles = 12;
                    s.breakEvenTriggerPercent = 50.0;
                    s.trailingStopAtrMult = 1.5;
                    s.maxSlPercent = 1.0;
                }
            }
            return s;
        }
    }

    public TimeframeSettings getEffectiveSettings(String tf) {
        TimeframeSettings defaults = TimeframeSettings.defaultsFor(tf);
        TimeframeSettings override = timeframeSettings.get(tf);
        if (override == null)
            return defaults;

        if (override.scoringBonus != null)
            defaults.scoringBonus = override.scoringBonus;
        if (override.confidentThreshold != null)
            defaults.confidentThreshold = override.confidentThreshold;
        if (override.trendCheckCandles != null)
            defaults.trendCheckCandles = override.trendCheckCandles;
        if (override.timeoutCandles != null)
            defaults.timeoutCandles = override.timeoutCandles;
        if (override.breakEvenTriggerPercent != null)
            defaults.breakEvenTriggerPercent = override.breakEvenTriggerPercent;
        if (override.trailingStopAtrMult != null)
            defaults.trailingStopAtrMult = override.trailingStopAtrMult;
        if (override.maxSlPercent != null)
            defaults.maxSlPercent = override.maxSlPercent;
        return defaults;
    }

    public double getEffectiveMaxSl(String tf) {
        TimeframeSettings s = getEffectiveSettings(tf);
        return s.getMaxSlPercent() != null ? s.getMaxSlPercent() : maxSlPercent;
    }

    public double getEffectiveThreshold(String tf) {
        TimeframeSettings s = getEffectiveSettings(tf);
        return s.getConfidentThreshold() != null ? s.getConfidentThreshold() : confidentThreshold;
    }

    public long getTimeframeDurationMs(String tf) {
        return switch (tf) {
            case "1m" -> 60_000L;
            case "3m" -> 180_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "1h" -> 3_600_000L;
            default -> 300_000L;
        };
    }
}
