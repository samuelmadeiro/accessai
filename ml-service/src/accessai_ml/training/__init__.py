"""Pipeline de treino do Modelo 1 (qualidade de texto alternativo).

O treino existe e funciona, mas NAO tem dado para rodar: a procedencia do
dataset (D2) segue como PROPOSTA em `docs/adr/0002-procedencia-do-dataset.md`, e
o corpus `.docx` tem zero textos alternativos. `train.py` recusa treinar sem
rotulo em vez de produzir um artefato que parece modelo e reporta metrica de
nada (CONTRIBUTING.md secao 1).
"""
