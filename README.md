# Optimisation de trajectoires par métaheuristiques

Projet de **Métaheuristiques** — Master 1 Informatique, Université de Lorraine (2025-2026).

> Optimiser une trajectoire 2D représentée par une **courbe de Bézier**, sous
> contraintes d'obstacles et de bornes, avec un budget de **60 secondes** par
> instance.

**Auteurs** : Naël Bensaadi, Linda Rahali

---

## Le problème

Étant donné un point de départ, un point d'arrivée et un ensemble d'obstacles
circulaires, on cherche les points de contrôle internes d'une courbe de Bézier
qui minimisent la fonction objectif fournie par le framework :

```
f(x) = L(x) + 100·P_obstacle(x) + P_courbure(x) + 100·P_bords(x)
```

avec `L` la longueur, `P_obstacle` la pénalité de collision, `P_courbure` la
régularité, et `P_bords` la sortie du domaine. La méthode `Problem.evaluate()`
est imposée par le sujet ; on optimise uniquement les points de contrôle internes.

## L'approche — `OptiPathFinal`

L'algorithme fait tourner **deux optimiseurs en parallèle** sur le même problème,
puis bascule sur le plus prometteur à mi-budget :

1. **L-SHADE** — Differential Evolution self-adaptif à population décroissante,
   mémoire historique des couples `(F, CR)` mise à jour par moyenne de Lehmer,
   mutation `current-to-pbest/1`, archive externe pour la diversité.
2. **CMA-ES IPOP** — Covariance Matrix Adaptation avec restarts à population
   croissante, échantillonnage par paires miroir (réduction de variance) et
   pénalité progressive sur les contraintes.

CMA-ES démarre depuis une **trajectoire A\*** calculée sur une grille 100×100
avec inflation des obstacles, puis ré-échantillonnée pour fournir une graine
géométriquement plausible. À `t = 25 s`, si exactement un des deux optimiseurs
a trouvé une solution faisable, l'autre est désactivé ; sinon les deux
continuent jusqu'à la fin du budget.

L'ensemble respecte les contraintes du sujet : une seule classe étend
`CompetitorProject`, l'initialisation lourde est dans `initialization()`,
aucun thread créé, aucune E/S pendant l'optimisation.

## Stack

- **Langage** : Java 11+
- **Dépendances** (dans `lib/`) : JFreeChart 1.5.5, JCommon 1.0.23,
  Reflections 0.9.12, Javassist 3.21
- **Framework** : code du cours dans `src/bezier/` (non modifié)

## Compilation & lancement

```bash
# Compiler
mkdir -p bin
javac -cp "lib/*" -d bin $(find src -name '*.java')

# Lancer le main du framework (60 s par problème, fenêtre Swing)
java -cp "bin:lib/*" bezier.run.Main
```

Le framework requiert un affichage graphique (Swing) — pas de mode headless.

## Structure du dépôt

```
.
├── src/                          Code Java
│   └── bezier/
│       ├── evaluation/           Framework (Problem, Bezier, charts) — non modifié
│       ├── output/               Logging
│       ├── projects/
│       │   └── competitor/
│       │       └── optipath/     OptiPathFinal et ses composants
│       │                         (L-SHADE, CMA-ES IPOP, sampling,
│       │                          contraintes, restarts…)
│       └── run/                  Entrée (Main)
├── lib/                          JARs (JFreeChart, Reflections, …)
├── data/                         Instances .bzr (prob1 → prob8)
├── rapport-metaheuristique.pdf   Rapport final
└── README.md
```

## Rapport

Les choix algorithmiques, l'état de l'art (DE/L-SHADE, CMA-ES/IPOP, A\*),
l'implémentation détaillée et l'analyse des résultats sont présentés dans
[`rapport-metaheuristique.pdf`](rapport-metaheuristique.pdf).
