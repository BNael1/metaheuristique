package bezier2_0.projects.competitor.optipath;

/**
 * Strategie de restart au niveau OptiPath (restart externe).
 * Decide quand relancer les deux CMA-ES depuis des seeds differents.
 *
 * Distinct du RestartStrategy interne (BIPOP/IPOP) qui gere les
 * restarts au sein d'une seule instance CMA-ES.
 */
public interface OuterRestartStrategy
{
    /** Resultat de la decision de restart. */
    enum Decision
    {
        /** Pas de restart. */
        CONTINUE,
        /** Relancer les deux CMA-ES. */
        RESTART_BOTH,
        /** Relancer uniquement le CMA-ES small. */
        RESTART_SMALL,
        /** Relancer uniquement le CMA-ES wide. */
        RESTART_WIDE
    }

    /** Initialisation avec les stats du seeding. */
    void init (SeedingStats stats);

    /** Notifie d'une amelioration du fitness global. */
    void onFitnessUpdate (double fitness, long timestampMs);

    /** Decide si un restart est necessaire. */
    Decision shouldRestart (OuterRestartContext ctx);

    /** Callback apres un restart effectif. */
    void onRestart ();
}
