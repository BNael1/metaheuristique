# Architecture du projet

## Vue d'ensemble

```
src/
├── bezier/          ← Framework du professeur (ne pas modifier)
├── engine/          ← Briques composables (notre code algo)
└── bench/           ← Outils de test, benchmark et GUI
```

---

## `src/bezier/` — Framework du professeur

Code fourni par le cours. **Ne pas modifier.**

```
bezier/
├── evaluation/      Définition du problème
│   ├── Problem.java          Charge les .bzr, calcule le fitness
│   ├── Bezier.java           Génère la courbe de Bézier depuis les points de contrôle
│   ├── Coordinates.java      Point 2D
│   ├── Obstacle.java         Obstacle circulaire
│   ├── Solution.java         Stockage de la meilleure solution
│   ├── BezierChart.java      Visualisation live de la trajectoire
│   ├── MonitorChart.java     Courbe de convergence du fitness
│   ├── SquareXYPlot.java     Plot à ratio fixe
│   └── PathExporter.java     Export des solutions
│
├── output/          Logging
│   ├── Output.java / OutputWriter.java
│   ├── StandardOutput.java   Console
│   └── LogFileOutput.java    Fichier
│
├── projects/        Contrats de compétition
│   ├── Project.java           Runnable avec initialization() + loop()
│   ├── CompetitorProject.java Classe de base à étendre pour la soumission
│   ├── InvalidProjectException.java
│   └── demo/                 Exemples fournis (RandomSearch, HillClimbing, RandomWalk)
│
└── run/             Point d'entrée du prof
    ├── Main.java              Lance tous les CompetitorProject via réflexion
    ├── MainFrame.java         Fenêtre Swing legacy
    └── UIInitializer.java     Init Swing
```

### Package de soumission

```
bezier/projects/competitor/optipath/
└── OptiPath.java    ← L'unique fichier à rendre au prof
                       Étend CompetitorProject, assemble les briques de engine/
```

---

## `src/engine/` — Briques composables

Notre code. Chaque sous-package est une **interface + ses implémentations**.
On modifie ici pour améliorer un composant, sans toucher au reste.

```
engine/
├── core/
│   ├── Optimizer.java          Interface : init(), step(), getBestX(), getBestFitness(),
│   │                                       shouldRestart(), getState()
│   ├── OptimizerState.java     État observable (generation, evaluations, sigma...)
│   ├── AlgorithmParameters.java Hyperparamètres (margin, controlPoints, initStrategy...)
│   ├── Individual.java         Vecteur solution + fitness
│   └── MultiSegmentBezier.java Décomposition multi-segments avec continuité C1
│
├── cmaes/
│   ├── CMAESCore.java     Implémentation CMA-ES complète (stratégies injectables)
│   └── CMAESBuilder.java  Builder fluent pour assembler des variantes :
│                            .ipop()         IPOP-CMA-ES (restart population croissante)
│                            .bipop()        BIPOP (dual-régime exploration/exploitation)
│                            .active()       Active CMA-ES (poids négatifs)
│                            .surrogate()    BIPOP + présélection RBF
│                            .adaptiveBipop() BIPOP adaptatif (bandit)
│                            .gridBipop()    BIPOP + archive MAP-Elites
│                            .stochRank()    BIPOP + classement stochastique
│                            .sepWarmup()    Phase diagonale puis matrice pleine
│                            .full()         Fusion Active + Surrogate + AdaBIPOP
│
├── de/
│   ├── DECore.java        Differential Evolution jDE (self-adaptive F/CR)
│   └── DESHADECore.java   SHADE (historique circulaire, Lehmer mean)
│
├── ga/
│   └── GACore.java        GA réel, sélection tournoi, SBX + mutation polynomiale
│
├── restart/               Stratégies de restart pour CMAESCore
│   ├── RestartStrategy.java   Interface
│   ├── RestartConfig.java     lambda, sigma, mean pour le prochain restart
│   ├── RestartContext.java    Contexte passé à la stratégie
│   ├── IPOPRestart.java       Population × 2 à chaque restart
│   ├── BIPOPRestart.java      Alternance grands/petits λ
│   ├── AdaptiveBIPOP.java     Sélection adaptative du régime
│   └── GridMapElitesRestart.java  Archive QD + roulette
│
├── sampling/              Stratégies d'échantillonnage
│   ├── SamplingStrategy.java  Interface
│   ├── SampleResult.java      arx (positions) + ary (directions normalisées)
│   ├── StandardSampling.java  Gaussienne multivariée standard
│   └── MirrorSampling.java    Paires (z, -z) — réduction de variance
│
├── covariance/            Mises à jour de la matrice de covariance
│   ├── CovarianceUpdate.java         Interface (mode FULL ou SEPARABLE)
│   ├── StandardCovariance.java       CMA-ES standard (Hansen 2016)
│   ├── ActiveCovariance.java         Poids négatifs sur les pires individus
│   └── SeparableWarmupCovariance.java Phase diagonale O(d) puis transition vers O(d²)
│
├── constraints/           Gestion des contraintes et des bornes
│   ├── ConstraintHandler.java    Interface (tri de la population)
│   ├── StandardConstraint.java   Tri par fitness pure
│   ├── StochasticRanking.java    Runarsson & Yao 2000
│   └── BoundsChecker.java        Clamp dans [lb, ub]
│
└── surrogate/             Évaluation par modèle de substitution
    ├── EvalWrapper.java        Interface : evaluateBatch(), getEvalCount()
    ├── DirectEval.java         Évaluation directe (pas de surrogate)
    ├── SurrogatePrescreen.java Filtre les candidats via RBF avant évaluation réelle
    └── SurrogateRBF.java       Modèle RBF cubique (buffer circulaire, kernel adaptatif)
```

