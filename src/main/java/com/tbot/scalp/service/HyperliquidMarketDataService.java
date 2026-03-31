package com.tbot.scalp.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.Candle;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class HyperliquidMarketDataService {

    private final ScalpConfig config;
    private final HyperliquidRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;

    private final Map<String, Integer> coinToIndex = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> assetToSzDecimals = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> assetToMaxLeverage = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void refreshMeta() {
        try {
            rateLimiter.acquireInfoHeavy();
            String body = "{\"type\":\"meta\"}";
            String response = postInfo(body);
            JsonNode root = objectMapper.readTree(response);
            JsonNode universe = root.get("universe");
            if (universe != null && universe.isArray()) {
                for (int i = 0; i < universe.size(); i++) {
                    JsonNode asset = universe.get(i);
                    String name = asset.get("name").asText();
                    coinToIndex.put(name, i);
                    assetToSzDecimals.put(i, asset.has("szDecimals") ? asset.get("szDecimals").asInt() : 4);
                    assetToMaxLeverage.put(i, asset.has("maxLeverage") ? asset.get("maxLeverage").asInt() : 10);
                }
            }
            log.info("Loaded {} perp assets from Hyperliquid meta", coinToIndex.size());
        } catch (Exception e) {
            log.error("Failed to load Hyperliquid meta: {}", e.getMessage());
        }
    }

    public List<Candle> fetchCandles(String pair, String interval, int daysAgo, int windowDays) {
        List<Candle> allCandles = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            long endMs = now - (long) daysAgo * 86_400_000L;
            long startMs = windowDays > 0 ? endMs - (long) windowDays * 86_400_000L
                    : endMs - (long) config.getBacktestDays() * 86_400_000L;

            // Warmup: add 50 candles
            long intervalMs = intervalToMs(interval);
            startMs -= 50 * intervalMs;

            // Paginate (500 candles/request max)
            long chunkMs = 500L * intervalMs;
            long cursor = startMs;
            Set<Long> seen = new HashSet<>();

            while (cursor < endMs) {
                long chunkEnd = Math.min(cursor + chunkMs, endMs);
                long estimatedCandles = Math.min(500L, (chunkEnd - cursor) / intervalMs);
                rateLimiter.acquireCandle((int) estimatedCandles);
                String body = String.format(
                        "{\"type\":\"candleSnapshot\",\"req\":{\"coin\":\"%s\",\"interval\":\"%s\",\"startTime\":%d,\"endTime\":%d}}",
                        toHyperliquidCoin(pair), interval, cursor, chunkEnd);

                String response = postInfo(body);
                JsonNode arr = objectMapper.readTree(response);
                if (arr.isArray()) {
                    for (JsonNode c : arr) {
                        long t = c.get("t").asLong();
                        if (seen.add(t)) {
                            allCandles.add(Candle.builder()
                                    .timestamp(t)
                                    .open(c.get("o").asDouble())
                                    .high(c.get("h").asDouble())
                                    .low(c.get("l").asDouble())
                                    .close(c.get("c").asDouble())
                                    .volume(c.get("v").asDouble())
                                    .numTrades(c.has("n") ? c.get("n").asInt() : 0)
                                    .build());
                        }
                    }
                }
                cursor = chunkEnd;
            }

            allCandles.sort(Comparator.comparingLong(Candle::getTimestamp));
        } catch (Exception e) {
            log.error("Failed to fetch candles for {} {}: {}", pair, interval, e.getMessage());
        }
        return allCandles;
    }

    public double fetchCurrentPrice(String pair) {
        try {
            rateLimiter.acquireInfo();
            String body = "{\"type\":\"allMids\"}";
            String response = postInfo(body);
            JsonNode mids = objectMapper.readTree(response);
            String coin = toHyperliquidCoin(pair);
            if (mids.has(coin)) {
                return mids.get(coin).asDouble();
            }
        } catch (Exception e) {
            log.error("Failed to fetch price for {}: {}", pair, e.getMessage());
        }
        return 0;
    }

    public int getAssetIndex(String pair) {
        if (coinToIndex.isEmpty())
            refreshMeta();
        return coinToIndex.getOrDefault(toHyperliquidCoin(pair), -1);
    }

    public int getSzDecimals(String pair) {
        int idx = getAssetIndex(pair);
        return assetToSzDecimals.getOrDefault(idx, 4);
    }

    // Fallback max leverage per coin (Hyperliquid, as of March 2026)
    private static final Map<String, Integer> KNOWN_MAX_LEVERAGE = Map.ofEntries(
            Map.entry("BTC", 40), Map.entry("ETH", 25), Map.entry("SOL", 20),
            Map.entry("XRP", 20), Map.entry("DOGE", 10), Map.entry("AVAX", 10),
            Map.entry("LINK", 10), Map.entry("ADA", 10), Map.entry("SUI", 10),
            Map.entry("HYPE", 10), Map.entry("INJ", 10), Map.entry("SEI", 10),
            Map.entry("TIA", 10), Map.entry("JUP", 10), Map.entry("OP", 10),
            Map.entry("ARB", 10), Map.entry("APT", 10), Map.entry("AAVE", 10),
            Map.entry("MKR", 10), Map.entry("WLD", 10), Map.entry("NEAR", 10),
            Map.entry("ONDO", 10), Map.entry("ATOM", 5), Map.entry("STX", 5),
            Map.entry("PENDLE", 5), Map.entry("FET", 5), Map.entry("FIL", 5),
            Map.entry("RENDER", 5), Map.entry("WIF", 5));

    public int getMaxLeverage(String pair) {
        int idx = getAssetIndex(pair);
        if (idx >= 0) {
            return assetToMaxLeverage.getOrDefault(idx, KNOWN_MAX_LEVERAGE.getOrDefault(pair, 10));
        }
        return KNOWN_MAX_LEVERAGE.getOrDefault(pair, 10);
    }

    public String getExchangeName() {
        return "Hyperliquid";
    }

    private String postInfo(String body) {
        return restTemplate.postForObject(
                config.getHyperliquidApiUrl() + "/info",
                new org.springframework.http.HttpEntity<>(body, jsonHeaders()),
                String.class);
    }

    private org.springframework.http.HttpHeaders jsonHeaders() {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("Content-Type", "application/json");
        return headers;
    }

    public static String toHyperliquidCoin(String pair) {
        return pair; // BTC → BTC, xyz:AAPL → xyz:AAPL
    }

    private long intervalToMs(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "3m" -> 180_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "1h" -> 3_600_000L;
            case "2h" -> 7_200_000L;
            case "4h" -> 14_400_000L;
            default -> 300_000L;
        };
    }

    /**
     * Fetch a fixed number of recent candles (more efficient than day-based for
     * live analysis).
     * Warmup of 50 candles is added automatically.
     */
    public List<Candle> fetchRecentCandles(String pair, String interval, int candleCount) {
        long intervalMs = intervalToMs(interval);
        long windowMs = (long) candleCount * intervalMs;
        long warmupMs = 50L * intervalMs;
        long now = System.currentTimeMillis();
        long endMs = now;
        long startMs = now - windowMs - warmupMs;

        List<Candle> allCandles = new ArrayList<>();
        try {
            long chunkMs = 500L * intervalMs;
            long cursor = startMs;
            Set<Long> seen = new HashSet<>();

            while (cursor < endMs) {
                long chunkEnd = Math.min(cursor + chunkMs, endMs);
                long estimatedCandles = Math.min(500L, (chunkEnd - cursor) / intervalMs);
                rateLimiter.acquireCandle((int) estimatedCandles);
                String body = String.format(
                        "{\"type\":\"candleSnapshot\",\"req\":{\"coin\":\"%s\",\"interval\":\"%s\",\"startTime\":%d,\"endTime\":%d}}",
                        toHyperliquidCoin(pair), interval, cursor, chunkEnd);

                String response = postInfo(body);
                JsonNode arr = objectMapper.readTree(response);
                if (arr.isArray()) {
                    for (JsonNode c : arr) {
                        long t = c.get("t").asLong();
                        if (seen.add(t)) {
                            allCandles.add(Candle.builder()
                                    .timestamp(t)
                                    .open(c.get("o").asDouble())
                                    .high(c.get("h").asDouble())
                                    .low(c.get("l").asDouble())
                                    .close(c.get("c").asDouble())
                                    .volume(c.get("v").asDouble())
                                    .numTrades(c.has("n") ? c.get("n").asInt() : 0)
                                    .build());
                        }
                    }
                }
                cursor = chunkEnd;
            }
            allCandles.sort(Comparator.comparingLong(Candle::getTimestamp));
        } catch (Exception e) {
            log.error("Failed to fetch recent candles for {} {}: {}", pair, interval, e.getMessage());
        }
        return allCandles;
    }
}
