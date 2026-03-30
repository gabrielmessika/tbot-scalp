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
public class BacktestSession {
    private String sessionName;
    private String sessionDescription;
    private long startTimestamp;
    private int totalSignals;
    @Builder.Default
    private List<PortfolioBacktest> portfolios = new ArrayList<>();
}
