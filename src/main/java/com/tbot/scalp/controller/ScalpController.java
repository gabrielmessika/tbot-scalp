package com.tbot.scalp.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.tbot.scalp.service.*;

import lombok.RequiredArgsConstructor;
import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.config.StartupRunner;
import com.tbot.scalp.model.AnalysisResult;
import com.tbot.scalp.service.AnalysisService;
import com.tbot.scalp.service.HyperliquidWebSocketService;
import com.tbot.scalp.service.PositionManagerService;
import com.tbot.scalp.service.RiskManagementService;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ScalpController {

    private final AnalysisService analysisService;
    private final PositionManagerService positionManager;
    private final RiskManagementService riskService;
    private final HyperliquidWebSocketService wsService;
    private final ScalpConfig config;
    private final StartupRunner startupRunner;

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

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("openPositions", positionManager.getOpenPositions());
        state.put("cooldowns", positionManager.getActiveCooldowns());
        state.put("wsConnected", wsService.isConnected());

        double balance = config.getDryRunBalance();
        RiskManagementService.RiskStatus risk = riskService.checkPortfolioRisk(balance, balance);
        state.put("risk", risk);

        state.put("autoTrade", config.isAutoTrade());
        state.put("liveTrading", config.isLiveTrading());
        state.put("exchange", config.getExchange());
        state.put("startupComplete", startupRunner.isStartupComplete());
        state.put("startupPhase", startupRunner.getStartupPhase());
        state.put("backtestRunning", analysisService.isBacktestRunning());
        return ResponseEntity.ok(state);
    }

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
        return ResponseEntity.ok(cfg);
    }

    @GetMapping("/cooldowns")
    public ResponseEntity<Map<String, Long>> cooldowns() {
        return ResponseEntity.ok(positionManager.getActiveCooldowns());
    }

    @GetMapping("/positions")
    public ResponseEntity<?> positions() {
        return ResponseEntity.ok(positionManager.getOpenPositions());
    }
}
