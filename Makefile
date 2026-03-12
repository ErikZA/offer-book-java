.PHONY: help docker-build docker-dev-up docker-dev-down docker-dev-logs docker-test docker-prod-up docker-prod-down docker-status docker-clean

DOCKER_COMPOSE_DEV := docker compose --env-file .env -f infra/docker-compose.dev.yml
DOCKER_COMPOSE_TEST := docker compose -f tests/e2e/docker-compose.e2e.yml
DOCKER_COMPOSE_PROD := docker compose --env-file .env -f infra/docker-compose.yml

# Colors for output
RED := \033[0;31m
GREEN := \033[0;32m
YELLOW := \033[1;33m
BLUE := \033[0;34m
NC := \033[0m

help:
	@echo "$(BLUE)╔════════════════════════════════════════╗$(NC)"
	@echo "$(BLUE)║   VIBRANIUM - Docker Commands         ║$(NC)"
	@echo "$(BLUE)║   💡 All work via Docker              ║$(NC)"
	@echo "$(BLUE)╚════════════════════════════════════════╝$(NC)"
	@echo ""
	@echo "$(YELLOW)🐳 Docker Development (with hotreload):$(NC)"
	@echo "  make docker-dev-up      - Start dev environment"
	@echo "  make docker-dev-down    - Stop dev environment"
	@echo "  make docker-dev-logs    - View logs (SERVICE=order-service)"
	@echo ""
	@echo "$(YELLOW)🧪 Testing:$(NC)"
	@echo "  make docker-test        - Run all tests in containers"
	@echo ""
	@echo "$(YELLOW)🐳 Build & Production:$(NC)"
	@echo "  make docker-build       - Build Docker images"
	@echo "  make docker-prod-up     - Start production environment"
	@echo "  make docker-prod-down   - Stop production environment"
	@echo ""
	@echo "$(YELLOW)⚙️  Utilities:$(NC)"
	@echo "  make docker-status      - Show running containers"
	@echo "  make docker-clean       - Clean all containers/images"
	@echo ""

docker-build:
	@echo "$(YELLOW)🐳 Building Docker images...$(NC)"
	docker build -f apps/order-service/docker/Dockerfile -t vibranium-order-service:latest .
	docker build -f apps/wallet-service/docker/Dockerfile -t vibranium-wallet-service:latest .
	@echo "$(GREEN)✓ Docker images built$(NC)"
	@docker images | grep vibranium || echo "No vibranium images found"

docker-dev-up:
	@if [ ! -f .env ]; then echo "$(RED)ERROR: .env not found! Run: cp .env.example .env$(NC)"; exit 1; fi
	@echo "$(YELLOW)🚀 Starting development environment with hotreload...$(NC)"
	$(DOCKER_COMPOSE_DEV) up -d
	@echo "$(GREEN)✓ Dev environment started$(NC)"
	@echo "$(BLUE)Services:$(NC)"
	@echo "  Order Service: http://localhost:8080 (Debug port: 5005)"
	@echo "  Wallet Service: http://localhost:8081 (Debug port: 5006)"
	@echo "$(YELLOW)📝 Tip: Run 'make docker-dev-logs SERVICE=order-service' to view logs$(NC)"

docker-dev-down:
	@echo "$(YELLOW)🛑 Stopping development environment...$(NC)"
	$(DOCKER_COMPOSE_DEV) down
	@echo "$(GREEN)✓ Dev environment stopped$(NC)"

docker-dev-logs:
	@if [ -z "$(SERVICE)" ]; then \
		echo "$(RED)ERROR: SERVICE required$(NC)"; \
		echo "Usage: make docker-dev-logs SERVICE=order-service"; \
		exit 1; \
	fi
	@echo "$(YELLOW)📋 Showing logs for $(SERVICE)...$(NC)"
	$(DOCKER_COMPOSE_DEV) logs -f $(SERVICE)

docker-test:
	@echo "$(YELLOW)🧪 Running tests in Docker...$(NC)"
	$(DOCKER_COMPOSE_TEST) up --abort-on-container-exit
	@echo "$(YELLOW)🧹 Cleaning test environment...$(NC)"
	$(DOCKER_COMPOSE_TEST) down -v
	@echo "$(GREEN)✓ Tests completed$(NC)"

docker-prod-up:
	@if [ ! -f .env ]; then echo "$(RED)ERROR: .env not found! Run: cp .env.example .env$(NC)"; exit 1; fi
	@echo "$(YELLOW)🚀 Starting production environment...$(NC)"
	$(DOCKER_COMPOSE_PROD) up -d
	@echo "$(GREEN)✓ Production environment started$(NC)"
	@echo "$(BLUE)Containers:$(NC)"
	@docker ps | grep -i vibranium || echo "No vibranium containers running"

docker-prod-down:
	@echo "$(YELLOW)🛑 Stopping production environment...$(NC)"
	$(DOCKER_COMPOSE_PROD) down
	@echo "$(GREEN)✓ Production environment stopped$(NC)"

docker-status:
	@echo "$(YELLOW)📊 Container Status:$(NC)"
	@docker ps --all --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -i vibranium || echo "No vibranium containers found"

docker-clean:
	@echo "$(YELLOW)🧹 Cleaning Docker artifacts...$(NC)"
	$(DOCKER_COMPOSE_DEV) down -v
	$(DOCKER_COMPOSE_TEST) down -v
	$(DOCKER_COMPOSE_PROD) down -v
	@echo "$(GREEN)✓ Clean completed$(NC)"

	rm -rf target/
	@echo "$(GREEN)✓ Clean completed$(NC)"

