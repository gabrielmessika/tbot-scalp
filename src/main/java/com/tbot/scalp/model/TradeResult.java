package com.tbot.scalp.model;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeResult {
    private long entryTime;
    private long exitTime;
    private String pair;
    private String timeframe;
    private String direction;
    @Builder.Default
    private List<String> strategies = new ArrayList<>();
    private double entryPrice;
    private double exitPrice;
    private double stopLoss;
    private double takeProfit;
    private int leverage;
    private double positionSizeUsd;
    private double score;
    private String result; // "TP Hit ✅", "SL Hit ❌", "Timeout ⏰", "Trailing Stop 📈", "Trend Close ↩️",
                           // "Skipped 🚫", "Pending ⏳"
    private double pnl;
    private double pnlPercent;
    private int candlesElapsed;
    private boolean breakEvenApplied;
    private String skipReason;
    private double balanceAfter;
}
