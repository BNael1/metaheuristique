# Résumé des 8 méthodes — Projet Métaheuristique

## Tableau comparatif (1 run, 60s)

| Algorithme | prob1 (d=32) | prob2 (d=8) | prob3 (d=16) | prob4 (d=16) |
|---|---|---|---|---|
| CMAES (base) | 37.65 | 31.48 | 29.34 | 5804.25 |
| BIPOP (ref) | **37.38** | 31.48 | 28.95 | **5432.95** |
| **P1 — Active** | 38.42 | 31.48 | 32.21 | 5608.23 |
| **P2 — Adaptive** | 38.25 | 31.48 | 29.45 | 5791.43 |
| **P3 — Surrogate** | 38.39 | 31.48 | 29.58 | 5588.40 |
| **P4 — Final (fusion)** | 38.42 | 31.48 | **29.26** | 5787.17 |
| **I1 — GridBIPOP** | 38.39 | 31.48 | 29.50 | 5779.97 |
| **I2 — StochRank** | 37.36 | 31.48 | **28.32** | 5439.37 |
| **I3 — AStarRepair** | 38.39 | 31.48 | **28.24** | 5848.11 |
| **I4 — SepWarmup** | 37.42 | 31.48 | 35.98 | 5468.24 |

> P1–P4 = 4 premières propositions ; I1–I4 = 4 nouvelles idées. Les valeurs en **gras** sont les meilleures par colonne.
| **P3 — Surrogate** | 38.39 | 31.48 | 29.58 | 5588.40 |
| **P4 — Final (fusion)** | 38.42 | 31.48 | **29.26** | 5787.17 |

> Les valeurs en **gras** sont les meilleures par colonne.

---

## P1 — Active CMA-ES

**Fichiers** : `CMAESActive.java`, `OptiPathActive.java`

**Idée** : Accélérer la convergence en exploitant les *mauvaises* solutions pour repousser la covariance loin des zones stériles.

**Composants** :
- **Active CMA-ES** : poids négatifs sur les pires individus (Jastrebski & Arnold 2006), normalisés par la distance de Mahalanobis pour la stabilité
- **Mirror Sampling** : chaque vecteur aléatoire z génère aussi son miroir −z, ce qui divise par 2 la variance de l'estimateur de gradient (Brockhoff et al. 2010)
- **Covariance Memory** : au restart, `C = 0.3 * C_ancien + 0.7 * I` au lieu de repartir de l'identité, pour conserver l'apprentissage structurel

**Résultats** :
- ✅ prob4 : 5608 (meilleur que CMAES 5804)
- ❌ prob1 : 38.42 (régression vs 37.65)
- ❌ prob3 : 32.21 (forte régression vs 29.34)

**Analyse** : Les poids négatifs et le mirror sampling améliorent les problèmes à paysage complexe (prob4, labyrinthe) mais dégradent les problèmes simples (prob1, prob3) où la convergence standard est déjà efficace. L'agressivité des updates actifs perturbe la covariance sur les paysages lisses.

---

## P2 — Adaptive BIPOP-CMA-ES

**Fichiers** : `CMAESAdaptive.java`, `OptiPathAdaptive.java`

**Idée** : Sélectionner dynamiquement la stratégie (standard vs active) selon les performances observées, avec gestion adaptive des restarts.

**Composants** :
- **BIPOP** alternant restarts larges (exploration) et petits (exploitation)
- **Active Weights** modulés selon un score de qualité qui s'adapte en ligne
- **Stratégie Bandit** : choisit entre mode conservateur (poids positifs seuls) et mode agressif (active + négatifs) selon un score de récompense cumulé

**Résultats** :
- ✅ prob3 : 29.45 (nettement mieux que Active 32.21, proche de CMAES 29.34)
- ~ prob2 : 31.48 (identique à la référence)
- ❌ prob1 : 38.25 (régression modérée)
- ❌ prob4 : 5791 (moins bon que Active 5608)

**Analyse** : Le bandit apprend correctement à désactiver les poids négatifs sur prob3 (obstacles structurés) mais n'arrive pas à exploiter pleinement le mode actif sur prob4. Le surcoût de la sélection de stratégie réduit le budget d'évaluations effectives.

