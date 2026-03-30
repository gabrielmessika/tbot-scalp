package com.tbot.scalp.service;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;

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
}
