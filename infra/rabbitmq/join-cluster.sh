#!/bin/sh
# join-cluster.sh
#
# Entrypoint customizado para rabbitmq-2 e rabbitmq-3 no ambiente staging.
#
# Problema que resolve: nós RabbitMQ sobem standalone mesmo com RABBITMQ_ERLANG_COOKIE
# compartilhado — o cookie apenas autentica a conexão Erlang, mas o join efetivo do
# cluster exige `rabbitmqctl join_cluster`. Sem isso, mensagens publicadas em rabbitmq-1
# NÃO são visíveis em rabbitmq-2/3 (nenhuma replicação).
#
# Estratégia:
#   1. Inicia o servidor RabbitMQ em background via entrypoint oficial.
#   2. Aguarda o nó local responder a `rabbitmq-diagnostics ping`.
#   3. Executa stop_app / reset / join_cluster / start_app para ingressar no cluster.
#   4. Transfere o controle de volta ao processo RabbitMQ em foreground (wait $PID).
#
# Pré-requisito no compose:
#   rabbitmq-1 deve estar service_healthy antes deste container iniciar (depends_on
#   com condition: service_healthy garante isso).
#
# Referências:
#   https://www.rabbitmq.com/docs/clustering
#   https://www.rabbitmq.com/docs/rabbitmqctl

set -e

LOG_PREFIX="[join-cluster $(hostname)]"

echo "$LOG_PREFIX Iniciando RabbitMQ server em background..."
# Chama o entrypoint oficial da imagem Docker do RabbitMQ que configura o cookie,
# permissões, plugins habilitados, etc. — preserva todo o comportamento padrão.
/usr/local/bin/docker-entrypoint.sh rabbitmq-server &
RABBIT_PID=$!

echo "$LOG_PREFIX Aguardando nó local ficar pronto..."
# rabbitmq-diagnostics ping retorna 0 quando o broker aceita conexões.
until rabbitmq-diagnostics -q ping 2>/dev/null; do
    sleep 3
done
echo "$LOG_PREFIX Nó local pronto."

echo "$LOG_PREFIX Ingressando no cluster (rabbit@rabbitmq-1)..."
# stop_app para the application layer (mantém a VM Erlang rodando).
rabbitmqctl stop_app
# reset limpa o banco de dados Mnesia local — necessário antes do join.
# ATENÇÃO: em nós com dados existentes isso apaga filas/mensagens locais;
# em staging é aceitável pois o cluster é recriado a cada deploy.
rabbitmqctl reset
# join_cluster faz este nó copiar metadados de rabbitmq-1 via Erlang distribution.
rabbitmqctl join_cluster rabbit@rabbitmq-1
# start_app reinicia a camada de aplicação já membro do cluster.
rabbitmqctl start_app

echo "$LOG_PREFIX Associado ao cluster com sucesso."
rabbitmqctl cluster_status --formatter=compact

echo "$LOG_PREFIX Entregando controle ao processo RabbitMQ (PID=$RABBIT_PID)..."
# wait garante que o container só encerra quando rabbitmq-server terminar,
# mantendo o ciclo de vida correto gerenciado pelo Docker.
wait $RABBIT_PID
