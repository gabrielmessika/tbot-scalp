package com.tbot.scalp.service.strategy;

import com.tbot.scalp.model.Candle;
import com.tbot.scalp.model.RawSignal;

import java.util.List;

public interface ScalpStrategy {
    List<RawSignal> detect(List<Candle> candles, double[] atr, double[] rsi,
            double[][] emas, double[] vwap);

    String getName();
}
