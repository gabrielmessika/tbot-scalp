package com.tbot.scalp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawSignal {
    private int candleIndex;
    private String direction; // LONG or SHORT
    private double entryPrice;
    private double suggestedSl;
    private double suggestedTp;
    private String strategyName;
    private double strength; // 0.0 to 1.0
}
