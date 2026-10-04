package com.sharpforge.etl.api.backtest;

import org.springframework.stereotype.Component;

/**
 * Utility to calculate the statistical significance (p-value) of a betting system
 * to protect users against overfitting (the "Sharp Trap").
 */
@Component
public class StatisticalSignificanceUtil {

    // The break-even win rate for standard -110 juice
    private static final double NULL_HYPOTHESIS_WIN_RATE = 0.5238;

    /**
     * Calculates the one-sided p-value using a normal approximation to the binomial distribution.
     * Evaluates if the system's win rate is statistically significantly greater than the break-even rate.
     */
    public double calculatePValue(int wins, int losses) {
        int n = wins + losses;
        if (n == 0) return 1.0;

        double p_hat = (double) wins / n;

        // If win rate is below break-even, the system is not profitable, p-value is effectively 1.0
        if (p_hat <= NULL_HYPOTHESIS_WIN_RATE) {
            return 1.0;
        }

        // Z-Score calculation: (p_hat - p_null) / sqrt(p_null * (1 - p_null) / n)
        double standardError = Math.sqrt((NULL_HYPOTHESIS_WIN_RATE * (1 - NULL_HYPOTHESIS_WIN_RATE)) / n);
        double zScore = (p_hat - NULL_HYPOTHESIS_WIN_RATE) / standardError;

        return calculatePValueFromZScore(zScore);
    }

    /**
     * Approximates the p-value from a Z-score using the complementary error function.
     * (One-tailed test, probability of observing a Z-score this extreme or greater).
     */
    private double calculatePValueFromZScore(double z) {
        // Approximation of the standard normal CDF
        double x = z / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double a1 = 0.254829592, a2 = -0.284496736, a3 = 1.421413741;
        double a4 = -1.453152027, a5 = 1.061405429;
        double erf = 1.0 - ((((a5 * t + a4) * t + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        
        double pValue = 0.5 * (1.0 - erf);
        return z > 0 ? pValue : 1.0 - pValue;
    }

    public String determineConfidence(double pValue, int sampleSize) {
        if (sampleSize < 30) {
            return "LOW"; // Small sample size trap
        }
        if (pValue < 0.05) {
            return "HIGH"; // Statistically significant
        }
        if (pValue < 0.15) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
