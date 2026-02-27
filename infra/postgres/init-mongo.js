// Inicialização do MongoDB
db.createUser({
	user: 'admin',
	pwd: 'admin123',
	roles: [{ role: 'root', db: 'admin' }],
});

// Criar database vibranium_orders
db = db.getSiblingDB('vibranium_orders');

// Criar collections e índices
db.createCollection('orders');
db.orders.createIndex({ userId: 1 });
db.orders.createIndex({ symbol: 1 });
db.orders.createIndex({ status: 1 });
db.orders.createIndex({ createdAt: 1 });
db.orders.createIndex({ symbol: 1, side: 1, status: 1 }); // Composto para order book

// Criar collection de eventos
db.createCollection('order_events');
db.order_events.createIndex({ correlationId: 1 }, { unique: true });
db.order_events.createIndex({ timestamp: 1 });

print('MongoDB initialized successfully');
