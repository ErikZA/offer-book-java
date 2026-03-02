package com.vibranium.orderservice.config;

import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.orm.jpa.JpaTransactionManager;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;

/**
 * Registra o {@link MongoTransactionManager} para habilitar transações MongoDB
 * multi-documento no Query Side (AT-05.3).
 *
 * <h3>Por que transação multi-documento é necessária aqui</h3>
 * <p>O método {@code onMatchExecuted()} atualiza <strong>dois documentos distintos</strong>
 * ({@code buyOrderId} e {@code sellOrderId}) de forma sequencial:</p>
 * <pre>
 *   findAndModify(buyOrderId)   → OK   ← commitado atomicamente por documento
 *   findAndModify(sellOrderId)  → FAIL ← exceção de infraestrutura
 * </pre>
 * <p>Sem transação, se o segundo update falha, o buyer já foi modificado e não há rollback
 * automático — estado permanentemente inconsistente entre os dois lados do trade.</p>
 *
 * <h3>Solução: {@link MongoTransactionManager} + {@code @Transactional}</h3>
 * <p>Com este bean registrado, o {@link MongoTransactionManager} envolve os dois
 * {@code findAndModify} em uma única sessão MongoDB com {@code startTransaction}:</p>
 * <pre>
 *   session.startTransaction()
 *     findAndModify(buyOrderId)   → OK  (write buffered na sessão)
 *     findAndModify(sellOrderId)  → EXCEPTION
 *   session.abortTransaction()    → ambos os writes descartados (rollback)
 * </pre>
 * <p>O MongoDB 7 (com replica set único tanto em testes via Testcontainers quanto em
 * staging via {@code replicaSet=rs0}) suporta transações multi-documento completas,
 * incluindo {@code findAndModify} com {@code upsert=true}.</p>
 *
 * <h3>Idempotência como defesa em profundidade</h3>
 * <p>O rollback garante que nenhuma inconsistência persiste. Após o rollback, o RabbitMQ
 * re-entrega a mensagem. Na re-entrega, a idempotência por {@code eventId + "-" + orderId}
 * garante que ambos os lados são aplicados exatamente uma vez — zero duplicação.</p>
 *
 * <h3>Coexistência com JPA — dois gerenciadores de transação</h3>
 * <p>Esta configuração registra <strong>dois</strong> {@link org.springframework.transaction.PlatformTransactionManager}:</p>
 * <ul>
 *   <li>{@link JpaTransactionManager} — {@code @Primary}, usado por padrão por todos os
 *       repositórios JPA ({@code @Transactional} sem qualificador).</li>
 *   <li>{@link MongoTransactionManager} — acessado exclusivamente via
 *       {@code @Transactional("mongoTransactionManager")} nos métodos do Query Side.</li>
 * </ul>
 * <p>O {@link JpaTransactionManager} precisa ser declarado explicitamente aqui porque
 * {@code JpaTransactionManagerAutoConfiguration} usa
 * {@code @ConditionalOnMissingBean(PlatformTransactionManager.class)} — ao encontrar
 * o {@link MongoTransactionManager} já registrado, a auto-configuração seria pulada,
 * causando {@code NoSuchBeanDefinitionException} em operações JPA.</p>
 *
 * <h3>Pré-requisito: Replica Set</h3>
 * <p>Transações MongoDB requerem replica set (MongoDB 4.0+). Ambiente de testes:
 * {@code MongoDBContainer.getReplicaSetUrl()} fornece URI com replica set. Ambiente de
 * produção: {@code docker-compose.staging.yml} configura {@code replicaSet=rs0}.</p>
 */
