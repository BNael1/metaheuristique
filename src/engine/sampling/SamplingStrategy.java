package engine.sampling;

import java.util.Random;

/**
 * Stratégie d'échantillonnage pour CMA-ES.
 * Génère les offspring à partir de la distribution N(mean, sigma² C).
 */
public interface SamplingStrategy
{
    /**
     * Échantillonne lambda offspring.
     *
     * @param mean   centroïde d×1
     * @param sigma  step-size
     * @param B      vecteurs propres de C (d×d)
     * @param diagD  racines carrées des valeurs propres (d×1)
     * @param lambda nombre d'offspring
     * @param d      dimension
     * @param rng    générateur aléatoire
     * @return SampleResult contenant arx (positions) et ary (y_k normalisés)
     */
    SampleResult sample (double [] mean, double sigma, double [][] B,
                         double [] diagD, int lambda, int d, Random rng);
}
