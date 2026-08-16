# Build em dois estagios: o jar sai de uma imagem com Maven e JDK 25, e roda
# numa imagem so com JRE. O runtime nao carrega compilador nem cache do Maven.
#
# ATENCAO ao contexto: este Dockerfile e construido a partir da RAIZ do
# repositorio, nao de backend/. O motivo e docs/wcag/criteria.json, que o
# pom.xml inclui como resource. A tabela WCAG e contrato do projeto (CLAUDE.md
# secao 6) e vive fora do modulo de proposito; copiar uma segunda copia para
# dentro de backend/ criaria duas fontes de verdade que divergem sozinhas.

FROM maven:3.9.16-eclipse-temurin-25-noble AS build
WORKDIR /repo

# O pom vem primeiro e sozinho: enquanto ele nao mudar, a camada de download
# fica em cache e o rebuild nao baixa a internet de novo.
COPY backend/pom.xml backend/pom.xml
WORKDIR /repo/backend
RUN mvn -B -q dependency:go-offline

WORKDIR /repo
COPY docs/wcag docs/wcag
COPY backend/src backend/src

WORKDIR /repo/backend
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25.0.3_9-jre-noble AS runtime
WORKDIR /app

# curl e usado pelo healthcheck do compose.
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

# Nao rodar como root.
RUN useradd --system --create-home --uid 10001 accessai
USER accessai

COPY --from=build /repo/backend/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
