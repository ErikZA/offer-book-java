-- Vista de contratos de eventos para RabbitMQ

-- Como usar no Kong para validar JWTs:
-- 1. Instalar plugin jwt no Kong
-- 2. Configurar kong-jwt-config.json com a chave do Keycloak

[
  {
    "service": "order-service",
    "created_at": 1234567890,
    "id": "order-svc-id",
    "name": "order-service"
  },
  {
    "service": "wallet-service",
    "created_at": 1234567890,
    "id": "wallet-svc-id",
    "name": "wallet-service"
  }
]

-- ROTAS KONG (POST to Kong Admin API)
POST /services/order-service/routes
{
  "protocols": ["http", "https"],
  "hosts": ["orders.vibranium.local"],
  "paths": ["/api/v1/orders"],
  "strip_path": false,
  "preserve_host": true
}

POST /services/wallet-service/routes
{
  "protocols": ["http", "https"],
  "hosts": ["wallet.vibranium.local"],
  "paths": ["/api/v1/wallets"],
  "strip_path": false,
  "preserve_host": true
}

-- PLUGIN JWT no Kong (validar token Keycloak)
POST /routes/{route-id}/plugins
{
  "name": "jwt",
  "config": {
    "key_claim_name": "kid",
    "secret_is_base64": false
  }
}
