# MH Project - Agent Instructions (FR/EN)

## 1) Portee / Scope

FR:
- Ce document definit les regles operationnelles pour travailler sur le projet d'optimisation de trajectoires Bezier.
- Les contraintes de l'enonce priment sur toute preference d'implementation.
- Les choix algorithmiques de la section OptiPath sont des recommandations fortes, pas des obligations absolues, sauf mention explicite MUST.

EN:
- This document defines operational rules for working on the Bezier trajectory optimization project.
- Assignment constraints override any implementation preference.
- OptiPath choices are strong recommendations, not absolute obligations, unless explicitly marked MUST.

---

## 2) Regles MUST (imperatives) / Mandatory MUST Rules

FR:
- MUST utiliser Java (implementation et execution du projet).
- MUST respecter le budget de 60 secondes en temps wall-clock pour chaque run.
- MUST executer les algorithmes en thread unique (pas de parallelisme intra-algorithme).
- MUST evaluer la performance sur moyenne de 10 executions independantes.
- MUST reinitialiser le probleme avant chaque run via problem.reset().
- MUST collecter le meilleur score via problem.getBestEvaluation().
- MUST utiliser l'API Problem pour l'evaluation (pas de calcul manuel de fitness en remplacement).
- MUST respecter la fonction de cout officielle:
  f(x) = L(x) + 100*P_obs(x) + 1*P_courb(x) + 100*P_bord(x)
- MUST conserver les ponderations imposees: alpha=100, beta=1, gamma=100.
- MUST traiter les variables de decision comme les seuls points de controle internes (depart/arrivee fixes).
- MUST respecter les bornes de l'instance pour le probleme officiel (les extensions de bornes restent une strategie, pas une redefinition du probleme).
- MUST considerer 4 instances par evaluation.
- MUST considerer que ces 4 instances peuvent etre aleatoires et ne pas supposer un jeu fixe (ex: prob1, prob2, prob3, prob4).

EN:
- MUST use Java (implementation and execution context).
- MUST respect the 60-second wall-clock budget for each run.
- MUST run in single-thread mode (no intra-algorithm parallelism).
- MUST evaluate performance over 10 independent runs.
- MUST reset the problem before each run with problem.reset().
- MUST collect the best score with problem.getBestEvaluation().
- MUST use the Problem API for evaluation (no manual fitness replacement).
- MUST respect the official cost function:
  f(x) = L(x) + 100*P_obs(x) + 1*P_courb(x) + 100*P_bord(x)
- MUST keep required weights: alpha=100, beta=1, gamma=100.
- MUST optimize only internal control points (start/end are fixed).
- MUST respect instance domain bounds for the official problem (bound extension is a strategy, not a problem redefinition).
- MUST account for 4 instances in each evaluation batch.
- MUST assume those 4 instances can be random and avoid hardcoding a fixed set (e.g., prob1, prob2, prob3, prob4).

---

## 3) SHOULD (fortement recommande) / Strong SHOULD Guidance

FR:
- SHOULD privilegier la robustesse moyenne (10 runs) plutot qu'un run exceptionnel.
- SHOULD utiliser un seeding structure + random (grille, motifs, sinus, aleatoire) pour couvrir l'espace.
- SHOULD utiliser une phase locale courte sur les meilleurs seeds avant optimisation globale.
- SHOULD utiliser une strategie CMA-ES/BIPOP pour les cas correles et difficiles.
- SHOULD envisager un portfolio de configurations (ex: marges differentes) avec allocation de temps claire.
- SHOULD mettre un mecanisme de restart adaptatif pour les cas de stagnation (notamment prob4).
- SHOULD monitorer explicitement la contrainte temps a chaque boucle.

EN:
- SHOULD prioritize robust average performance (10 runs) over one exceptional run.
- SHOULD use mixed structured + random seeding (grid, patterns, sinusoidal, random) for space coverage.
- SHOULD run a short local refinement phase on top seeds before global optimization.
- SHOULD favor CMA-ES/BIPOP-style strategy for correlated and difficult instances.
- SHOULD consider a portfolio of configurations (e.g., different margins) with clear time allocation.
- SHOULD implement adaptive restart behavior for stagnation cases (especially prob4).
- SHOULD monitor the time budget explicitly in each loop.

---

## 4) AVOID (a ne pas faire) / Things to AVOID

FR:
- AVOID depasser 60s, meme legerement.
- AVOID reutiliser un etat interne entre runs independants.
- AVOID changer alpha/beta/gamma de la fonction de cout officielle.
- AVOID contourner l'API Problem pour "simuler" des evaluations incompatibles.
- AVOID introduire du parallelisme intra-algorithme.
- AVOID fonder les conclusions sur un seul run.
- AVOID modifier le framework professeur dans src/bezier si non requis.

EN:
- AVOID exceeding the 60s budget, even slightly.
- AVOID carrying internal state across independent runs.
- AVOID changing alpha/beta/gamma in the official cost function.
- AVOID bypassing the Problem API with incompatible custom evaluation logic.
- AVOID introducing intra-algorithm parallelism.
- AVOID drawing conclusions from a single run.
- AVOID modifying the teacher framework in src/bezier unless explicitly required.

---

## 5) Workflow agent avant action / Agent Pre-Action Workflow

FR:
1. Verifier l'objectif demande (run unique, benchmark, comparaison, tuning, export).
2. Verifier la zone de modification (preferer engine/, bench/, projects/competitor).
3. Valider les contraintes fixes: Java, 60s wall-clock, thread unique, 10 runs, evaluation sur 4 instances.
4. Verifier que reset/getBestEvaluation sont correctement utilises.
5. Confirmer que la metrique raportee est bien la moyenne sur 10 runs.
6. Documenter les hypothese si une regle est ambigue.

EN:
1. Confirm the requested goal (single run, benchmark, comparison, tuning, export).
2. Confirm safe edit area (prefer engine/, bench/, projects/competitor).
3. Validate fixed constraints: Java, 60s wall-clock, single-thread, 10 runs, evaluation on 4 instances.
4. Ensure reset/getBestEvaluation usage is correct.
5. Ensure the reported metric is the average over 10 runs.
6. Document assumptions whenever a rule is ambiguous.

---

## 6) Checklist rapide / Quick Checklist

FR:
- [ ] Java uniquement
- [ ] Budget 60s wall-clock respecte
- [ ] Thread unique
- [ ] problem.reset() avant chaque run
- [ ] problem.getBestEvaluation() pour le score
- [ ] 10 runs independants
- [ ] Evaluation sur 4 instances (potentiellement aleatoires)
- [ ] Moyenne finale correctement calculee
- [ ] Formule officielle et ponderations intactes
- [ ] Aucune modification non justifiee du framework src/bezier

EN:
- [ ] Java only
- [ ] 60s wall-clock budget respected
- [ ] Single-thread execution
- [ ] problem.reset() before each run
- [ ] problem.getBestEvaluation() used for score
- [ ] 10 independent runs
- [ ] Evaluation on 4 instances (potentially random)
- [ ] Final average computed correctly
- [ ] Official formula and weights unchanged
- [ ] No unjustified edits in src/bezier framework

---

## 7) Notes de conformite / Compliance Notes

FR:
- Ce document est un guide operationnel derive du rapport mh_projet.pdf.
- En cas de conflit, appliquer en priorite l'enonce/protocole d'evaluation officiel.

EN:
- This document is an operational guide derived from mh_projet.pdf.
- If a conflict appears, always prioritize the official assignment/evaluation protocol.
