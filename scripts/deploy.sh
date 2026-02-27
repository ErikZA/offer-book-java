#!/bin/bash

# ============================================
# VIBRANIUM ORDER BOOK - DEPLOY SCRIPT
# Gerencia stack Docker em diferentes ambientes
# ============================================

set -e

ENVIRONMENT="${1:-dev}"
ACTION="${2:-up}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_usage() {
    echo -e "${YELLOW}Usage:${NC}"
    echo "  ./scripts/deploy.sh {dev|staging} {up|down|logs|ps}"
    echo ""
    echo -e "${YELLOW}Examples:${NC}"
    echo "  ./scripts/deploy.sh dev up         # Start dev stack"
    echo "  ./scripts/deploy.sh dev logs       # Show dev logs"
    echo "  ./scripts/deploy.sh staging down   # Stop staging stack"
}

if [ "$ENVIRONMENT" != "dev" ] && [ "$ENVIRONMENT" != "staging" ]; then
    echo -e "${RED}❌ Invalid environment: $ENVIRONMENT${NC}"
    print_usage
    exit 1
fi

COMPOSE_FILE="infra/docker-compose.${ENVIRONMENT}.yml"

if [ ! -f "$COMPOSE_FILE" ]; then
    echo -e "${RED}❌ Compose file not found: $COMPOSE_FILE${NC}"
    exit 1
fi

echo -e "${BLUE}=========================================${NC}"
echo -e "${BLUE}Vibranium Order Book - Deploy Script${NC}"
echo -e "${BLUE}Environment: ${YELLOW}$ENVIRONMENT${NC}"
echo -e "${BLUE}Action: ${YELLOW}$ACTION${NC}"
echo -e "${BLUE}=========================================${NC}"

case $ACTION in
    up)
        echo -e "\n${YELLOW}🚀 Starting $ENVIRONMENT stack...${NC}"
        docker-compose -f "$COMPOSE_FILE" up -d
        sleep 5
        echo -e "\n${YELLOW}⏳ Waiting for healthchecks...${NC}"
        sleep 10
        docker-compose -f "$COMPOSE_FILE" ps
        echo -e "\n${GREEN}✅ Stack started!${NC}"
        
        if [ "$ENVIRONMENT" == "dev" ]; then
            echo -e "\nAccess points:"
            echo "  Order Service: http://localhost:8080"
            echo "  Wallet Service: http://localhost:8081"
            echo "  Kong Gateway: http://localhost:8000"
            echo "  Keycloak: http://localhost:8180 (admin/admin123)"
            echo "  RabbitMQ: http://localhost:15672 (guest/guest)"
        fi
        ;;
    down)
        echo -e "\n${YELLOW}🛑 Stopping $ENVIRONMENT stack...${NC}"
        docker-compose -f "$COMPOSE_FILE" down
        echo -e "${GREEN}✅ Stack stopped!${NC}"
        ;;
    logs)
        SERVICE="${3:-}"
        if [ -z "$SERVICE" ]; then
            echo -e "\n${YELLOW}📋 Showing all logs (Ctrl+C to exit)...${NC}"
            docker-compose -f "$COMPOSE_FILE" logs -f
        else
            echo -e "\n${YELLOW}📋 Showing logs for $SERVICE (Ctrl+C to exit)...${NC}"
            docker-compose -f "$COMPOSE_FILE" logs -f "$SERVICE"
        fi
        ;;
    ps)
        echo -e "\n${YELLOW}📊 Stack status:${NC}"
        docker-compose -f "$COMPOSE_FILE" ps
        ;;
    *)
        echo -e "${RED}❌ Unknown action: $ACTION${NC}"
        print_usage
        exit 1
        ;;
esac
