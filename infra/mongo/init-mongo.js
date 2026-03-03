// =============================================================================
// init-mongo.js — Inicialização de collections e índices do vibranium_orders
// =============================================================================
// Executado pelo entrypoint do mongo:7.0 em /docker-entrypoint-initdb.d/
// na PRIMEIRA subida do container (quando o volume está vazio).
//
// NOTA: O usuário root já é criado automaticamente pelo entrypoint a partir de
// MONGO_INITDB_ROOT_USERNAME / MONGO_INITDB_ROOT_PASSWORD. Não recriamos aqui.
//
// Contexto: o entrypoint executa este script conectado ao banco
// MONGO_INITDB_DATABASE (vibranium_orders) com autenticação desabilitada
// temporariamente. Após os scripts, mongod reinicia com auth habilitado.
// =============================================================================

// Garante que estamos no banco correto
db = db.getSiblingDB('vibranium_orders');

// ---------------------------------------------------------------------------
// Collection: orders — estado atual de cada ordem (CQRS Read Model)
// ---------------------------------------------------------------------------
db.createCollection('orders');
db.orders.createIndex({ userId: 1 });
db.orders.createIndex({ symbol: 1 });
db.orders.createIndex({ status: 1 });
db.orders.createIndex({ createdAt: 1 });
// Índice composto para leitura do order book (symbol + side + status)
db.orders.createIndex({ symbol: 1, side: 1, status: 1 });

// ---------------------------------------------------------------------------
// Collection: order_events — log imutável de eventos de domínio (Event Store)
// ---------------------------------------------------------------------------
db.createCollection('order_events');
// correlationId é único por evento — evita duplicatas em replay
db.order_events.createIndex({ correlationId: 1 }, { unique: true });
db.order_events.createIndex({ timestamp: 1 });

print('[init-mongo.js] vibranium_orders inicializado com sucesso.');
