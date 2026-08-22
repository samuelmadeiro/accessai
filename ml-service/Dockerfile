# Imagem do servico de inferencia.
#
# Estagio unico, ao contrario do backend: nao ha compilacao a descartar. O que
# reduz a imagem aqui e nao instalar as dependencias de desenvolvimento (pytest,
# ruff, mypy) — elas nao vao para producao.

FROM python:3.13-slim-bookworm

# Sem .pyc no disco e sem buffer na saida: o log do container precisa aparecer
# quando acontece, nao quando o buffer enche.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# O pyproject vem primeiro e sozinho: enquanto ele nao mudar, a camada de
# instalacao fica em cache e o rebuild nao baixa scikit-learn de novo.
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-compile .

# A pasta existe mesmo vazia. Sem artefato dentro, o servico sobe e responde
# pela heuristica, marcando usouHeuristica=true — ver src/accessai_ml/inference.
RUN mkdir -p /app/models

# Nao rodar como root.
RUN useradd --system --create-home --uid 10001 accessai
USER accessai

ENV ACCESSAI_MODELOS=/app/models

EXPOSE 8000

CMD ["uvicorn", "accessai_ml.inference.main:app", "--host", "0.0.0.0", "--port", "8000"]
