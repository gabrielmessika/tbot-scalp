package com.tbot.scalp.service;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import com.tbot.scalp.model.TradeExecution;

@Slf4j
@Service
public class TradeJournalService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final ObjectMapper mapper = new ObjectMapper();
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
}
