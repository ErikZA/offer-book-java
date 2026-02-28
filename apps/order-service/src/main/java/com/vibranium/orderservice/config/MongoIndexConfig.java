package com.vibranium.orderservice.config;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

/**
 * Configuração que garante a criação de índices MongoDB no startup da aplicação.
 *
 * <p>O Spring Data MongoDB com {@code auto-index-creation: true} cria os índices
 * declarados via {@code @CompoundIndex} no startup, mas somente se a coleção já existir
 * ou se a escrita ocorrer antes do {@code ensureIndexes()} ser chamado. Em ambientes
 * de teste com Testcontainers (MongoDB fresh container) e uso concorrente imediato,
 * a criação lazy do índice na primeira escrita pode adicionar ~500ms à latência,
 * quebrando os SLA tests.</p>
 *
 * <p>Este componente usa {@link SmartInitializingSingleton} que é invocado pelo
 * {@code DefaultListableBeanFactory} após todos os singletons serem inicializados —
 * garantindo que o {@link MongoTemplate} esteja completamente configurado e conectado
 * ao cluster MongoDB antes de executar os {@code ensureIndex()} calls.</p>
 *
 * <p><strong>Idempotente:</strong> {@code ensureIndex()} no MongoDB 7+ é no-op se
 * o índice já existir com a mesma definição.</p>
 *
 * <p><strong>Connection Pool:</strong> O driver Java do MongoDB cresce o connection pool
 * lazily por padrão ({@code minSize=0}). Em ambientes com muitas requests concorrentes
 * imediatas (testes de carga, cold-start), isso causa spikes de latência enquanto novas
 * conexões são criadas. {@code minSize=10} garante 10 conexões prontas desde o startup.</p>
 */
@Configuration
public class MongoIndexConfig {

    private static final Logger logger = LoggerFactory.getLogger(MongoIndexConfig.class);

    /**
     * Customiza o MongoDB connection pool para pré-criar conexões mínimas no startup.
     *
     * <p>Sem esta configuração, o pool cresce lazily (minSize=0): quando chegam 50
     * requests concorrentes, o driver cria conexões uma a uma (maxConnecting=2 por default),
     * causando spikes de latência de ~300-900ms em Testcontainers/Docker. Com
     * {@code minSize=10}, 10 conexões são estabelecidas durante o startup do ApplicationContext,
     * eliminando o cold-start.</p>
     *
     * @return Customizer para {@link MongoClientSettings} injetado no auto-configure do Spring Boot.
     */
    @Bean
    MongoClientSettingsBuilderCustomizer mongoConnectionPoolCustomizer() {
        // minSize=10: pré-cria 10 conexões no startup para evitar crescimento lazy em carga concorrente.
        // maxSize=100: headroom para picos de carga em produção.
        // Em produção com Atlas ou Kubernetes, ajustar conforme CPU/memória disponíveis.
        return builder -> builder.applyToConnectionPoolSettings(
                poolBuilder -> poolBuilder.minSize(10).maxSize(100)
        );
    }

    /**
     * Pré-cria os índices da coleção {@code orders} no startup, evitando criação lazy
     * durante o primeiro acesso — eliminando spike de latência em produção e em testes.
     *
     * <p>Índices criados:</p>
     * <ul>
     *   <li>{@code idx_userId_createdAt}: composto {@code {userId: 1, createdAt: -1}} —
     *       suporta a query paginada {@code findByUserIdOrderByCreatedAtDesc} em O(log n).</li>
     * </ul>
     *
     * @param mongoTemplate Template MongoDB auto-configurado pelo Spring Boot.
     * @return Bean {@link SmartInitializingSingleton} executado após a inicialização do contexto.
     */
    @Bean
    // Só criado quando app.mongodb.enabled=true (ou quando a propriedade está ausente em prod).
    // Evita injecão de MongoTemplate inexistente em contextos sem MongoDB (Command Side tests).
    @ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
    SmartInitializingSingleton mongoOrdersIndexInitializer(MongoTemplate mongoTemplate) {
        return () -> {
            logger.info("Inicializando índices MongoDB para coleção 'orders'...");

            // Cria a coleção se não existir (MongoDB cria lazily na primeira escrita;
            // forçamos aqui para garantir que o ensureIndex() funcione mesmo sem documentos)
            if (!mongoTemplate.collectionExists("orders")) {
                mongoTemplate.createCollection("orders");
                logger.debug("Coleção 'orders' criada.");
            }

            // Garante o índice composto para consultas paginadas por usuário
            IndexOperations indexOps = mongoTemplate.indexOps("orders");
            indexOps.ensureIndex(
                    new CompoundIndexDefinition(
                            new Document("userId", 1).append("createdAt", -1))
                            .named("idx_userId_createdAt")
            );

            logger.info("Índices MongoDB para 'orders' prontos.");
        };
    }
}
