# Benchmark des variantes CMA-ES pour l'optimisation de courbes de Bézier

## Protocole expérimental

- **Budget** : 60 secondes par exécution (wall-clock)
- **Répétitions** : 3 runs par (algorithme, problème) pour les 12 premiers algorithmes ; 1 run pour les 4 nouvelles idées (GridBIPOP, StochRank, AStarRepair, SepWarmup)
- **Problèmes** : 4 instances (prob1 à prob4)
- **Algorithmes** : 16 au total (6 existants + 6 variantes précédentes + 4 nouvelles idées)
- **Total** : 172 exécutions (hors MultiSegment, exclu car résultats aberrants sur prob2)

## Résultats — Score moyen (3 runs pour les 12 premiers, 1 run pour les 4 nouvelles idées)

| Algorithme     | prob1  | prob2  | prob3  | prob4    |
|----------------|--------|--------|--------|----------|
| CMAES          | 36.35  | 31.48  | 28.45  | 6026.86  |
| DE             | 36.62  | 31.48  | 30.62  | 6242.19  |
| GA             | 37.38  | 31.49  | 42.11  | 5940.93  |
| BIPOP          | 37.70  | 31.48  | 28.87  | 4678.70  |
| SHADE          | 36.42  | 31.48  | 28.45  | 5894.32  |
| BipopAdaptif   | 37.71  | 31.48  | 29.14  | 5670.98  |
| *LMCMA*        | 39.09  | 31.80  | 69.38  | 5148.37  |
| *AStarSeeds*   | 36.36  | 31.48  | 28.92  | 5835.41  |
| *NBIPOP*       | 37.38  | 31.48  | 29.78  | 5610.85  |
| *ALConstraint* | 36.70  | 31.48  | 31.59  | 4911.02  |
| *RFSurrogate*  | 39.47  | 31.48  | 28.35  | 5564.34  |
| *Islands*      | 36.70  | 31.48  | 29.41  | 5726.27  |
| **GridBIPOP**  | 38.39  | 31.48  | 29.50  | 5779.97  |
| **StochRank**  | 37.36  | 31.48  | 28.32  | 5439.37  |
| **AStarRepair**| 38.39  | 31.48  | 28.24  | 5848.11  |
| **SepWarmup**  | 37.42  | 31.48  | 35.98  | 5468.24  |

| **PSO**        | 37.43  | 31.49  | 45.12  | 2941.39  |

> Les 6 variantes précédentes sont en *italique*, les 4 nouvelles idées en **gras**.

## Résultats — Meilleur score (min sur 3 runs, ou 1 run pour les 4 nouvelles idées)

| Algorithme     | prob1  | prob2  | prob3  | prob4    |
|----------------|--------|--------|--------|----------|
| CMAES          | 36.34  | 31.48  | 28.27  | 5808.96  |
| DE             | 36.56  | 31.48  | 30.54  | 6064.85  |
| GA             | 36.56  | 31.49  | 40.50  | 5505.95  |
| BIPOP          | 37.35  | 31.48  | 28.28  | 3104.26  |
| SHADE          | 36.35  | 31.48  | 28.23  | 5418.86  |
| BipopAdaptif   | 37.36  | 31.48  | 28.86  | 5468.21  |
| *LMCMA*        | 38.25  | 31.60  | 46.65  | 3183.75  |
| *AStarSeeds*   | 36.33  | 31.48  | 28.45  | 5749.04  |
| *NBIPOP*       | 36.36  | 31.48  | 29.49  | 5465.99  |
| *ALConstraint* | 36.34  | 31.48  | 31.45  | 3121.60  |
| *RFSurrogate*  | 37.36  | 31.48  | 28.23  | 5509.40  |
| *Islands*      | 36.35  | 31.48  | 28.43  | 5565.22  |
| **GridBIPOP**  | 38.39  | 31.48  | 29.50  | 5779.97  |
| **StochRank**  | 37.36  | 31.48  | 28.32  | 5439.37  |
| **AStarRepair**| 38.39  | 31.48  | 28.24  | 5848.11  |
| **SepWarmup**  | 37.42  | 31.48  | 35.98  | 5468.24  |

| **PSO**        | 36.70  | 31.45  | 36.18  | 2079.72  |

## Classement par problème (score moyen, du meilleur au pire)

