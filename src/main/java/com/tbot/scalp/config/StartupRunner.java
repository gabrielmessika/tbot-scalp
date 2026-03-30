package com.tbot.scalp.config;

import java.io.File;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.tbot.scalp.service.AnalysisService;
import com.tbot.scalp.service.HyperliquidMarketDataService;
import com.tbot.scalp.service.HyperliquidWebSocketService;
import com.tbot.scalp.service.PositionManagerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupRunner {

    private final ScalpConfig config;
    private final AnalysisService analysisService;
    private final HyperliquidMarketDataService marketDataService;
    private final HyperliquidWebSocketService webSocketService;
    private final PositionManagerService positionManager;

    private volatile boolean startupComplete = false;
    private volatile String startupPhase = "Initializing";

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        new Thread(() -> {
            try {
                // Phase 1: Check data directories
                startupPhase = "Checking data directories";
                checkDataDirectories();

                // Phase 2: Load exchange meta
                startupPhase = "Loading exchange metadata";
                marketDataService.refreshMeta();

                // Phase 3: Connect WebSocket
                if (config.isAutoTrade()) {
                    startupPhase = "Connecting WebSocket";
                    webSocketService.connect();
                }

                // Phase 4: Position recovery (BEFORE analysis — t-bot pattern)
                if (config.isLiveTrading()) {
                    startupPhase = "Recovering positions from exchange";
                    log.info("Recovering positions from exchange...");
                    positionManager.recoverPositions();
                }

                // Phase 5: Run backtest if configured (skip in live mode)
                if (config.isStartupBacktest() && !config.isLiveTrading()) {
                    startupPhase = "Running startup backtest";
                    log.info("Starting backtest...");
                    analysisService.runBacktest();
                }

                // Phase 6: Run initial analysis
                startupPhase = "Running initial analysis";
                analysisService.runCurrentAnalysis();

                startupPhase = "Complete";
                startupComplete = true;

                if (config.isLiveTrading()) {
                    log.info("Startup complete — tbot-scalp LIVE MODE ready (exchange={})", config.getExchange());
                } else {
                    log.info("Startup complete — tbot-scalp ready (dry-run)");
                }
            } catch (Exception e) {
                startupPhase = "Error: " + e.getMessage();
                log.error("Startup failed", e);
            }
        }, "startup-scalp").start();
    }

    private void checkDataDirectories() {
        for (String dir : new String[] { "journal", "history", "logs", "backtests", "screener" }) {
            File f = new File("./" + dir);
            if (!f.exists()) {
                f.mkdirs();
                log.info("Created directory: {}", f.getAbsolutePath());
            }
        }
    }

    public boolean isStartupComplete() {
        return startupComplete;
    }

    public String getStartupPhase() {
        return startupPhase;
    }
}