@Configuration
// Criado apenas quando MongoDB está habilitado — alinhado com os demais beans do Query Side.
// Em testes do Command Side (app.mongodb.enabled=false), este bean não é registrado,
// evitando que o MongoTransactionManager procure um MongoDatabaseFactory inexistente.
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class MongoTransactionConfig {

    private static final Logger logger = LoggerFactory.getLogger(MongoTransactionConfig.class);

    /**
     * Re-declara o {@link JpaTransactionManager} como {@code @Primary} quando MongoDB
     * está ativo.
     *
     * <h3>Por que é necessário</h3>
     * <p>{@code JpaTransactionManagerAutoConfiguration} usa
     * {@code @ConditionalOnMissingBean(PlatformTransactionManager.class)}: quando
     * {@link MongoTransactionManager} (que implementa {@link org.springframework.transaction.PlatformTransactionManager})
     * já registrado, a auto-configuração ignora a criação do {@code JpaTransactionManager},
     * causando {@code NoSuchBeanDefinitionException: No bean named 'transactionManager'}
     * em operações JPA (ex: {@code UserRegistryRepository.save()}).</p>
     *
     * <p>A solução é definir explicitamente o {@link JpaTransactionManager} aqui com
     * {@code @Primary}, garantindo que:</p>
     * <ul>
     *   <li>O bean {@code transactionManager} existe para todos os repositórios JPA.</li>
     *   <li>É o gerenciador DEFAULT para {@code @Transactional} sem qualificador.</li>
     *   <li>{@link MongoTransactionManager} é acessado EXPLICITAMENTE via
     *       {@code @Transactional("mongoTransactionManager")} e não intercepta
     *       operações JPA.</li>
     * </ul>
     *
     * @param emf {@link EntityManagerFactory} auto-configurada pela stack JPA.
     * @return Gerenciador de transações JPA marcado como {@code @Primary}.
     */
    @Bean
    @Primary
    JpaTransactionManager transactionManager(EntityManagerFactory emf) {
        logger.debug("Declarando JpaTransactionManager @Primary para coexistir com MongoTransactionManager (AT-05.3)");
        return new JpaTransactionManager(emf);
    }

    /**
     * Registra o {@link MongoTransactionManager} como bean Spring para suporte a
     * transações MongoDB multi-documento via {@code @Transactional("mongoTransactionManager")}.
     *
     * @param dbFactory Fábrica de banco MongoDB gerenciada pelo Spring Boot.
     * @return Gerenciador de transações MongoDB – não {@code @Primary}, acessado
     *         via qualificador explícito nos métodos do Query Side.
     */
    @Bean
    MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory dbFactory) {
        logger.debug("Registrando MongoTransactionManager para suporte a transações multi-documento (AT-05.3)");
        return new MongoTransactionManager(dbFactory);
    }

    /**
     * Registra os conversores entre {@link BigDecimal} e {@link Decimal128} para todas
     * as operações MongoDB (leituras e escritas de documentos, queries e updates).
     *
     * <h3>Por que é necessário</h3>
     * <p>Spring Data MongoDB 4.x registra {@code BigDecimalToDecimal128Converter} como converter
     * padrão para mapeamento de entidades, mas o {@code UpdateMapper} para operações
     * {@code $set}/{@code $setOnInsert} em {@link org.springframework.data.mongodb.core.query.Update}
     * não aplica automaticamente o converter para valores raw passados diretamente.
     * Sem este bean, {@code BigDecimal} é serializado como String BSON, causando
     * {@code TypeMismatch} no operador {@code $inc} quando o campo já está armazenado
     * como String e o argumento é Decimal128 (ou vice-versa).</p>
     *
     * <p>O registro explícito garante consistência em toda a stack:
     * mapeamento de entidades, queries, criteria e operações de update.</p>
     *
     * @return  Conversões customizadas do MongoDB.
     */
    @Bean
    MongoCustomConversions mongoCustomConversions() {
        // BigDecimalToDecimal128Converter: escrita — garante que BigDecimal é armazenado
        // como Decimal128 (tipo BSON nativo para decimais de alta precisão) em vez de String.
        // Decimal128ToBigDecimalConverter: leitura — converte Decimal128 de volta para BigDecimal
        // ao mapear documentos MongoDB para entidades Java (ex: getRemainingQty()).
        return new MongoCustomConversions(List.of(
                new BigDecimalToDecimal128Converter(),
                new Decimal128ToBigDecimalConverter()
        ));
    }

    // =========================================================================
    // Conversores internos
    // =========================================================================

    /**
     * Conversor de escrita: {@link BigDecimal} → {@link Decimal128}.
     * Aplicado automaticamente pelo Spring Data MongoDB a todos os campos
     * {@code BigDecimal} em documentos, queries e operações de update.
     */
    @WritingConverter
    static class BigDecimalToDecimal128Converter implements Converter<BigDecimal, Decimal128> {
        @Override
        public Decimal128 convert(BigDecimal source) {
            return new Decimal128(source);
        }
    }

    /**
     * Conversor de leitura: {@link Decimal128} → {@link BigDecimal}.
     * Aplicado ao mapear campos Decimal128 do MongoDB de volta para campos Java
     * {@code BigDecimal} (ex: {@code remainingQty}, {@code price}, {@code originalQty}).
     */
    @ReadingConverter
    static class Decimal128ToBigDecimalConverter implements Converter<Decimal128, BigDecimal> {
        @Override
        public BigDecimal convert(Decimal128 source) {
            return source.bigDecimalValue();
        }
    }
}