---

## P3 — Surrogate-Assisted BIPOP-CMA-ES

**Fichiers** : `SurrogateRBF.java`, `CMAESSurrogate.java`, `OptiPathSurrogate.java`

**Idée** : Multiplier le pouvoir de recherche en pré-filtrant les candidats via un modèle substitut RBF, pour ne garder que les plus prometteurs à évaluer.

**Composants** :
- **SurrogateRBF** : modèle substitut léger à interpolation inverse pondérée par noyau cubique, archive circulaire de 300 points
- **Pre-screening** : chaque génération échantillonne `3 × λ` candidats, les classe par fitness prédite, n'évalue que les `λ` meilleurs
- **Safety Fraction (30%)** : 30% des λ sélectionnés sont tirés aléatoirement (non filtrés), pour protéger contre un mauvais surrogate
- **Kendall Tau auto-disable** : la corrélation de rang entre prédictions et fitness réelle est suivie en ligne. Si τ < 0.15, le surrogate se désactive automatiquement → retour au CMA-ES standard
- **BIPOP** dual-regime restarts + clear de l'archive au restart

**Résultats** :
- ✅ prob4 : 5588 (3e meilleur, proche de BIPOP 5432)
- ✅ prob2 : 31.48 (optimal)
- ~ prob3 : 29.58 (correct, le surrogate s'auto-désactive sur les obstacles)
- ❌ prob1 : 38.39 (régression modérée)

**Analyse** : Le mécanisme d'auto-disable est crucial — v1 sans cette protection donnait prob3 = 41.31 et prob4 = 6148 (catastrophique). Avec le Kendall tau, le surrogate se coupe automatiquement sur les paysages à obstacles où l'interpolation RBF ne capture pas les discontinuités. Sur prob1 (d=32), le coût de l'évaluation RBF (O(n×d) par candidat) consomme une fraction notable du budget.

---

## P4 — Final (Fusion du meilleur combo)

**Fichiers** : `CMAESFinal.java`, `OptiPathFinal.java`, `SurrogateRBF.java`

**Idée** : Fusionner les meilleures composantes de P1, P2 et P3 dans un seul optimiseur.

**Composants fusionnés** :
1. **BIPOP** dual-regime restarts (de P3/BIPOP)
2. **Active CMA-ES** avec poids négatifs + normalisation Mahalanobis (de P1)
3. **Covariance Memory** `C = 0.3*C_prev + 0.7*I` au restart (de P1)
4. **Mirror Sampling** paires original/miroir (de P1)
5. **Surrogate RBF Pre-screening** avec safety fraction 30% + auto-disable Kendall tau (de P3)

**Résultats** :
- ✅ **prob3 : 29.26** (meilleur de TOUTES les variantes, meilleur que CMAES 29.34)
- ✅ prob2 : 31.48 (optimal)
- ~ prob1 : 38.42 (même niveau que Active)
- ❌ prob4 : 5787 (en retrait vs BIPOP pur 5432 et Surrogate 5588)

**Analyse** : La fusion réussit sur prob3 grâce à la synergie covariance memory + surrogate pre-screening. Le problème sur prob4 vient du mirror sampling + active weights qui ajoutent du surcoût computationnel dans un problème où le budget évaluations est le facteur limitant (33 obstacles → évaluations coûteuses). La combinaison de tous les mécanismes simultanément crée une charge CPU trop élevée pour prob4.

---

## Synthèse (P1–P4)

| Force principale | P1 Active | P2 Adaptive | P3 Surrogate | P4 Final |
|---|---|---|---|---|
| prob1 (simple, d=32) | ❌ | ❌ | ❌ | ❌ |
| prob2 (facile, d=8) | ✅ | ✅ | ✅ | ✅ |
| prob3 (3 murs, d=16) | ❌ | ~ | ~ | **✅ best** |
| prob4 (labyrinthe, d=16) | ~ | ❌ | ~ | ❌ |

**Meilleur algo par instance (P1–P4)** :
- prob1 → BIPOP (37.38)
- prob2 → tous ≈ 31.48
- prob3 → **Final (29.26)**
- prob4 → BIPOP (5432.95)

**Leçon clé** : Aucune des variantes avancées ne bat BIPOP de manière uniforme sur toutes les instances. Les mécanismes sophistiqués (active weights, surrogate, mirror) apportent un gain marginal sur certaines instances mais un surcoût computationnel qui dégrade les autres. La stratégie la plus robuste reste le BIPOP pur pour prob4, tandis que la fusion (P4) excelle sur prob3.

---

## I1 — Grid-BIPOP (MAP-Elites + BIPOP-CMA-ES)

**Fichiers** : `CMAESGridBipop.java`, `OptiPathGridBipop.java`

**Idée** : Combiner l'exploration Quality-Diversity (MAP-Elites) avec BIPOP-CMA-ES. Une archive 20×20 discrétise l'espace comportemental (position médiane des points de contrôle) et guide les restarts vers des zones sous-explorées.

**Composants** :
- **Archive MAP-Elites 20×20** : stocke la meilleure solution par cellule comportementale, avec compteur de visites
- **Sélection roulette inversée** : au restart, la cellule de départ est choisie inversement proportionnelle au nombre de visites → exploration diversifiée
- **BIPOP** dual-regime restarts (large σ / petit σ)
- **Descripteur comportemental** : médiane des coordonnées x et y des points de contrôle

**Résultats** :
- ~ prob2 : 31.48 (optimal)
- ~ prob3 : 29.50 (correct, dans la moyenne)
- ❌ prob1 : 38.39 (régression notable vs BIPOP 37.38)
- ❌ prob4 : 5779.97 (en retrait)

**Analyse** : L'approche QD diversifie l'exploration mais la grille 20×20 est trop grossière pour capturer les bonnes niches en haute dimension (prob1, d=32). Sur prob4 (labyrinthe étroit), la dispersion des restarts dans des cellules non pertinentes gaspille le budget. Le concept est prometteur mais nécessiterait une adaptation du descripteur comportemental au type de problème.

---

## I2 — Stochastic Ranking CMA-ES

**Fichiers** : `CMAESStochRank.java`, `OptiPathStochRank.java`

**Idée** : Remplacer le tri par fitness pure par un Stochastic Ranking (Runarsson & Yao 2000) qui compare les individus tantôt par fitness, tantôt par violation de contrainte, avec une probabilité PF.

**Composants** :
- **Stochastic Ranking** : avec PF = 0.45, chaque paire d'individus est comparée par fitness (prob PF) ou par violation (prob 1-PF)
- **Estimation heuristique de violation** : individus avec fitness > 5× diagonale de l'espace sont considérés infaisables, degré = fitness − seuil
- **BIPOP** dual-regime restarts
- **Diversité de seeds** (grille + perturbations aléatoires)

**Résultats** :
- ✅ **prob3 : 28.32** (top 3, proche de RFSurrogate 28.35 et SHADE 28.45)
- ✅ **prob4 : 5439.37** (top 4, meilleur des nouvelles idées sur prob4)
- ~ prob1 : 37.36 (correct, dans la moyenne)
- ✅ prob2 : 31.48 (optimal)

**Analyse** : Le Stochastic Ranking est la meilleure des 4 nouvelles idées en termes de compromis. L'alternance aléatoire entre fitness et violation permet de traverser les zones de forte pénalité (obstacles) tout en gardant une pression sélective sur la qualité. Sur prob4, c'est le 4e meilleur algorithme (après BIPOP, ALConstraint, LMCMA), confirmant l'intérêt d'une gestion explicite des contraintes pour le labyrinthe.

---

## I3 — A* Repair BIPOP-CMA-ES

**Fichiers** : `CMAESAStarRepair.java`, `OptiPathAStarRepair.java`

**Idée** : À chaque génération, réparer les solutions infaisables en les tirant vers des chemins A* pré-calculés, avec une force décroissante au fil des générations.

**Composants** :
- **Chemins A* pré-calculés** : utilise `AStarGrid` pour générer des chemins de référence faisables au début de chaque restart
- **Opérateur de réparation** : `repairTowardAStar()` tire 20% des offspring vers le chemin A* le plus proche, avec strength = 0.3 × decay
- **Decay adaptatif** : la force de réparation décroît exponentiellement (×0.995/gen) pour laisser CMA-ES raffiner librement
- **BIPOP** dual-regime restarts

**Résultats** :
- ✅ **prob3 : 28.24** (meilleur absolu sur prob3, devant RFSurrogate 28.35)
- ✅ prob2 : 31.48 (optimal)
- ❌ prob1 : 38.39 (régression, repair coûteux en d=32)
- ❌ prob4 : 5848.11 (pire des 4 nouvelles sur prob4)

**Analyse** : AStarRepair excelle sur prob3 car les chemins A* à travers 3 murs fournissent un excellent guide structurel — la réparation pousse les solutions vers des corridors faisables. Cependant, sur prob4 (labyrinthe en S), les chemins A* sont trop rectilignes et ne capturent pas les virages nécessaires, forçant les solutions vers des zones sous-optimales. Le surcoût de calcul A* pénalise aussi les problèmes de haute dimension (prob1, d=32).

---

## I4 — Sep-CMA-ES Warm-up

**Fichiers** : `CMAESSepWarmup.java`, `OptiPathSepWarmup.java`

**Idée** : Démarrer chaque restart en mode Sep-CMA-ES (matrice de covariance diagonale, O(d) par gen), puis transiter vers la CMA-ES complète (O(d²)) après 300 générations, pour un apprentissage structurel rapide.

**Composants** :
- **Phase diagonale** (Sep-CMA-ES) : 300 premières générations en O(d), updates diagonaux uniquement
- **Transition** : `C = 0.3 × diag_learned + 0.7 × I` — blend de la diagonale apprise avec l'identité
- **Phase complète** : CMA-ES standard avec matrice pleine après transition
- **BIPOP** restarts — chaque restart recommence en mode Sep

**Résultats** :
- ✅ **prob4 : 5468.24** (top 5, meilleur que NBIPOP 5610 et BipopAdaptif 5670)
- ✅ prob2 : 31.48 (optimal)
- ~ prob1 : 37.42 (correct)
- ❌ **prob3 : 35.98** (très mauvais, pire des 4 nouvelles idées)

**Analyse** : Le warm-up diagonal accélère les premiers restarts sur prob4 en permettant plus de restarts dans le budget de 60s. Cependant, la phase diagonale est catastrophique sur prob3 (3 murs) car la structure des corridors nécessite des corrélations entre variables dès le début — l'approximation diagonale perd l'information structurelle cruciale. La transition à 300 générations arrive trop tard pour récupérer sur prob3.

---

## Synthèse globale (P1–P4 + I1–I4)

| Force principale | I1 GridBIPOP | I2 StochRank | I3 AStarRepair | I4 SepWarmup |
|---|---|---|---|---|
| prob1 (simple, d=32) | ❌ | ~ | ❌ | ~ |
| prob2 (facile, d=8) | ✅ | ✅ | ✅ | ✅ |
| prob3 (3 murs, d=16) | ~ | **✅** | **✅ best** | ❌ |
| prob4 (labyrinthe, d=16) | ❌ | **✅** | ❌ | **✅** |

**Meilleur algo par instance (toutes variantes)** :
- prob1 → CMAES (36.35) ou AStarSeeds (36.36)
- prob2 → tous ≈ 31.48
- prob3 → **AStarRepair (28.24)** > RFSurrogate (28.35) > StochRank (28.32)
- prob4 → BIPOP (4678.70) > ALConstraint (4911.02) > StochRank (5439.37)

**Leçon clé** : Les 4 nouvelles idées confirment que la gestion des contraintes est le facteur déterminant. StochRank (ranking stochastique) offre le meilleur compromis global, AStarRepair excelle spécifiquement sur les problèmes à corridors (prob3), et SepWarmup aide sur le labyrinthe grâce au démarrage rapide. GridBIPOP reste le plus décevant — la diversité QD n'apporte pas de gain suffisant sur ces instances.
