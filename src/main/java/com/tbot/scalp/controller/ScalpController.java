package com.tbot.scalp.controller;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.config.StartupRunner;
import com.tbot.scalp.model.AnalysisResult;
import com.tbot.scalp.model.OpenPosition;
import com.tbot.scalp.service.AnalysisService;
import com.tbot.scalp.service.HyperliquidExecutionService;
import com.tbot.scalp.service.HyperliquidWebSocketService;
import com.tbot.scalp.service.OrderManagerService;
import com.tbot.scalp.service.PositionManagerService;
import com.tbot.scalp.service.RiskManagementService;
import com.tbot.scalp.service.TradeHistoryService;
import com.tbot.scalp.service.TradeJournalService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScalpController {

    private final AnalysisService analysisService;
    private final PositionManagerService positionManager;
    private final RiskManagementService riskService;
    private final OrderManagerService orderManager;
    private final HyperliquidExecutionService executionService;
    private final HyperliquidWebSocketService wsService;
    private final TradeJournalService journalService;
    private final TradeHistoryService historyService;
    private final ScalpConfig config;
    private final StartupRunner startupRunner;

    // ==================== Analysis & Backtest ====================

    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResult> analyze() {
        return ResponseEntity.ok(analysisService.runCurrentAnalysis());
    }

    @PostMapping("/backtest")
    public ResponseEntity<AnalysisResult> backtest() {
        if (analysisService.isBacktestRunning()) {
            return ResponseEntity.status(409).build();
        }
        return ResponseEntity.ok(analysisService.runBacktest());
    }

    @GetMapping("/results")
    public ResponseEntity<AnalysisResult> results() {
        AnalysisResult result = analysisService.getLastResult();
        return result != null ? ResponseEntity.ok(result) : ResponseEntity.noContent().build();
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    // ==================== Bot State ====================

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("openPositions", positionManager.getOpenPositions());
        state.put("cooldowns", positionManager.getActiveCooldowns());
        state.put("wsConnected", wsService.isConnected());

        RiskManagementService.RiskStatus risk = orderManager.getRiskStatus();
        state.put("risk", risk);

        state.put("autoTrade", config.isAutoTrade());
        state.put("liveTrading", config.isLiveTrading());
        state.put("exchange", config.getExchange());
        state.put("startupComplete", startupRunner.isStartupComplete());
        state.put("startupPhase", startupRunner.getStartupPhase());
        state.put("backtestRunning", analysisService.isBacktestRunning());
        state.put("pendingOrders", positionManager.pendingOrderCount());

        Map<String, Object> tfConfigs = new LinkedHashMap<>();
        for (String tf : config.getTimeframes()) {
            var tfs = config.getEffectiveSettings(tf);
            Map<String, Object> tfMap = new LinkedHashMap<>();
            tfMap.put("timeoutCandles", tfs.getTimeoutCandles());
            tfConfigs.put(tf, tfMap);
        }
        state.put("timeframeSettings", tfConfigs);

        return ResponseEntity.ok(state);
    }

    @GetMapping("/bot/state")
    public ResponseEntity<Map<String, Object>> botState() {
        Map<String, Object> state = new LinkedHashMap<>();

        List<OpenPosition> positions = positionManager.getOpenPositions();
        state.put("openPositions", positions);
        state.put("openCount", positions.size());
        state.put("pendingOrders", positionManager.pendingOrderCount());
        state.put("cooldowns", positionManager.getActiveCooldowns());

        RiskManagementService.RiskStatus riskStatus = orderManager.getRiskStatus();
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("peakEquity", riskService.getPeakEquity());
        risk.put("dailyStartBalance", riskService.getDailyStartBalance());
        risk.put("dailyResetTimestamp", riskService.getDailyResetTimestamp());
        risk.put("availableBalance", riskStatus.getAvailableBalance());
        risk.put("totalEquity", riskStatus.getTotalEquity());
        risk.put("dailyPnl", riskStatus.getDailyPnl());
        risk.put("dailyPnlPercent", riskStatus.getDailyPnlPercent());
        risk.put("dailyLimitReached", riskStatus.isDailyLimitReached());
        risk.put("currentDrawdown", riskStatus.getCurrentDrawdown());
        risk.put("drawdownCircuitBreaker", riskStatus.isDrawdownCircuitBreaker());
        risk.put("usedMargin", riskStatus.getUsedMargin());
        risk.put("usedMarginPercent", riskStatus.getUsedMarginPercent());
        risk.put("openPositions", riskStatus.getOpenPositions());
        risk.put("maxPositions", riskStatus.getMaxPositions());
        state.put("risk", risk);

        state.put("autoTrade", config.isAutoTrade());
        state.put("liveTrading", config.isLiveTrading());
        state.put("exchange", config.getExchange());
        state.put("startupComplete", startupRunner.isStartupComplete());
        state.put("startupPhase", startupRunner.getStartupPhase());
        state.put("wsConnected", wsService.isConnected());

        Map<String, Object> tfConfigs = new LinkedHashMap<>();
        for (String tf : config.getTimeframes()) {
            var tfs = config.getEffectiveSettings(tf);
            Map<String, Object> tfMap = new LinkedHashMap<>();
            tfMap.put("timeoutCandles", tfs.getTimeoutCandles());
            tfMap.put("trendCheckCandles", tfs.getTrendCheckCandles());
            tfMap.put("breakEvenTriggerPercent", tfs.getBreakEvenTriggerPercent());
            tfConfigs.put(tf, tfMap);
        }
        state.put("timeframeSettings", tfConfigs);

        return ResponseEntity.ok(state);
    }

    // ==================== Config ====================

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("coins", config.getCoins());
        cfg.put("timeframes", config.getTimeframes());
        cfg.put("exchange", config.getExchange());
        cfg.put("strategies", config.getStrategyWeights());
        cfg.put("positionSizePercent", config.getPositionSizePercent());
        cfg.put("maxLeverage", config.getMaxLeverage());
        cfg.put("maxSlPercent", config.getMaxSlPercent());
        cfg.put("confidentThreshold", config.getConfidentThreshold());
        cfg.put("maxOpenPositions", config.getMaxOpenPositions());
        cfg.put("maxDailyLossPercent", config.getMaxDailyLossPercent());
        cfg.put("maxDrawdownPercent", config.getMaxDrawdownPercent());
        cfg.put("backtestSessions", config.getBacktestSessions());
        cfg.put("portfolioBalances", config.getPortfolioBalances());
        cfg.put("autoTrade", config.isAutoTrade());
        cfg.put("liveTrading", config.isLiveTrading());
        cfg.put("maxLossPerTradePercent", config.getMaxLossPerTradePercent());
        cfg.put("maxMarginUsagePercent", config.getMaxMarginUsagePercent());
        return ResponseEntity.ok(cfg);
    }

    // ==================== Positions & Cooldowns ====================

    @GetMapping("/cooldowns")
    public ResponseEntity<Map<String, Long>> cooldowns() {
        return ResponseEntity.ok(positionManager.getActiveCooldowns());
    }

    @GetMapping("/positions")
    public ResponseEntity<?> positions() {
        return ResponseEntity.ok(positionManager.getOpenPositions());
    }

    @PostMapping("/close-position/{pair}")
    public ResponseEntity<Map<String, String>> closePosition(@PathVariable String pair) {
        log.warn("[MANUAL] Close requested for {}", pair);
        OpenPosition pos = positionManager.getOpenPositions().stream()
                .filter(p -> p.getPair().equals(pair))
                .findFirst().orElse(null);
        if (pos == null) {
            return ResponseEntity.notFound().build();
        }
        String key = pos.getPair() + ":" + pos.getTimeframe();
        // closePosition handles exchange close internally when live
        positionManager.closePosition(key, pos.getCurrentPrice(), "MANUAL");
        return ResponseEntity.ok(Map.of("status", "closed", "pair", pair));
    }

    // ==================== Execution & Risk ====================

    @GetMapping("/execution-status")
    public ResponseEntity<Map<String, Object>> executionStatus() {
        return ResponseEntity.ok(Map.of(
                "autoTrade", config.isAutoTrade(),
                "liveTrading", config.isLiveTrading(),
                "exchange", config.getExchange()));
    }

    @GetMapping("/startup-status")
    public ResponseEntity<Map<String, Object>> startupStatus() {
        return ResponseEntity.ok(Map.of(
                "complete", startupRunner.isStartupComplete(),
                "phase", startupRunner.getStartupPhase()));
    }

    @GetMapping("/bot/exchange/balance")
    public ResponseEntity<Map<String, Object>> botExchangeBalance() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (config.isLiveTrading()) {
            result.put("mode", "live");
            double b = executionService.getAvailableBalance();
            double e = executionService.getEquity();
            result.put("balance", Double.isNaN(b) ? 0 : b);
            result.put("equity", Double.isNaN(e) ? 0 : e);
        } else {
            result.put("mode", "dry-run");
            result.put("balance", orderManager.getEffectiveBalance());
            result.put("equity", orderManager.getEffectiveEquity());
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/bot/exchange/positions")
    public ResponseEntity<?> exchangePositions() {
        if (!config.isLiveTrading()) {
            return ResponseEntity.ok(Map.of("mode", "dry-run", "positions", List.of()));
        }
        try {
            return ResponseEntity.ok(executionService.getExchangePositions());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/bot/pending-orders")
    public ResponseEntity<Map<String, Object>> pendingOrders() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", positionManager.pendingOrderCount());
        result.put("hasPending", positionManager.hasPendingOrders());
        result.put("orders", positionManager.getPendingOrderDetails());
        return ResponseEntity.ok(result);
    }

    // ==================== Trade Journal ====================

    @GetMapping("/trades")
    public ResponseEntity<List<Map<String, Object>>> trades() {
        List<Map<String, Object>> journal = journalService.readAllParsed();

        // Build index of closed trades by clientOrderId for O(1) lookup
        Map<String, Map<String, Object>> closedByOrderId = new java.util.HashMap<>();
        for (Map<String, Object> h : historyService.readAllParsed()) {
            Object cid = h.get("clientOrderId");
            if (cid != null)
                closedByOrderId.put(String.valueOf(cid), h);
        }

        // Annotate journal entries with close info when available
        for (Map<String, Object> entry : journal) {
            Object cid = entry.get("clientOrderId");
            if (cid != null) {
                Map<String, Object> closed = closedByOrderId.get(String.valueOf(cid));
                if (closed != null) {
                    entry.put("closeReason", closed.get("closeReason"));
                    entry.put("exitPrice", closed.get("exitPrice"));
                    entry.put("closeDate", closed.get("closeDate"));
                    entry.put("pnlPercent", closed.get("pnlPercent"));
                    entry.put("pnlUsd", closed.get("pnlUsd"));
                }
            }
        }

        return ResponseEntity.ok(journal);
    }

    // ==================== Trade History ====================

    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> history() {
        return ResponseEntity.ok(historyService.readAllParsed());
    }

    @GetMapping("/history/summary")
    public ResponseEntity<Map<String, Object>> historySummary() {
        return ResponseEntity.ok(historyService.getSummary());
    }

    // ==================== Trade Statistics ====================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> tradeStats() {
        List<Map<String, Object>> trades = historyService.readAllParsed();
        Map<String, Object> stats = new LinkedHashMap<>();

        if (trades.isEmpty()) {
            stats.put("message", "No trade history available");
            return ResponseEntity.ok(stats);
        }

        // Compute stats for multiple periods
        long now = System.currentTimeMillis();
        int[] periods = { 7, 14, 30, 60, 90 };
        Map<String, Object> periodStats = new LinkedHashMap<>();
        for (int days : periods) {
            long cutoff = now - (long) days * 86_400_000L;
            List<Map<String, Object>> filtered = trades.stream()
                    .filter(t -> {
                        Object cd = t.get("closeDate");
                        if (cd instanceof String s) {
                            try {
                                return java.time.LocalDateTime.parse(s,
                                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() >= cutoff;
                            } catch (Exception e) {
                                return true;
                            }
                        }
                        return true;
                    }).toList();
            periodStats.put(days + "d", computePeriodStats(filtered));
        }
        stats.put("periods", periodStats);

        // Breakdown by pair
        Map<String, List<Map<String, Object>>> byPair = new LinkedHashMap<>();
        for (var t : trades) {
            String pair = String.valueOf(t.getOrDefault("pair", "UNKNOWN"));
            byPair.computeIfAbsent(pair, k -> new ArrayList<>()).add(t);
        }
        Map<String, Object> pairStats = new LinkedHashMap<>();
        byPair.forEach((pair, pts) -> pairStats.put(pair, computePeriodStats(pts)));
        stats.put("byPair", pairStats);

        // Breakdown by timeframe
        Map<String, List<Map<String, Object>>> byTf = new LinkedHashMap<>();
        for (var t : trades) {
            String tf = String.valueOf(t.getOrDefault("timeframe", "UNKNOWN"));
            byTf.computeIfAbsent(tf, k -> new ArrayList<>()).add(t);
        }
        Map<String, Object> tfStats = new LinkedHashMap<>();
        byTf.forEach((tf, tts) -> tfStats.put(tf, computePeriodStats(tts)));
        stats.put("byTimeframe", tfStats);

        // Breakdown by direction
        Map<String, List<Map<String, Object>>> byDir = new LinkedHashMap<>();
        for (var t : trades) {
            String dir = String.valueOf(t.getOrDefault("direction", "UNKNOWN"));
            byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(t);
        }
        Map<String, Object> dirStats = new LinkedHashMap<>();
        byDir.forEach((dir, dts) -> dirStats.put(dir, computePeriodStats(dts)));
        stats.put("byDirection", dirStats);

        // Breakdown by close reason
        Map<String, List<Map<String, Object>>> byReason = new LinkedHashMap<>();
        for (var t : trades) {
            String reason = String.valueOf(t.getOrDefault("closeReason", "UNKNOWN"));
            byReason.computeIfAbsent(reason, k -> new ArrayList<>()).add(t);
        }
        Map<String, Object> reasonStats = new LinkedHashMap<>();
        byReason.forEach((reason, rts) -> reasonStats.put(reason, computePeriodStats(rts)));
        stats.put("byCloseReason", reasonStats);

        return ResponseEntity.ok(stats);
    }

    private Map<String, Object> computePeriodStats(List<Map<String, Object>> trades) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("totalTrades", trades.size());
        if (trades.isEmpty()) {
            s.put("wins", 0);
            s.put("losses", 0);
            s.put("winRate", 0.0);
            s.put("totalPnlUsd", 0.0);
            s.put("avgPnlUsd", 0.0);
            s.put("bestTrade", 0.0);
            s.put("worstTrade", 0.0);
            s.put("profitFactor", 0.0);
            return s;
        }
        int wins = 0, losses = 0;
        double totalPnl = 0, grossProfit = 0, grossLoss = 0;
        double best = Double.MIN_VALUE, worst = Double.MAX_VALUE;
        for (var t : trades) {
            double pnl = t.get("pnlUsd") instanceof Number n ? n.doubleValue() : 0;
            totalPnl += pnl;
            if (pnl > 0) {
                wins++;
                grossProfit += pnl;
            } else {
                losses++;
                grossLoss += Math.abs(pnl);
            }
            double pnlPct = t.get("pnlPercent") instanceof Number n ? n.doubleValue() : 0;
            if (pnlPct > best)
                best = pnlPct;
            if (pnlPct < worst)
                worst = pnlPct;
        }
        s.put("wins", wins);
        s.put("losses", losses);
        s.put("winRate", Math.round((double) wins / trades.size() * 1000.0) / 10.0);
        s.put("totalPnlUsd", Math.round(totalPnl * 100.0) / 100.0);
        s.put("avgPnlUsd", Math.round(totalPnl / trades.size() * 100.0) / 100.0);
        s.put("bestTrade", Math.round(best * 100.0) / 100.0);
        s.put("worstTrade", Math.round(worst * 100.0) / 100.0);
        s.put("profitFactor", grossLoss > 0 ? Math.round(grossProfit / grossLoss * 100.0) / 100.0 : 0.0);
        return s;
    }

    // ==================== Application Logs ====================

    private static final int MAX_LOG_LINES = 500;
    private static final long MAX_READ_BYTES = 256 * 1024;

    @GetMapping("/logs")
    public ResponseEntity<List<String>> logs(
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(required = false) String filter) {
        int maxLines = Math.min(lines, MAX_LOG_LINES);
        Path logPath = Path.of("logs/tbot-scalp.log");
        if (!Files.exists(logPath)) {
            return ResponseEntity.ok(List.of());
        }
        try {
            List<String> tail = tailFile(logPath, maxLines);
            if (filter != null && !filter.isBlank()) {
                String lowerFilter = filter.toLowerCase();
                tail = tail.stream()
                        .filter(line -> line.toLowerCase().contains(lowerFilter))
                        .toList();
            }
            return ResponseEntity.ok(tail);
        } catch (Exception e) {
            log.error("Failed to read log file: {}", e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private List<String> tailFile(Path path, int maxLines) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long fileLen = raf.length();
            if (fileLen == 0)
                return List.of();
            long start = Math.max(0, fileLen - MAX_READ_BYTES);
            raf.seek(start);
            byte[] buf = new byte[(int) (fileLen - start)];
            raf.readFully(buf);
            String content = new String(buf, StandardCharsets.UTF_8);
            String[] allLines = content.split("\n");
            List<String> result = new ArrayList<>();
            int from = Math.max(start > 0 ? 1 : 0, allLines.length - maxLines);
            for (int i = from; i < allLines.length; i++) {
                if (!allLines[i].isBlank())
                    result.add(allLines[i]);
            }
            return result;
        }
    }

    // ==================== Helpers ====================

    // (balance/equity now handled by OrderManagerService)
}
