#!/bin/bash
# Script para simular o Self-Registration no Keycloak via cURL
# Isso é necessário para disparar o evento "REGISTER" que o plugin do RabbitMQ intercepta.
#
# Uso: ./simular_registro_evento.sh [username] [email] [senha]
# Exemplo: ./simular_registro_evento.sh vibranium-user1 user1@teste.com admin123

USERNAME=${1:-"novo_usuario"}
EMAIL=${2:-"novo_usuario@test.com"}
PASSWORD=${3:-"admin123"}
KEYCLOAK_URL="http://localhost:8180"
REALM="orderbook-realm"
CLIENT_ID="order-client"

echo "=========================================="
echo "Iniciando registro do usuario: $USERNAME"
echo "=========================================="

# 1. Obter a página de registro e extrair os Cookies (JSESSIONID) e a URL do formulário
echo "-> 1. Iniciando sessão e carregando formulário de registro..."
HTML=$(curl -s -c cookies.txt "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/registrations?client_id=$CLIENT_ID&response_type=code")

# Extraímos a Action URL do formulário usando grep e sed (A URL possui o session_code e execution id dinâmicos)
ACTION_URL=$(echo "$HTML" | grep -o 'action="[^"]*login-actions/registration[^"]*"' | head -n 1 | sed 's/action="//' | sed 's/"//' | sed 's/&amp;/\&/g' | sed 's|http://keycloak:8080|http://localhost:8180|g' | tr -d '\r')

if [ -z "$ACTION_URL" ]; then
    echo "❌ Erro: Não foi possível obter a URL de Action do formulário."
    echo "Verifique se o Keycloak está rodando e se o 'User registration' está habilitado no Realm Settings."
    rm -f cookies.txt
    exit 1
fi

echo "-> URL de Execução Dinâmica Capturada com Sucesso!"
echo "-> ACTION_URL: $ACTION_URL"

# 2. Submeter os dados para a URL de execução injetando os cookies gerados
echo "-> 2. Enviando requisição POST de registro via cURL..."
echo "-> ACTION_URL: $ACTION_URL"
# wait 15 sec
sleep 15

STATUS_CODE=$(curl -s -o response.html -w "%{http_code}" -X POST "$ACTION_URL" \
     -b cookies.txt \
     -H "Content-Type: application/x-www-form-urlencoded" \
     --data-urlencode "firstName=Nome" \
     --data-urlencode "lastName=Sobrenome" \
     --data-urlencode "email=$EMAIL" \
     --data-urlencode "username=$USERNAME" \
     --data-urlencode "password=$PASSWORD" \
     --data-urlencode "password-confirm=$PASSWORD")

if [ "$STATUS_CODE" -eq 302 ] || [ "$STATUS_CODE" -eq 200 ]; then
    if grep -q "registration" response.html; then
        echo "⚠️ Status 200 OK mas retornou o form. Possivel erro de validacao. Olhar response.html."
        cat response.html | grep -i "error" || true
    else
        echo "✅ Sucesso! O usuário '$USERNAME' foi registrado."
        echo "O evento REGISTER deve ter sido propagado no RabbitMQ para as filas do Wallet e Order service."
    fi
else
    echo "⚠️ Status inesperado retornado: $STATUS_CODE. O usuário já existe ou dados incorretos?"
    cat response.html | grep -i "error" || true
fi

# Cleanup
rm -f cookies.txt response.html
echo "=========================================="
