package com.tbot.scalp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeOrder {
    private String clientOrderId;
    private String pair;
    private String timeframe;
    private String direction;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private int leverage;
    private double quantity;
    private double positionSizeUsd;
    private double score;
    private String orderType;
    private long signalTimestamp;
}
