package com.tbot.scalp.service;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import com.tbot.scalp.model.OpenPosition;

@Slf4j
@Service
public class TradeHistoryService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final ObjectMapper mapper = new ObjectMapper();
    private File currentFile;

    public void recordClose(OpenPosition pos, double exitPrice, String closeReason, int candlesElapsed) {
        try {
            if (currentFile == null) {
                File dir = new File("./history");
                if (!dir.exists())
                    dir.mkdirs();
                currentFile = new File(dir, "trade-history_" + LocalDateTime.now().format(FMT) + ".jsonl");
            }

            double pnlPercent;
            if ("LONG".equals(pos.getDirection())) {
                pnlPercent = (exitPrice - pos.getEntryPrice()) / pos.getEntryPrice() * 100 * pos.getLeverage();
            } else {
                pnlPercent = (pos.getEntryPrice() - exitPrice) / pos.getEntryPrice() * 100 * pos.getLeverage();
            }
            double pnlUsd = pos.getQuantity() * pos.getEntryPrice() * pnlPercent / 100.0 / pos.getLeverage();

            var entry = new LinkedHashMap<String, Object>();
            entry.put("openDate", pos.getOpenTimestamp());
            entry.put("closeDate", System.currentTimeMillis());
            entry.put("pair", pos.getPair());
            entry.put("timeframe", pos.getTimeframe());
            entry.put("direction", pos.getDirection());
            entry.put("entryPrice", pos.getEntryPrice());
            entry.put("exitPrice", exitPrice);
            entry.put("stopLoss", pos.getOriginalStopLoss());
            entry.put("takeProfit", pos.getTakeProfit());
            entry.put("leverage", pos.getLeverage());
            entry.put("quantity", pos.getQuantity());
            entry.put("score", pos.getScore());
            entry.put("closeReason", closeReason);
            entry.put("pnlPercent", Math.round(pnlPercent * 100.0) / 100.0);
            entry.put("pnlUsd", Math.round(pnlUsd * 100.0) / 100.0);
            entry.put("candlesElapsed", candlesElapsed);
            entry.put("breakEvenApplied", pos.isBreakEvenApplied());
            entry.put("dryRun", pos.isDryRun());
            entry.put("exchange", pos.getExchange());

            try (var writer = new FileWriter(currentFile, true)) {
                writer.write(mapper.writeValueAsString(entry) + "\n");
            }
        } catch (Exception e) {
            log.error("Failed to record trade history: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readAllParsed() {
        List<Map<String, Object>> all = new ArrayList<>();
        File dir = new File("./history");
        if (!dir.exists())
            return all;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jsonl"));
        if (files == null)
            return all;
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (File f : files) {
            try {
                List<String> lines = Files.readAllLines(f.toPath());
                for (String line : lines) {
                    if (line.isBlank())
                        continue;
                    Map<String, Object> entry = mapper.readValue(line, Map.class);
                    // Format dates for display
                    if (entry.get("openDate") instanceof Number) {
                        long ts = ((Number) entry.get("openDate")).longValue();
                        entry.put("openDate", LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
                                .format(dtFmt));
                    }
                    if (entry.get("closeDate") instanceof Number) {
                        long ts = ((Number) entry.get("closeDate")).longValue();
                        entry.put("closeDate", LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())
                                .format(dtFmt));
                    }
                    all.add(entry);
                }
            } catch (Exception e) {
                log.warn("Failed to parse history file {}: {}", f.getName(), e.getMessage());
            }
        }
        Collections.reverse(all);
        return all;
    }

    public Map<String, Object> getSummary() {
        List<Map<String, Object>> trades = readAllParsed();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTrades", trades.size());
        if (trades.isEmpty()) {
            summary.put("wins", 0);
            summary.put("losses", 0);
            summary.put("winRate", 0.0);
            summary.put("totalPnlUsd", 0.0);
            summary.put("totalPnlPercent", 0.0);
            summary.put("avgPnlPercent", 0.0);
            summary.put("bestTradePnl", 0.0);
            summary.put("worstTradePnl", 0.0);
            return summary;
        }
        int wins = 0, losses = 0;
        double totalPnlUsd = 0, totalPnlPct = 0, best = Double.MIN_VALUE, worst = Double.MAX_VALUE;
        Map<String, Integer> closeReasons = new LinkedHashMap<>();
        for (Map<String, Object> t : trades) {
            double pnlUsd = t.get("pnlUsd") instanceof Number ? ((Number) t.get("pnlUsd")).doubleValue() : 0;
            double pnlPct = t.get("pnlPercent") instanceof Number ? ((Number) t.get("pnlPercent")).doubleValue() : 0;
            totalPnlUsd += pnlUsd;
            totalPnlPct += pnlPct;
            if (pnlUsd > 0)
                wins++;
            else
                losses++;
            if (pnlPct > best)
                best = pnlPct;
            if (pnlPct < worst)
                worst = pnlPct;
            String reason = String.valueOf(t.getOrDefault("closeReason", "UNKNOWN"));
            closeReasons.merge(reason, 1, Integer::sum);
        }
        double wr = (wins + losses) > 0 ? (double) wins / (wins + losses) * 100 : 0;
        summary.put("wins", wins);
        summary.put("losses", losses);
        summary.put("winRate", Math.round(wr * 10.0) / 10.0);
        summary.put("totalPnlUsd", Math.round(totalPnlUsd * 100.0) / 100.0);
        summary.put("totalPnlPercent", Math.round(totalPnlPct * 100.0) / 100.0);
        summary.put("avgPnlPercent", Math.round(totalPnlPct / trades.size() * 100.0) / 100.0);
        summary.put("bestTradePnl", Math.round(best * 100.0) / 100.0);
        summary.put("worstTradePnl", Math.round(worst * 100.0) / 100.0);
        summary.put("closeReasons", closeReasons);
        return summary;
    }

    public double getRealizedPnlUsd() {
        List<Map<String, Object>> trades = readAllParsed();
        return trades.stream()
                .mapToDouble(t -> t.get("pnlUsd") instanceof Number ? ((Number) t.get("pnlUsd")).doubleValue() : 0)
                .sum();
    }
}
