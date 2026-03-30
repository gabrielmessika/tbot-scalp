package com.tbot.scalp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbot.scalp.config.ScalpConfig;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * WebSocket client for Hyperliquid real-time data feeds.
 * Subscribes to: trades, l2Book, candle, allMids for configured coins.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HyperliquidWebSocketService {

    private final ScalpConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebSocketClient wsClient;
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean running = false;

    // Real-time data stores
    private final Map<String, Double> latestMids = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> recentTrades = new ConcurrentHashMap<>();

    // Callbacks for live trading
    private Consumer<JsonNode> onTradeCallback;
    private Consumer<JsonNode> onCandleCallback;
    private Consumer<Map<String, Double>> onMidsCallback;

    public void connect() {
        if (running)
            return;
        running = true;

        try {
            wsClient = new WebSocketClient(new URI(config.getHyperliquidWsUrl())) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    log.info("WebSocket connected to Hyperliquid");
                    subscribeToFeeds();
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    log.warn("WebSocket closed: code={}, reason={}, remote={}", code, reason, remote);
                    if (running) {
                        reconnectScheduler.schedule(() -> reconnect(), 5, TimeUnit.SECONDS);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    log.error("WebSocket error: {}", ex.getMessage());
                }
            };
            wsClient.connect();
        } catch (Exception e) {
            log.error("Failed to connect WebSocket: {}", e.getMessage());
        }
    }

    private void reconnect() {
        log.info("Attempting WebSocket reconnect...");
        try {
            if (wsClient != null)
                wsClient.close();
            connect();
        } catch (Exception e) {
            log.error("Reconnect failed: {}", e.getMessage());
            if (running) {
                reconnectScheduler.schedule(() -> reconnect(), 10, TimeUnit.SECONDS);
            }
        }
    }

    private void subscribeToFeeds() {
        try {
            // Subscribe to allMids
            wsClient.send("{\"method\":\"subscribe\",\"subscription\":{\"type\":\"allMids\"}}");

            // Subscribe to trades + candles for each coin
            for (String coin : config.getCoins()) {
                String hlCoin = HyperliquidMarketDataService.toHyperliquidCoin(coin);
                wsClient.send(String.format(
                        "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"trades\",\"coin\":\"%s\"}}", hlCoin));

                // Subscribe to 1m candles for real-time updates
                wsClient.send(String.format(
                        "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"candle\",\"coin\":\"%s\",\"interval\":\"1m\"}}",
                        hlCoin));
            }
            log.info("Subscribed to {} coin feeds", config.getCoins().size());
        } catch (Exception e) {
            log.error("Failed to subscribe to feeds: {}", e.getMessage());
        }
    }

    private void handleMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String channel = json.has("channel") ? json.get("channel").asText() : "";
            JsonNode data = json.get("data");

            switch (channel) {
                case "allMids" -> {
                    if (data != null && data.has("mids")) {
                        JsonNode mids = data.get("mids");
                        mids.fieldNames().forEachRemaining(coin -> latestMids.put(coin, mids.get(coin).asDouble()));
                        if (onMidsCallback != null)
                            onMidsCallback.accept(Map.copyOf(latestMids));
                    }
                }
                case "trades" -> {
                    if (data != null && data.isArray()) {
                        for (JsonNode trade : data) {
                            String coin = trade.get("coin").asText();
                            recentTrades.computeIfAbsent(coin, k -> new CopyOnWriteArrayList<>()).add(trade);
                            // Keep only last 100 trades per coin
                            List<JsonNode> trades = recentTrades.get(coin);
                            while (trades.size() > 100)
                                trades.remove(0);
                        }
                        if (onTradeCallback != null)
                            onTradeCallback.accept(data);
                    }
                }
                case "candle" -> {
                    if (onCandleCallback != null && data != null)
                        onCandleCallback.accept(data);
                }
                case "subscriptionResponse" -> {
                    // ACK, ignore
                }
                default -> {
                    // Unknown channel
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing WS message: {}", e.getMessage());
        }
    }

    public double getLatestPrice(String coin) {
        return latestMids.getOrDefault(HyperliquidMarketDataService.toHyperliquidCoin(coin), 0.0);
    }

    public Map<String, Double> getAllMids() {
        return Map.copyOf(latestMids);
    }

    public List<JsonNode> getRecentTrades(String coin) {
        return recentTrades.getOrDefault(HyperliquidMarketDataService.toHyperliquidCoin(coin), List.of());
    }

    public void setOnTradeCallback(Consumer<JsonNode> callback) {
        this.onTradeCallback = callback;
    }

    public void setOnCandleCallback(Consumer<JsonNode> callback) {
        this.onCandleCallback = callback;
    }

    public void setOnMidsCallback(Consumer<Map<String, Double>> callback) {
        this.onMidsCallback = callback;
    }

    public boolean isConnected() {
        return wsClient != null && wsClient.isOpen();
    }

    @PreDestroy
    public void disconnect() {
        running = false;
        reconnectScheduler.shutdownNow();
        if (wsClient != null)
            wsClient.close();
        log.info("WebSocket disconnected");
    }
}
