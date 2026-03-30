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
public class Signal {
    private String pair;
    private String timeframe;
    private String direction;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private double score;
    private String confidence;
    @Builder.Default
    private List<String> strategies = new ArrayList<>();
    private double riskReward;
    private int leverage;
    private long timestamp;
    private int candleIndex;
    private long alertTimestamp;

    // Backtest results
    private String backtestResult;
    private double pnl;
    private double pnlPercent;

    public String getFormattedAlert() {
        return String.format("[%s] %s %s @ %.4f | SL: %.4f | TP: %.4f | Score: %.1f %s | R:R %.1f | Lev: %dx | %s",
                timeframe, direction, pair, entryPrice, stopLoss, takeProfit,
                score, confidence, riskReward, leverage, String.join(" + ", strategies));
    }
}
