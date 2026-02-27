#!/bin/bash

# Script para iniciar o Keycloak e configurar realm/clients

# 1. Criar realm "vibranium"
#    Nome: vibranium
#    Enabled: true

# 2. Criar cliente "api-gateway"
#    Client ID: api-gateway
#    Client Protocol: openid-connect
#    Access Type: public
#    Valid Redirect URIs: http://localhost:8000/*
#    Web Origins: http://localhost:8000

# 3. Criar cliente "order-service"
#    Client ID: order-service
#    Client Protocol: openid-connect
#    Access Type: confidential
#    Service Accounts Enabled: true

# 4. Criar cliente "wallet-service"
#    Client ID: wallet-service
#    Client Protocol: openid-connect
#    Access Type: confidential
#    Service Accounts Enabled: true

# 5. Criar usuário de teste
#    Username: trader1
#    Email: trader1@vibranium.com
#    Password: Trader@123456

# Credenciais padrão:
# Admin Console: http://localhost:8180
# Username: admin
# Password: admin123

echo "Keycloak está pronto em http://localhost:8180"
echo "Realm OpenID Connect URL: http://keycloak:8080/realms/vibranium/.well-known/openid-configuration"
