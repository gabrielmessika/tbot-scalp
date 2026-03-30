package com.tbot.scalp.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.tbot.scalp.model.Candle;

@Service
public class IndicatorService {

    public double[] calculateEMA(List<Candle> candles, int period) {
        double[] ema = new double[candles.size()];
        if (candles.isEmpty() || period <= 0)
            return ema;

        double multiplier = 2.0 / (period + 1);
        ema[0] = candles.get(0).getClose();
        for (int i = 1; i < candles.size(); i++) {
            ema[i] = (candles.get(i).getClose() - ema[i - 1]) * multiplier + ema[i - 1];
        }
        return ema;
    }

    public double[] calculateRSI(List<Candle> candles, int period) {
        double[] rsi = new double[candles.size()];
        if (candles.size() < period + 1)
            return rsi;

        double gainSum = 0, lossSum = 0;
        for (int i = 1; i <= period; i++) {
            double change = candles.get(i).getClose() - candles.get(i - 1).getClose();
            if (change > 0)
                gainSum += change;
            else
                lossSum += Math.abs(change);
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        rsi[period] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));

        for (int i = period + 1; i < candles.size(); i++) {
            double change = candles.get(i).getClose() - candles.get(i - 1).getClose();
            avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
            rsi[i] = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));
        }
        return rsi;
    }

    public double[] calculateATR(List<Candle> candles, int period) {
        double[] atr = new double[candles.size()];
        if (candles.size() < 2)
            return atr;

        double sum = 0;
        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(c.getHigh() - c.getLow(),
                    Math.max(Math.abs(c.getHigh() - prev.getClose()),
                            Math.abs(c.getLow() - prev.getClose())));
            if (i <= period) {
                sum += tr;
                atr[i] = sum / i;
            } else {
                atr[i] = (atr[i - 1] * (period - 1) + tr) / period;
            }
        }
        return atr;
    }

    public double[] calculateBollingerUpper(List<Candle> candles, int period, double stdDevMult) {
        double[] upper = new double[candles.size()];
        double[] sma = calculateSMA(candles, period);
        double[] stdDev = calculateStdDev(candles, period);
        for (int i = 0; i < candles.size(); i++) {
            upper[i] = sma[i] + stdDevMult * stdDev[i];
        }
        return upper;
    }

    public double[] calculateBollingerLower(List<Candle> candles, int period, double stdDevMult) {
        double[] lower = new double[candles.size()];
        double[] sma = calculateSMA(candles, period);
        double[] stdDev = calculateStdDev(candles, period);
        for (int i = 0; i < candles.size(); i++) {
            lower[i] = sma[i] - stdDevMult * stdDev[i];
        }
        return lower;
    }

    public double[] calculateBollingerBandwidth(List<Candle> candles, int period, double stdDevMult) {
        double[] bw = new double[candles.size()];
        double[] sma = calculateSMA(candles, period);
        double[] stdDev = calculateStdDev(candles, period);
        for (int i = 0; i < candles.size(); i++) {
            bw[i] = sma[i] > 0 ? (2 * stdDevMult * stdDev[i]) / sma[i] : 0;
        }
        return bw;
    }

    public double[] calculateSMA(List<Candle> candles, int period) {
        double[] sma = new double[candles.size()];
        double sum = 0;
        for (int i = 0; i < candles.size(); i++) {
            sum += candles.get(i).getClose();
            if (i >= period)
                sum -= candles.get(i - period).getClose();
            sma[i] = i >= period - 1 ? sum / Math.min(i + 1, period) : candles.get(i).getClose();
        }
        return sma;
    }

    public double[] calculateStdDev(List<Candle> candles, int period) {
        double[] stdDev = new double[candles.size()];
        for (int i = period - 1; i < candles.size(); i++) {
            double sum = 0, sumSq = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double v = candles.get(j).getClose();
                sum += v;
                sumSq += v * v;
            }
            double mean = sum / period;
            stdDev[i] = Math.sqrt(sumSq / period - mean * mean);
        }
        return stdDev;
    }

    public double[] calculateVWAP(List<Candle> candles) {
        double[] vwap = new double[candles.size()];
        double cumVol = 0, cumTpVol = 0;
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            double tp = (c.getHigh() + c.getLow() + c.getClose()) / 3.0;
            cumVol += c.getVolume();
            cumTpVol += tp * c.getVolume();
            vwap[i] = cumVol > 0 ? cumTpVol / cumVol : c.getClose();
        }
        return vwap;
    }

    /** Session VWAP that resets at detected session boundary (every 24h) */
    public double[] calculateSessionVWAP(List<Candle> candles, long sessionDurationMs) {
        double[] vwap = new double[candles.size()];
        double cumVol = 0, cumTpVol = 0;
        long sessionStart = candles.isEmpty() ? 0 : candles.get(0).getTimestamp();

        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            if (c.getTimestamp() - sessionStart >= sessionDurationMs) {
                cumVol = 0;
                cumTpVol = 0;
                sessionStart = c.getTimestamp();
            }
            double tp = (c.getHigh() + c.getLow() + c.getClose()) / 3.0;
            cumVol += c.getVolume();
            cumTpVol += tp * c.getVolume();
            vwap[i] = cumVol > 0 ? cumTpVol / cumVol : c.getClose();
        }
        return vwap;
    }

    public double[] calculateADX(List<Candle> candles, int period) {
        double[] adx = new double[candles.size()];
        if (candles.size() < period * 2)
            return adx;

        double[] plusDM = new double[candles.size()];
        double[] minusDM = new double[candles.size()];
        double[] tr = new double[candles.size()];

        for (int i = 1; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double upMove = c.getHigh() - prev.getHigh();
            double downMove = prev.getLow() - c.getLow();
            plusDM[i] = (upMove > downMove && upMove > 0) ? upMove : 0;
            minusDM[i] = (downMove > upMove && downMove > 0) ? downMove : 0;
            tr[i] = Math.max(c.getHigh() - c.getLow(),
                    Math.max(Math.abs(c.getHigh() - prev.getClose()), Math.abs(c.getLow() - prev.getClose())));
        }

        double smoothTR = 0, smoothPlusDM = 0, smoothMinusDM = 0;
        for (int i = 1; i <= period; i++) {
            smoothTR += tr[i];
            smoothPlusDM += plusDM[i];
            smoothMinusDM += minusDM[i];
        }

        double dxSum = 0;
        for (int i = period; i < candles.size(); i++) {
            if (i > period) {
                smoothTR = smoothTR - smoothTR / period + tr[i];
                smoothPlusDM = smoothPlusDM - smoothPlusDM / period + plusDM[i];
                smoothMinusDM = smoothMinusDM - smoothMinusDM / period + minusDM[i];
            }
            double plusDI = smoothTR > 0 ? 100 * smoothPlusDM / smoothTR : 0;
            double minusDI = smoothTR > 0 ? 100 * smoothMinusDM / smoothTR : 0;
            double dx = (plusDI + minusDI) > 0 ? 100 * Math.abs(plusDI - minusDI) / (plusDI + minusDI) : 0;

            if (i < period * 2) {
                dxSum += dx;
                adx[i] = dxSum / (i - period + 1);
            } else {
                adx[i] = (adx[i - 1] * (period - 1) + dx) / period;
            }
        }
        return adx;
    }

    public double[] calculateRelativeVolume(List<Candle> candles, int lookback) {
        double[] rv = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            if (i < lookback) {
                rv[i] = 1.0;
                continue;
            }
            double sum = 0;
            for (int j = i - lookback; j < i; j++) {
                sum += candles.get(j).getVolume();
            }
            double avg = sum / lookback;
            rv[i] = avg > 0 ? candles.get(i).getVolume() / avg : 1.0;
        }
        return rv;
    }

    /** Stochastic RSI – outputs K and D lines */
    public double[][] calculateStochRSI(List<Candle> candles, int rsiPeriod, int stochPeriod, int kSmooth,
            int dSmooth) {
        double[] rsi = calculateRSI(candles, rsiPeriod);
        double[] k = new double[candles.size()];
        double[] d = new double[candles.size()];

        for (int i = stochPeriod + rsiPeriod; i < candles.size(); i++) {
            double minRsi = Double.MAX_VALUE, maxRsi = Double.MIN_VALUE;
            for (int j = i - stochPeriod + 1; j <= i; j++) {
                minRsi = Math.min(minRsi, rsi[j]);
                maxRsi = Math.max(maxRsi, rsi[j]);
            }
            k[i] = (maxRsi - minRsi) > 0 ? ((rsi[i] - minRsi) / (maxRsi - minRsi)) * 100 : 50;
        }

        // Smooth K
        double[] smoothK = new double[candles.size()];
        double kSum = 0;
        for (int i = 0; i < candles.size(); i++) {
            kSum += k[i];
            if (i >= kSmooth)
                kSum -= k[i - kSmooth];
            smoothK[i] = i >= kSmooth - 1 ? kSum / kSmooth : k[i];
        }

        // D line = SMA of smoothK
        double dSum = 0;
        for (int i = 0; i < candles.size(); i++) {
            dSum += smoothK[i];
            if (i >= dSmooth)
                dSum -= smoothK[i - dSmooth];
            d[i] = i >= dSmooth - 1 ? dSum / dSmooth : smoothK[i];
        }

        return new double[][] { smoothK, d };
    }
}
