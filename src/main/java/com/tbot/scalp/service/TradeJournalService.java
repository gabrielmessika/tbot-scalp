package com.tbot.scalp.service;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbot.scalp.config.ScalpConfig;
import com.tbot.scalp.model.TradeExecution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeJournalService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScalpConfig config;
    private File currentFile;

    public void record(TradeExecution exec, String status, double score, String timeframe) {
        try {
            if (currentFile == null) {
                File dir = new File("./journal");
                if (!dir.exists())
                    dir.mkdirs();
                currentFile = new File(dir, "trade-journal_" + LocalDateTime.now().format(FMT) + ".jsonl");
            }

            var entry = new java.util.LinkedHashMap<String, Object>();
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("pair", exec.getPair());
            entry.put("direction", exec.getDirection());
            entry.put("timeframe", timeframe);
            entry.put("entryPrice", exec.getFillPrice());
            entry.put("stopLoss", exec.getStopLoss());
            entry.put("takeProfit", exec.getTakeProfit());
            entry.put("leverage", exec.getLeverage());
            entry.put("quantity", exec.getQuantity());
            entry.put("score", score);
            entry.put("status", status);
            entry.put("exchange", exec.getExchange());
            entry.put("dryRun", exec.isDryRun());
            entry.put("clientOrderId", exec.getClientOrderId());
            entry.put("exchangeOrderId", exec.getExchangeOrderId());
            if (exec.getErrorMessage() != null)
                entry.put("errorMessage", exec.getErrorMessage());

            try (var writer = new FileWriter(currentFile, true)) {
                writer.write(mapper.writeValueAsString(entry) + "\n");
            }
        } catch (Exception e) {
            log.error("Failed to record trade journal: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readAllParsed() {
        List<Map<String, Object>> all = new ArrayList<>();
        File dir = new File("./journal");
        if (!dir.exists())
            return all;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jsonl"));
        if (files == null)
            return all;

        // Compute cutoff timestamp from liveStartDate config
        long cutoffMs = 0;
        String startDate = config.getLiveStartDate();
        if (startDate != null && !startDate.isBlank()) {
            try {
                cutoffMs = java.time.LocalDate.parse(startDate)
                        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e) {
                log.warn("Invalid liveStartDate '{}': {}", startDate, e.getMessage());
            }
        }

        // Sort by name desc (most recent file first)
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (File f : files) {
            try {
                List<String> lines = Files.readAllLines(f.toPath());
                for (String line : lines) {
                    if (line.isBlank())
                        continue;
                    Map<String, Object> entry = mapper.readValue(line, Map.class);
                    // Filter out entries before liveStartDate
                    if (cutoffMs > 0) {
                        long ts = entry.get("timestamp") instanceof Number
                                ? ((Number) entry.get("timestamp")).longValue()
                                : 0;
                        if (ts > 0 && ts < cutoffMs)
                            continue;
                    }
                    all.add(entry);
                }
            } catch (Exception e) {
                log.warn("Failed to parse journal file {}: {}", f.getName(), e.getMessage());
            }
        }
        Collections.reverse(all);
        return all;
    }
}