### Ajouter une nouvelle brique

1. Créer une implémentation de l'interface concernée (ex. `engine/restart/MyRestart.java`)
2. L'enregistrer dans `CMAESBuilder` si c'est pour CMA-ES
3. L'exposer via `ProjectCatalog` pour le benchmark
4. Tester depuis `BenchmarkComparison --only MonVariant`

---

## `src/bench/` — Outils de développement

```
bench/
├── ProjectCatalog.java     Factory centralisée — un appel par variante :
│                             ProjectCatalog.bipop(problem)
│                             ProjectCatalog.full(problem)
│                             ProjectCatalog.lmcma(problem) ...
│
├── OptimizerProject.java   Wrapper générique CompetitorProject → Optimizer
│                             Remplace tous les anciens OptiPath*.java
│
├── BenchmarkComparison.java  Benchmark incrémental (CSV, reprend où il s'est arrêté)
│                               java -cp "lib/*:bin" bench.BenchmarkComparison --seconds 60 --runs 5
│                               java -cp "lib/*:bin" bench.BenchmarkComparison --only BIPOP
│
├── BenchmarkExtendedBounds.java  Test avec marges de recherche variables (0, 3, 5, 8)
│
├── UIInitializer.java      Init Swing (thread EDT)
│
├── gui/                    Dashboard Swing (thème FlatDark)
│   ├── DashboardApp.java   Fenêtre principale à 5 onglets
│   ├── ConfigPane.java     Sélection problème + algorithme
│   ├── LiveRunPane.java    Exécution live avec visualisation trajectoire
│   ├── ComparePane.java    Comparaison côte-à-côte de deux algos
│   ├── BenchmarkPane.java  Benchmark batch avec barre de progression
│   ├── TunerPane.java      Grid search d'hyperparamètres
│   ├── AlgorithmFactory.java  Instancie un Optimizer depuis un nom (pour la GUI)
│   ├── AlgorithmRunner.java   Gère l'exécution en thread + monitoring
│   └── ThemeUtils.java     Couleurs FlatDarkLaf
│
└── competitors/            Algorithmes de comparaison (benchmarks uniquement)
    ├── lmcma/    LM-CMA-ES (Limited-Memory, auto-contenu)
    ├── nbipop/   NBIPOP (Noisy BIPOP, auto-contenu)
    ├── astarseeds/ A* + IPOP (auto-contenu)
    ├── alconst/  Lagrangien augmenté + IPOP (auto-contenu)
    ├── rfsurr/   Surrogate Random Forest + IPOP (auto-contenu)
    └── islands/  Modèle îles 3 populations (auto-contenu)
```

---

## Flux de travail

### Développer une nouvelle variante

```
1. Modifier/créer une brique dans engine/
2. L'exposer dans CMAESBuilder (si CMA-ES) ou ProjectCatalog directement
3. Tester : BenchmarkComparison --only MaVariante --seconds 10 --runs 1
4. Si meilleur résultat → mettre à jour OptiPath.java
```

### Préparer la soumission

```
OptiPath.java (bezier/projects/competitor/optipath/) doit être auto-suffisant :
- Étend CompetitorProject
- Un seul constructeur (Problem problem)
- Appelle addAuthor() + setMethodName() dans le constructeur
- initialization() + loop() uniquement
- Peut importer depuis engine/ et bezier/evaluation/
```

### Lancer le benchmark complet

```bash
javac -cp "lib/*" -sourcepath src -d bin src/engine/**/*.java src/bezier/**/*.java src/bench/**/*.java
java -cp "lib/*:bin" bench.BenchmarkComparison --seconds 60 --runs 5 --headless
```

---

## Dépendances externes (`lib/`)

| JAR | Utilisé par |
|-----|-------------|
| `jfreechart-1.5.5.jar` | Graphiques (BezierChart, MonitorChart, LiveRunPane) |
| `jcommon-1.0.23.jar` | Dépendance de JFreeChart |
| `flatlaf-3.4.1.jar` | Thème sombre du Dashboard (DashboardApp) |
| `reflections-0.9.12.jar` | Découverte auto des CompetitorProject (Main.java) |
| `javassist-3.21.0-GA.jar` | Dépendance de Reflections |