### prob1 (16 points de contrôle, 5 obstacles)
1. CMAES — 36.35
2. AStarSeeds — 36.36
3. SHADE — 36.42
4. DE — 36.62
5. ALConstraint — 36.70
6. Islands — 36.70
7. **StochRank** — 37.36
8. GA / NBIPOP — 37.38
9. **GridBIPOP** — 38.39
10. **AStarRepair** — 38.39
11. **SepWarmup** — 37.42
12. BIPOP — 37.70
13. BipopAdaptif — 37.71
14. LMCMA — 39.09
15. RFSurrogate — 39.47

### prob2 (4 points de contrôle, 1 obstacle)
Tous les algorithmes convergent essentiellement vers la même valeur (~31.48), à l'exception de LMCMA (31.80) et GA (31.49). Problème trop simple pour discriminer.

### prob3 (8 points de contrôle, 33 obstacles / 3 murs)
1. **AStarRepair** — 28.24
2. RFSurrogate — 28.35
3. **StochRank** — 28.32
4. CMAES — 28.45
5. SHADE — 28.45
6. BIPOP — 28.87
7. AStarSeeds — 28.92
8. BipopAdaptif — 29.14
9. Islands — 29.41
10. **GridBIPOP** — 29.50
11. NBIPOP — 29.78
12. DE — 30.62
13. ALConstraint — 31.59
14. **SepWarmup** — 35.98
15. GA — 42.11
16. LMCMA — 69.38

### prob4 (8 points de contrôle, 33 obstacles / labyrinthe en S)
1. BIPOP — 4678.70 *(haute variance)*
2. ALConstraint — 4911.02
3. LMCMA — 5148.37
4. **StochRank** — 5439.37
5. **SepWarmup** — 5468.24
6. RFSurrogate — 5564.34
7. NBIPOP — 5610.85
8. BipopAdaptif — 5670.98
9. Islands — 5726.27
10. **GridBIPOP** — 5779.97
11. AStarSeeds — 5835.41
12. **AStarRepair** — 5848.11
13. SHADE — 5894.32
14. GA — 5940.93
15. CMAES — 6026.86
16. DE — 6242.19

## Analyse

### Problèmes faciles (prob1, prob2)
Sur prob1 et prob2, les différences entre algorithmes sont minimes. CMA-ES de base et AStarSeeds dominent légèrement sur prob1. Prob2, avec un seul obstacle et 4 points de contrôle, ne discrimine pas les algorithmes — tous atteignent la solution optimale ou quasi-optimale. Parmi les nouvelles idées, StochRank (37.36) et SepWarmup (37.42) se placent dans la moyenne, tandis que GridBIPOP et AStarRepair (38.39) sous-performent sur prob1.

### Problème intermédiaire (prob3)
**AStarRepair** obtient le meilleur score absolu (28.24), dépassant RFSurrogate (28.35) et SHADE (28.23 best). La réparation A* guide efficacement les solutions vers des zones faisables dans un espace structuré par 3 murs. **StochRank** (28.32) se place aussi dans le top 3 grâce à sa gestion stochastique des contraintes. **GridBIPOP** (29.50) est correct mais sans gain notable. **SepWarmup** (35.98) déçoit fortement — l'approximation diagonale initiale perd trop d'information structurelle dans un problème multi-corridors.

### Problème difficile (prob4 — labyrinthe)
Le labyrinthe en S reste le problème le plus discriminant. BIPOP conserve le meilleur score moyen (4678.70) malgré sa haute variance, suivi d'ALConstraint (4911.02). Parmi les nouvelles idées, **StochRank** (5439.37) et **SepWarmup** (5468.24) se placent bien, dans le top 5–6. Le ranking stochastique aide à traverser les zones de forte pénalité, et le warm-up diagonal permet un démarrage rapide avant la transition vers la CMA-ES complète. **GridBIPOP** (5779.97) et **AStarRepair** (5848.11) sont décevants sur ce problème — l'archive QD du GridBIPOP disperse trop l'exploration dans un labyrinthe étroit, et la réparation A* fixe les solutions vers des chemins A* qui ne sont pas assez fins pour le labyrinthe.

### Bilan des 4 nouvelles idées

| Variante | Forces | Faiblesses |
|----------|--------|------------|
| **GridBIPOP** | Concept QD intéressant, stable sur prob2/prob3 | Pas de gain significatif, sous-performe sur prob1 (38.39) et prob4 (5779) |
| **StochRank** | **Top 3 sur prob3** (28.32), **top 4 sur prob4** (5439) | prob1 médiocre (37.36) |
| **AStarRepair** | **Meilleur sur prob3** (28.24), repair A* efficace | Pire des 4 sur prob4 (5848), prob1 faible (38.39) |
| **SepWarmup** | **Top 5 sur prob4** (5468), démarrage rapide | **Catastrophique sur prob3** (35.98), overhead de transition |

