# Dockerfile para execução de testes
FROM maven:3.9-eclipse-temurin-21-alpine

WORKDIR /app

# Instalar curl para verificações
RUN apk add --no-cache curl

# Copiar pom.xml raiz primeiro (cache layer)
COPY pom.xml ./

# Copiar pom.xml de cada módulo (cache layer)
COPY apps/order-service/pom.xml ./apps/order-service/
COPY apps/wallet-service/pom.xml ./apps/wallet-service/
COPY libs/common-contracts/pom.xml ./libs/common-contracts/

# Baixar todas as dependências antecipadamente
RUN mvn dependency:resolve dependency:resolve-plugins -DskipTests -q 2>/dev/null || true

# Copiar código-fonte
COPY apps ./apps
COPY libs ./libs

# Executar testes
# O comando padrão pode ser sobrescrito no docker-compose
CMD ["mvn", "clean", "test", "-U"]
