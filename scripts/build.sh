#!/bin/bash

# ============================================
# VIBRANIUM ORDER BOOK - BUILD SCRIPT
# Automatiza build, test e docker
# ============================================

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}🚀 Vibranium Order Book - Build Script${NC}"
echo "========================================="

# ============================================
# 1. CLEAN
# ============================================
echo -e "\n${YELLOW}[1/5]${NC} Cleaning artifacts..."
mvn clean

# ============================================
# 2. BUILD LIBRARIES
# ============================================
echo -e "\n${YELLOW}[2/5]${NC} Building shared libraries..."
mvn install -pl libs/common-contracts,libs/common-utils -DskipTests

# ============================================
# 3. BUILD SERVICES
# ============================================
echo -e "\n${YELLOW}[3/5]${NC} Building microservices..."
mvn package -pl apps/order-service,apps/wallet-service -DskipTests

# ============================================
# 4. DOCKER IMAGES (Optional)
# ============================================
if [ "$1" == "--docker" ]; then
    echo -e "\n${YELLOW}[4/5]${NC} Building Docker images..."
    
    docker build -t order-service:latest -f apps/order-service/Dockerfile .
    docker build -t wallet-service:latest -f apps/wallet-service/Dockerfile .
    
    docker images | grep -E "order-service|wallet-service"
else
    echo -e "\n${YELLOW}[4/5]${NC} Skipping Docker build (use --docker flag)"
fi

# ============================================
# 5. SUMMARY
# ============================================
echo -e "\n${GREEN}✅ Build completed successfully!${NC}"
echo "========================================="
echo ""
echo -e "📦 Artifacts:"
echo "  - Order Service:  $(ls -lh apps/order-service/target/order-service-*.jar | awk '{print $NF}')"
echo "  - Wallet Service: $(ls -lh apps/wallet-service/target/wallet-service-*.jar | awk '{print $NF}')"
echo ""
echo -e "🚀 Next steps:"
echo "  1. Start infrastructure: docker-compose -f infra/docker-compose.dev.yml up -d"
echo "  2. Run Order Service:   mvn -pl apps/order-service spring-boot:run"
echo "  3. Run Wallet Service:  mvn -pl apps/wallet-service spring-boot:run"
echo ""
echo -e "🔗 Health checks:"
echo "  - Order Service:  curl http://localhost:8080/actuator/health"
echo "  - Wallet Service: curl http://localhost:8081/actuator/health"
echo ""