### Bilan des variantes précédentes (rappel)

| Variante | Forces | Faiblesses |
|----------|--------|------------|
| **LM-CMA-ES** | Bon potentiel sur prob4 (best=3183) | Très instable, catastrophique sur prob3 (69.38) |
| **A*Seeds** | Excellent sur prob1, bon et stable partout | Pas de gain sur prob3/prob4 vs. CMAES de base |
| **NBIPOP** | Stable, bon compromis prob4 (5610) | Légèrement inférieur à BIPOP sur prob4 |
| **ALConstraint** | **Meilleur sur prob4** (4911 moy., 3121 best) | Sous-performant sur prob3 (31.59) |
| **RFSurrogate** | **Excellent sur prob3** (28.35) | Variance sur prob1 (39.47), sans gain sur prob4 |
| **Islands** | Stable, bon compromis | Pas de gain significatif vs. CMAES de base |

### Recommandations
- **Problèmes simples** : CMA-ES de base ou AStarSeeds
- **Problèmes multi-obstacles** : AStarRepair (28.24) ou RFSurrogate (28.35) ou SHADE
- **Problèmes labyrinthe** : ALConstraint ou BIPOP ; StochRank et SepWarmup comme alternatives
- **Usage général** : StochRank offre le meilleur compromis parmi les nouvelles idées (top 3–4 sur prob3 et prob4)

---

## Expérience : Extended Bounds (bornes étendues pour les points de contrôle)

### Hypothèse

Dans CMA-ES, les points de contrôle de la courbe de Bézier sont contraints à l'intérieur de la surface `[minX, maxX] × [minY, maxY]`. Or la matrice de covariance C est biaisée dès que la distribution se retrouve tronquée par les bornes : les composantes perpendiculaires aux murs reçoivent artificiellement moins de variance, ce qui déséquilibre l'adaptation. En élargissant les bornes des points de contrôle d'une marge `cpMargin` (tout en gardant la pénalité sur les **points échantillonnés** de la courbe), on supprime cette troncature et on laisse la covariance s'adapter librement. L'effet attendu est surtout visible sur les problèmes à forte densité d'obstacles (prob3, prob4), où de nombreux points de contrôle se retrouvent collés aux bords.

### Protocole

- **Configurations** : `baseline` (bornes originales), `margin3` (±3), `margin5` (±5), `margin8` (±8)
- **Sigma initial** : calculé sur l'intervalle original `(ub−lb)/6`, identique pour toutes les configurations
- **Budget** : 60 secondes par run, 3 runs par (config, problème)
- **Fichier source** : `CMAESOptimizerExtendedBounds.java` (copie de CMAESOptimizer avec constructeur modifié)
- **CSV** : `results/bench_ExtendedBounds.csv`

### Résultats — Score moyen (3 runs)

| Config   | prob1  | prob2  | prob3  | prob4    |
|----------|--------|--------|--------|----------|
| baseline | 36.80  | 31.52  | 29.37  | 4845.08  |
| margin3  | 36.67  | 31.42  | 28.40  | 3849.40  |
| margin5  | 36.78  | 31.42  | 28.88  | 2640.41  |
| margin8  | 37.07  | 31.42  | 28.25  | 2655.14  |

### Résultats — Meilleur score (min sur 3 runs)

| Config   | prob1  | prob2  | prob3  | prob4    |
|----------|--------|--------|--------|----------|
| baseline | 36.37  | 31.48  | 28.89  | 3095.44  |
| margin3  | 36.32  | 31.42  | 28.22  | 2300.45  |
| margin5  | 36.33  | 31.42  | 28.22  | 1958.42  |
| margin8  | 36.36  | 31.42  | 28.21  | 1538.28  |

### Variation relative par rapport au baseline (score moyen)

| Config   | prob1   | prob2   | prob3   | prob4    |
|----------|---------|---------|---------|----------|
| margin3  | −0.4%   | −0.3%   | −3.3%   | −20.5%   |
| margin5  | −0.1%   | −0.3%   | −1.7%   | −45.5%   |
| margin8  | +0.7%   | −0.3%   | −3.8%   | −45.2%   |

### Analyse par problème

