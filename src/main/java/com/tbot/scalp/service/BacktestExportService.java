package com.tbot.scalp.service;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.extern.slf4j.Slf4j;
import com.tbot.scalp.model.BacktestSummary;

@Slf4j
@Service
public class BacktestExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final ObjectMapper mapper;

    public BacktestExportService() {
        mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Path export(BacktestSummary summary) {
        try {
            File dir = new File("./backtests");
            if (!dir.exists())
                dir.mkdirs();

            String filename = "backtest_" + LocalDateTime.now().format(FMT) + ".json";
            File file = new File(dir, filename);
            mapper.writeValue(file, summary);
            log.info("Backtest exported to {}", file.getAbsolutePath());
            return file.toPath();
        } catch (Exception e) {
            log.error("Failed to export backtest: {}", e.getMessage());
            return null;
        }
    }
}
