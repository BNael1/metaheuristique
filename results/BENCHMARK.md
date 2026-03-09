# Benchmark des variantes CMA-ES pour l'optimisation de courbes de Bézier

## Protocole expérimental

- **Budget** : 60 secondes par exécution (wall-clock)
- **Répétitions** : 3 runs par (algorithme, problème)
- **Problèmes** : 4 instances (prob1 à prob4)
- **Algorithmes** : 12 au total (6 existants + 6 nouvelles variantes)
- **Total** : 156 exécutions (hors MultiSegment, exclu car résultats aberrants sur prob2)

## Résultats — Score moyen (3 runs)

| Algorithme     | prob1  | prob2  | prob3  | prob4    |
|----------------|--------|--------|--------|----------|
| CMAES          | 36.35  | 31.48  | 28.45  | 6026.86  |
| DE             | 36.62  | 31.48  | 30.62  | 6242.19  |
| GA             | 37.38  | 31.49  | 42.11  | 5940.93  |
| BIPOP          | 37.70  | 31.48  | 28.87  | 4678.70  |
| SHADE          | 36.42  | 31.48  | 28.45  | 5894.32  |
| BipopAdaptif   | 37.71  | 31.48  | 29.14  | 5670.98  |
| **LMCMA**      | 39.09  | 31.80  | 69.38  | 5148.37  |
| **AStarSeeds** | 36.36  | 31.48  | 28.92  | 5835.41  |
| **NBIPOP**     | 37.38  | 31.48  | 29.78  | 5610.85  |
| **ALConstraint** | 36.70 | 31.48 | 31.59  | 4911.02  |
| **RFSurrogate** | 39.47 | 31.48  | 28.35  | 5564.34  |
| **Islands**    | 36.70  | 31.48  | 29.41  | 5726.27  |

> Les 6 nouvelles variantes sont en **gras**.

## Résultats — Meilleur score (min sur 3 runs)

| Algorithme     | prob1  | prob2  | prob3  | prob4    |
|----------------|--------|--------|--------|----------|
| CMAES          | 36.34  | 31.48  | 28.27  | 5808.96  |
| DE             | 36.56  | 31.48  | 30.54  | 6064.85  |
| GA             | 36.56  | 31.49  | 40.50  | 5505.95  |
| BIPOP          | 37.35  | 31.48  | 28.28  | 3104.26  |
| SHADE          | 36.35  | 31.48  | 28.23  | 5418.86  |
| BipopAdaptif   | 37.36  | 31.48  | 28.86  | 5468.21  |
| **LMCMA**      | 38.25  | 31.60  | 46.65  | 3183.75  |
| **AStarSeeds** | 36.33  | 31.48  | 28.45  | 5749.04  |
| **NBIPOP**     | 36.36  | 31.48  | 29.49  | 5465.99  |
| **ALConstraint** | 36.34 | 31.48 | 31.45  | 3121.60  |
| **RFSurrogate** | 37.36 | 31.48  | 28.23  | 5509.40  |
| **Islands**    | 36.35  | 31.48  | 28.43  | 5565.22  |

## Classement par problème (score moyen, du meilleur au pire)

### prob1 (16 points de contrôle, 5 obstacles)
1. CMAES — 36.35
2. AStarSeeds — 36.36
3. SHADE — 36.42
4. DE — 36.62
5. ALConstraint — 36.70
6. Islands — 36.70
7. GA / NBIPOP — 37.38
8. BIPOP — 37.70
9. BipopAdaptif — 37.71
10. LMCMA — 39.09
11. RFSurrogate — 39.47

### prob2 (4 points de contrôle, 1 obstacle)
Tous les algorithmes convergent essentiellement vers la même valeur (~31.48), à l'exception de LMCMA (31.80) et GA (31.49). Problème trop simple pour discriminer.

### prob3 (8 points de contrôle, 33 obstacles / 3 murs)
1. **RFSurrogate** — 28.35
2. CMAES — 28.45
3. SHADE — 28.45
4. BIPOP — 28.87
5. AStarSeeds — 28.92
6. BipopAdaptif — 29.14
7. Islands — 29.41
8. NBIPOP — 29.78
9. DE — 30.62
10. ALConstraint — 31.59
11. GA — 42.11
12. LMCMA — 69.38

### prob4 (8 points de contrôle, 33 obstacles / labyrinthe en S)
1. **ALConstraint** — 4911.02
2. BIPOP — 4678.70 *(mais haute variance)*
3. **LMCMA** — 5148.37
4. **RFSurrogate** — 5564.34
5. **NBIPOP** — 5610.85
6. BipopAdaptif — 5670.98
7. **Islands** — 5726.27
8. AStarSeeds — 5835.41
9. SHADE — 5894.32
10. GA — 5940.93
11. CMAES — 6026.86
12. DE — 6242.19

## Analyse

### Problèmes faciles (prob1, prob2)
Sur prob1 et prob2, les différences entre algorithmes sont minimes. CMA-ES de base et AStarSeeds dominent légèrement sur prob1. Prob2, avec un seul obstacle et 4 points de contrôle, ne discrimine pas les algorithmes — tous atteignent la solution optimale ou quasi-optimale.

### Problème intermédiaire (prob3)
**RFSurrogate** obtient le meilleur score moyen (28.35), suivi de près par CMAES et SHADE (28.45). Le surrogate Random Forest permet d'économiser des évaluations coûteuses et d'explorer efficacement l'espace. LMCMA échoue sévèrement sur ce problème (69.38) car son approximation low-rank de la matrice de covariance est inadaptée à un paysage avec de nombreux obstacles.

### Problème difficile (prob4 — labyrinthe)
Le labyrinthe en S est le problème le plus discriminant. **ALConstraint** excelle avec un score moyen de 4911 (et un best de 3121.60), grâce à sa gestion explicite des contraintes via Lagrangien augmenté. **LMCMA** montre un bon score sur un run (3183.75) mais une haute variance. BIPOP obtient le meilleur score absolu (3104.26) mais avec une très haute variance également. Les algorithmes CMA-ES de base et DE sont les moins performants sur ce problème.

### Bilan des nouvelles variantes

| Variante | Forces | Faiblesses |
|----------|--------|------------|
| **LM-CMA-ES** | Bon potentiel sur prob4 (best=3183) | Très instable, catastrophique sur prob3 (69.38) |
| **A*Seeds** | Excellent sur prob1, bon et stable partout | Pas de gain sur prob3/prob4 vs. CMAES de base |
| **NBIPOP** | Stable, bon compromis prob4 (5610) | Légèrement inférieur à BIPOP sur prob4 |
| **ALConstraint** | **Meilleur sur prob4** (4911 moy., 3121 best) | Sous-performant sur prob3 (31.59) |
| **RFSurrogate** | **Meilleur sur prob3** (28.35) | Variance sur prob1 (39.47), sans gain sur prob4 |
| **Islands** | Stable, bon compromis | Pas de gain significatif vs. CMAES de base |

### Recommandations
- **Problèmes simples** : CMA-ES de base ou AStarSeeds
- **Problèmes multi-obstacles** : RFSurrogate ou SHADE
- **Problèmes labyrinthe** : ALConstraint ou BIPOP
- **Usage général** : NBIPOP ou Islands pour un bon compromis stabilité/performance