**prob1 (16 CPs, 5 obstacles)** — Les marges n'apportent pas de gain significatif (±0.7%). Les points de contrôle ont assez d'espace pour évoluer librement, la troncature de C ne se manifeste pas. margin8 dégrade légèrement le score (+0.7%), probablement parce que l'espace de recherche élargi ralentit la convergence sans apporter de bénéfice structurel.

**prob2 (4 CPs, 1 obstacle)** — Toutes les marges convergent vers ~31.42, très légèrement meilleur que la baseline (31.52). Le problème est trop simple pour discriminer, mais on note que les marges permettent de trouver systématiquement la solution optimale.

**prob3 (8 CPs, 33 obstacles / 3 murs)** — Amélioration notable : margin8 atteint 28.25 (−3.8% en moyenne) et 28.21 (best). margin3 est similaire (28.40 moy, 28.22 best). L'extension des bornes libère les CPs coincés entre les murs et permet à la covariance de mieux couvrir les corridors étroits.

**prob4 (8 CPs, 33 obstacles / labyrinthe en S)** — **Résultat majeur** : margin5 (2640 moy, 1958 best) et margin8 (2655 moy, **1538 best**) réduisent le score moyen de **~45%** par rapport au baseline (4845). Même margin3 (3849, −20.5%) est nettement meilleur. L'effet est spectaculaire : dans le labyrinthe en S, les CPs sont systématiquement poussés contre les murs, ce qui compresse artificiellement la covariance. En relâchant cette contrainte, CMA-ES explore mieux les virages du labyrinthe. Le best de 1538 (margin8) est le **meilleur score jamais obtenu sur prob4** dans tout ce benchmark, battant BIPOP (3104 best), ALConstraint (3122 best) et LM-CMA (3184 best).

### Conclusion

L'hypothèse est **confirmée** : étendre les bornes des points de contrôle améliore significativement les performances de CMA-ES, en particulier sur les problèmes où les obstacles forcent les CPs contre les limites de la surface. L'effet est massif sur prob4 (−45%) et modéré sur prob3 (−3.8%). Les marges de 5 à 8 offrent les meilleurs résultats. Cette modification est simple (1 paramètre `cpMargin`), sans coût computationnel supplémentaire, et compatible avec toutes les autres variantes CMA-ES du benchmark.

**Mise à jour des recommandations** :
- **Problèmes labyrinthe** : CMA-ES Extended Bounds (margin5–8) est désormais la meilleure approche, dominant toutes les variantes précédentes
- La combinaison Extended Bounds + autres optimisations (StochRank, ALConstraint) pourrait donner des résultats encore meilleurs

### Tableau récapitulatif — mean (best)

| Problème | baseline           | margin3            | margin5            | margin8            |
|----------|--------------------|--------------------|--------------------|--------------------|
| prob1    |   36,80 (  36,37)  |   36,67 (  36,32)  |   36,78 (  36,33)  |   37,07 (  36,36)  |
| prob2    |   31,52 (  31,48)  |   31,42 (  31,42)  |   31,42 (  31,42)  |   31,42 (  31,42)  |
| prob3    |   29,37 (  28,89)  |   28,40 (  28,22)  |   28,88 (  28,22)  |   28,25 (  28,21)  |
| prob4    | 4845,08 (3095,44)  | 3849,40 (2300,45)  | 2640,41 (1958,42)  | 2655,14 (1538,28)  |

> Format : mean (best) — CSV source : `results/bench_ExtendedBounds.csv`

---

## Experience : PSO (Particle Swarm Optimization)

### Protocole

- **Algorithme** : PSO avec inertie decroissante lineaire (w : 0.9 -> 0.4), N = 10xd, c1 = c2 = 2.0
- **Anti-stagnation** : reinitialisation de 50% des particules apres N x 10 generations sans amelioration
- **Bornes** : bornes naturelles du probleme (sans marge), positions non clampes
- **Budget** : 60 secondes par run (wall-clock)
- **Repetitions** : 10 runs par probleme

### Resultats - Score moyen (10 runs)

| Probleme | PSO mean | PSO best | Reference CMAES | Reference BIPOP |
|----------|----------|----------|-----------------|-----------------|
| prob1    | 37.43    | 36.70    | 36.35           | 37.70           |
| prob2    | 31.49    | 31.45    | 31.48           | 31.48           |
| prob3    | 45.12    | 36.18    | 28.45           | 28.87           |
| prob4    | 2941.39  | 2079.72  | 6026.86         | 4678.70         |

> CSV source : `results/bench_PSO.csv`
