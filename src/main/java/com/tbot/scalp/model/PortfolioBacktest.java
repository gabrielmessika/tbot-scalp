package com.tbot.scalp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioBacktest {
    private double initialBalance;
    private double finalBalance;
    private double roi;
    private double winRate;
    private int totalTrades;
    private int wins;
    private int losses;
    private int pending;
    private int skipped;
    private double maxDrawdown;
    private double bestTrade;
    private double worstTrade;
    @Builder.Default
    private List<TradeResult> trades = new ArrayList<>();
    @Builder.Default
    private Map<String, Integer> skipReasons = new LinkedHashMap<>();
}
