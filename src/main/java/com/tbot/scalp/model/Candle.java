package com.tbot.scalp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Candle {
    private long timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private int numTrades;

    public double body() {
        return Math.abs(close - open);
    }

    public boolean isBullish() {
        return close > open;
    }

    public boolean isBearish() {
        return close < open;
    }

    public double bodyTop() {
        return Math.max(open, close);
    }

    public double bodyBottom() {
        return Math.min(open, close);
    }

    public double range() {
        return high - low;
    }

    public double upperWick() {
        return high - bodyTop();
    }

    public double lowerWick() {
        return bodyBottom() - low;
    }

    public double midPrice() {
        return (high + low) / 2.0;
    }
}
