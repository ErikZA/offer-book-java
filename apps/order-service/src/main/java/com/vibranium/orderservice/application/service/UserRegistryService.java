package com.vibranium.orderservice.application.service;

import com.vibranium.utils.dto.KeycloakEventDto;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Serviço de aplicação para gerenciamento do registro de usuários.
 *
 * <p>Encapsula a lógica de negócio para persistência de usuários vindos
 * do Keycloak, garantindo que a criação do registro e a gravação no
 * Event Store ocorram na mesma transação atômica.</p>
 */
@Service
public class UserRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(UserRegistryService.class);

    private final UserRegistryRepository userRegistryRepository;
    private final EventStoreService eventStoreService;
    private final ProcessedEventRepository processedEventRepository;

    public UserRegistryService(UserRegistryRepository userRegistryRepository,
                               EventStoreService eventStoreService,
                               ProcessedEventRepository processedEventRepository) {
        this.userRegistryRepository = userRegistryRepository;
        this.eventStoreService      = eventStoreService;
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Registra um novo usuário e armazena o evento bruto no Event Store.
     *
     * @param userId    ID do usuário no Keycloak.
     * @param event     DTO do evento decodificado.
     * @param rawPayload JSON bruto do evento para auditoria.
     */
    @Transactional
    public void registerUser(String userId, KeycloakEventDto event, String rawPayload) {
        logger.info("Creating user: {}", userId);
        
        if (userRegistryRepository.existsByKeycloakId(userId)) {
            logger.debug("Usuário já registrado (idempotente): keycloakId={}", userId);
            return;
        }

        // 1. Persiste o registro do usuário
        UserRegistry registry = new UserRegistry(userId);
        userRegistryRepository.save(registry);

        UUID eventIdCtr = UUID.randomUUID();
        // 2. Persiste o evento bruto no Event Store para auditoria e replay
        // AggregateType "User" e AggregateId como o próprio userId do Keycloak
        eventStoreService.append(
                eventIdCtr, // ID interno do evento no Event Store
                userId,
                "User",
                event.type() != null ? event.type() : "ADMIN_CREATE_USER",
                rawPayload,
                Instant.ofEpochMilli(event.time() > 0 ? event.time() : Instant.now().toEpochMilli()),
                eventIdCtr,
                1
        );

        processedEventRepository.save(new ProcessedEvent(eventIdCtr));

        logger.info("Usuário registrado e evento persistido no Event Store: keycloakId={}", userId);
    }
}
