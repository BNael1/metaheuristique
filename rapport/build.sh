#!/bin/bash
# Build du rapport PDF (à exécuter depuis le dossier rapport/)
cd "$(dirname "$0")"

mkdir -p build

pdflatex -output-directory=build -interaction=nonstopmode rapport.tex
pdflatex -output-directory=build -interaction=nonstopmode rapport.tex   # 2e passe pour les références
echo "=> build/rapport.pdf généré"
