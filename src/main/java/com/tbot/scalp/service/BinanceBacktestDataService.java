package com.tbot.scalp.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.Candle;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches candle data from Binance Futures API for backtesting purposes.
 * Binance provides 1m/3m candles with 365+ days of history (vs Hyperliquid's
 * 5000 candle limit = ~3.5 days for 1m).
 * <p>
 * Only used for backtest data. Live trading still uses Hyperliquid.
 * <p>
 * API: GET https://fapi.binance.com/fapi/v1/klines
 * - No authentication required for market data
 * - Max 1500 candles per request (paginated)
 * - Rate limit: 2400 weight/min; klines weight = 1-10 based on limit
 */
@Slf4j
@Service
public class BinanceBacktestDataService {

    private static final String BINANCE_FUTURES_URL = "https://fapi.binance.com/fapi/v1/klines";
    private static final int MAX_CANDLES_PER_REQUEST = 1500;

    // Rate limiting: 2400 weight/min, klines with limit>1000 = 10 weight
    private static final int RATE_BUDGET = 2400;
    private static final long RATE_WINDOW_MS = 60_000L;
    private long rateWindowStart = 0;
    private int rateUsed = 0;

    private final ScalpConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private RestTemplate restTemplate;

    public BinanceBacktestDataService(ScalpConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Fetch candles from Binance Futures for backtesting.
     * Same signature as HyperliquidMarketDataService.fetchCandles().
     */
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

            String symbol = toBinanceSymbol(pair);
            Set<Long> seen = new HashSet<>();
            long cursor = startMs;

            while (cursor < endMs) {
                acquireRate(10); // weight 10 for limit > 1000
                String url = String.format(
                        "%s?symbol=%s&interval=%s&startTime=%d&endTime=%d&limit=%d",
                        BINANCE_FUTURES_URL, symbol, interval, cursor, endMs, MAX_CANDLES_PER_REQUEST);

                String response = restTemplate.getForObject(url, String.class);
                JsonNode arr = objectMapper.readTree(response);
                if (!arr.isArray() || arr.isEmpty()) {
                    break;
                }

                long lastTimestamp = 0;
                for (JsonNode c : arr) {
                    long t = c.get(0).asLong(); // Open time
                    lastTimestamp = t;
                    if (seen.add(t)) {
                        allCandles.add(Candle.builder()
                                .timestamp(t)
                                .open(c.get(1).asDouble())
                                .high(c.get(2).asDouble())
                                .low(c.get(3).asDouble())
                                .close(c.get(4).asDouble())
                                .volume(c.get(5).asDouble())
                                .numTrades(c.get(8).asInt())
                                .build());
                    }
                }

                // Move cursor past the last candle we received
                cursor = lastTimestamp + intervalMs;

                // If we got fewer than max, we've reached the end
                if (arr.size() < MAX_CANDLES_PER_REQUEST) {
                    break;
                }
            }

            allCandles.sort(Comparator.comparingLong(Candle::getTimestamp));
            log.debug("Binance: fetched {} candles for {} {} ({}d ago, {}d window)",
                    allCandles.size(), pair, interval, daysAgo, windowDays);
        } catch (Exception e) {
            log.error("Failed to fetch Binance candles for {} {}: {}", pair, interval, e.getMessage());
        }
        return allCandles;
    }

    /**
     * Convert generic coin name to Binance Futures symbol.
     * BTC → BTCUSDT, ETH → ETHUSDT, etc.
     */
    public static String toBinanceSymbol(String coin) {
        // Strip any prefix (e.g. xyz:AAPL)
        String clean = coin.contains(":") ? coin.substring(coin.indexOf(':') + 1) : coin;
        return clean.toUpperCase() + "USDT";
    }

    private synchronized void acquireRate(int weight) {
        long now = System.currentTimeMillis();
        if (now - rateWindowStart >= RATE_WINDOW_MS) {
            rateWindowStart = now;
            rateUsed = 0;
        }
        if (rateUsed + weight > RATE_BUDGET) {
            long waitMs = RATE_WINDOW_MS - (now - rateWindowStart) + 100;
            if (waitMs > 0) {
                log.debug("Binance rate limit: waiting {}ms (used {}/{})", waitMs, rateUsed, RATE_BUDGET);
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            rateWindowStart = System.currentTimeMillis();
            rateUsed = 0;
        }
        rateUsed += weight;
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
}
