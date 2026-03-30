package com.tbot.scalp.config;

import java.io.File;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.tbot.scalp.service.AnalysisService;
import com.tbot.scalp.service.HyperliquidMarketDataService;
import com.tbot.scalp.service.HyperliquidWebSocketService;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupRunner {

    private final ScalpConfig config;
    private final AnalysisService analysisService;
    private final HyperliquidMarketDataService marketDataService;
    private final HyperliquidWebSocketService webSocketService;

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

                // Phase 4: Run backtest if configured
                if (config.isStartupBacktest() && !config.isLiveTrading()) {
                    startupPhase = "Running startup backtest";
                    log.info("Starting backtest...");
                    analysisService.runBacktest();
                }

                // Phase 5: Run initial analysis
                startupPhase = "Running initial analysis";
                analysisService.runCurrentAnalysis();

                startupPhase = "Complete";
                startupComplete = true;
                log.info("Startup complete — tbot-scalp ready");
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
