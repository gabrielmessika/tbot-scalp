package com.tbot.scalp.config;

import java.nio.file.Files;
import java.nio.file.Path;

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
                // Phase 1: Check data directories (with R/W permission validation)
                startupPhase = "Checking data directories";
                checkDataDirectories();

                // Phase 2: API health check
                if (config.isStartupChecks()) {
                    startupPhase = "Checking API health";
                    if (!checkApiHealth()) {
                        startupPhase = "\u274c API health check failed";
                        log.error("Startup aborted — API health check failed. Schedulers will still run for retry.");
                        startupComplete = true;
                        return;
                    }
                }

                // Phase 3: Load exchange meta
                startupPhase = "Loading exchange metadata";
                marketDataService.refreshMeta();

                // Phase 4: Connect WebSocket
                if (config.isAutoTrade()) {
                    startupPhase = "Connecting WebSocket";
                    webSocketService.connect();
                }

                // Phase 5: Position recovery (BEFORE analysis — t-bot pattern)
                if (config.isLiveTrading()) {
                    startupPhase = "Recovering positions from exchange";
                    log.info("Recovering positions from exchange...");
                    positionManager.recoverPositions();
                }

                // Phase 6: Run backtest if configured (skip in live mode)
                if (config.isStartupBacktest() && !config.isLiveTrading()) {
                    startupPhase = "Running startup backtest";
                    log.info("Starting backtest...");
                    analysisService.runBacktest();
                }

                // Phase 7: Run initial analysis
                startupPhase = "Running initial analysis";
                analysisService.runCurrentAnalysis();

                startupPhase = "Complete";
                if (config.isLiveTrading()) {
                    log.info("Startup complete — tbot-scalp LIVE MODE ready (exchange={})", config.getExchange());
                } else {
                    log.info("Startup complete — tbot-scalp ready (dry-run)");
                }
            } catch (Exception e) {
                startupPhase = "Error: " + e.getMessage();
                log.error("Startup failed", e);
            } finally {
                // Always allow schedulers to run — even after failure (t-bot pattern)
                startupComplete = true;
            }
        }, "startup-scalp").start();
    }

    private void checkDataDirectories() {
        for (String dir : new String[] { "journal", "history", "logs", "backtests", "screener" }) {
            Path p = Path.of("./" + dir);
            try {
                if (!Files.exists(p)) {
                    Files.createDirectories(p);
                    log.info("Created directory: {}", p.toAbsolutePath());
                }
                if (!Files.isReadable(p) || !Files.isWritable(p)) {
                    log.error("Directory {} is not readable/writable — check permissions", p.toAbsolutePath());
                    throw new RuntimeException("Directory permission error: " + p.toAbsolutePath());
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to create directory: " + p.toAbsolutePath(), e);
            }
        }
    }

    /**
     * Quick API health check — verifies Hyperliquid /info responds.
     */
    private boolean checkApiHealth() {
        try {
            log.info("Checking Hyperliquid API health...");
            double price = marketDataService.fetchCurrentPrice("BTC");
            if (price > 0) {
                log.info("\u2705 API health OK (BTC price: ${} )", String.format("%.0f", price));
                return true;
            }
            log.error("\u274c API returned invalid BTC price: {}", price);
            return false;
        } catch (Exception e) {
            log.error("\u274c API health check failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean isStartupComplete() {
        return startupComplete;
    }

    public String getStartupPhase() {
        return startupPhase;
    }
}
