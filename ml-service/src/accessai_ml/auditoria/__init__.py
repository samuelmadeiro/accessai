"""Auditoria de integridade do ecossistema de dados.

Camada separada de `dataset` e de `training` de proposito: ela IMPORTA as duas
para conferi-las, e um modulo auditado que importasse o auditor fecharia um
ciclo em que o objeto medido escolhe a regua.
"""
