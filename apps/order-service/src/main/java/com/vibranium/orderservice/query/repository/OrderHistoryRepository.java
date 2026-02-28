package com.vibranium.orderservice.query.repository;

import com.vibranium.orderservice.query.model.OrderDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Repositório MongoDB do Read Model de Ordens (Query Side).
 *
 * <p>Spring Data MongoDB gera a implementação em runtime a partir das
 * assinaturas dos métodos. Nenhum SQL ou query manual necessário.</p>
 *
 * <p>A query paginada {@code findByUserIdOrderByCreatedAtDesc} é suportada
 * pelo índice composto {@code {userId: 1, createdAt: -1}} declarado em
 * {@link OrderDocument} via {@code @CompoundIndex}. Sem esse índice,
 * a query faria collection scan — inaceitável em produção.</p>
 *
 * <p>Síncrono por consistência com o resto do stack neste sprint.
 * Migração para {@code ReactiveMongoRepository} é um trade-off possível
 * para maior throughput de escrita, registrado como tech debt.</p>
 */
public interface OrderHistoryRepository extends MongoRepository<OrderDocument, String> {

    /**
     * Lista todas as ordens de um usuário ordenadas da mais recente para a mais antiga.
     *
     * <p>Suportado pelo índice composto {@code idx_userId_createdAt}.
     * O param {@code Pageable} garante que nunca retornaremos um resultado
     * descontrolado — use {@code PageRequest.of(page, size)} do lado do controller.</p>
     *
     * @param userId   Keycloak ID do usuário (claim {@code sub} do JWT).
     * @param pageable Configuração de paginação e ordenação.
     * @return Página de documentos do usuário, da mais recente para a mais antiga.
     */
    Page<OrderDocument> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /**
     * Busca uma ordem pelo seu ID único.
     *
     * <p>Atenção: o {@code @Id} do documento já é o {@code orderId} (String),
     * portanto {@code findById(orderId)} é equivalente a este método.
     * Este método existe por clareza semântica nos testes e nos consumers.</p>
     *
     * @param orderId UUID da ordem como String.
     * @return {@code Optional} com o documento, ou vazio se não existir.
     */
    Optional<OrderDocument> findByOrderId(String orderId);
}
