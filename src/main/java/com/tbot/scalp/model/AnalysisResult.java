package com.tbot.scalp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisResult {
    @Builder.Default
    private List<LogEntry> logs = new ArrayList<>();
    @Builder.Default
    private List<Signal> alerts = new ArrayList<>();
    private BacktestSummary backtest;
    private long analysisTimestamp;
    private long analysisTimeMs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LogEntry {
        private long timestamp;
        private String level;
        private String message;
    }
}
