package com.tbot.scalp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradeExecution {
    private String exchangeOrderId;
    private String clientOrderId;
    private String pair;
    private String direction;
    private double fillPrice;
    private double quantity;
    private int leverage;
    private double stopLoss;
    private double takeProfit;
    private String status; // FILLED, REJECTED, DRY_RUN
    private String errorMessage;
    private boolean dryRun;
    private long executionTimestamp;
    private String tpTriggerId;
    private String slTriggerId;
    private String exchange;

    public boolean isSuccess() {
        return "FILLED".equals(status) || "DRY_RUN".equals(status);
    }
}
