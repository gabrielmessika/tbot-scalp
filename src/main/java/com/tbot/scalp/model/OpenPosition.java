package com.tbot.scalp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPosition {
    private String pair;
    private String timeframe;
    private String direction;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int leverage;
    private double quantity;
    private double originalStopLoss;
    private double currentPrice;
    private double currentPnlPercent;
    private int candlesElapsed;
    private boolean breakEvenApplied;
    private int beLevel; // 0=none, 1=entry, 2=25%TP, 3=50%TP
    private String clientOrderId;
    private String exchangeOrderId;
    private String tpTriggerId;
    private String slTriggerId;
    private String exchange;
    private boolean dryRun;
    private long openTimestamp;
    private String closeReason;
    private double score;

    public double getTpProgressPercent() {
        if (takeProfit == entryPrice)
            return 0;
        double tpDist = Math.abs(takeProfit - entryPrice);
        double currentDist = "LONG".equals(direction)
                ? currentPrice - entryPrice
                : entryPrice - currentPrice;
        return (currentDist / tpDist) * 100.0;
    }

    public boolean isProfitable() {
        return "LONG".equals(direction) ? currentPrice > entryPrice : currentPrice < entryPrice;
    }

    public void updatePrice(double price) {
        this.currentPrice = price;
        double diff = "LONG".equals(direction) ? price - entryPrice : entryPrice - price;
        this.currentPnlPercent = (diff / entryPrice) * 100.0 * leverage;
    }

    public double getPositionSizeUsd() {
        return quantity * entryPrice;
    }
}
