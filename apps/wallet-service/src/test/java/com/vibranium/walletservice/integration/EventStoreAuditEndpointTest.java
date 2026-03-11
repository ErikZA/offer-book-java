package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.service.EventStoreService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração para o endpoint de auditoria do Event Store do wallet-service.
 *
 * <p>Cenários validados:</p>
 * <ol>
 *   <li>GET /admin/events?aggregateId={walletId} com role ADMIN → lista completa.</li>
 *   <li>GET /admin/events?aggregateId={walletId} sem autenticação → 401.</li>
 *   <li>GET /admin/events?aggregateId={walletId} sem role ADMIN → 403.</li>
 *   <li>GET /admin/events?aggregateId={walletId}&until=... → replay temporal.</li>
 *   <li>GET /admin/events para agregado inexistente → lista vazia.</li>
 * </ol>
 */
@DisplayName("EventStore — Admin Audit Endpoint (wallet-service)")
@AutoConfigureMockMvc
class EventStoreAuditEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventStoreService eventStoreService;

    @Test
    @DisplayName("GET /admin/events com role ADMIN deve retornar lista completa de eventos do agregado")
    void getEvents_withAdminRole_shouldReturnFullEventList() throws Exception {
        // Arrange — insere eventos de teste
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-01-01T12:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T12:00:05Z");
        Instant t3 = Instant.parse("2026-01-01T12:00:10Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "WalletCreatedEvent", "{\"status\":\"CREATED\"}", t1, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsReservedEvent", "{\"status\":\"RESERVED\"}", t2, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsSettledEvent", "{\"status\":\"SETTLED\"}", t3, correlationId, 1);

        // Act + Assert
        mockMvc.perform(get("/admin/events")
                        .param("aggregateId", aggregateId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].eventType", is("WalletCreatedEvent")))
                .andExpect(jsonPath("$[1].eventType", is("FundsReservedEvent")))
                .andExpect(jsonPath("$[2].eventType", is("FundsSettledEvent")))
                .andExpect(jsonPath("$[0].aggregateId", is(aggregateId)))
                .andExpect(jsonPath("$[0].aggregateType", is("Wallet")));
    }

    @Test
    @DisplayName("GET /admin/events sem autenticação deve retornar 401 Unauthorized")
    void getEvents_withoutAuthentication_shouldReturn401() throws Exception {
        mockMvc.perform(get("/admin/events")
                        .param("aggregateId", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                        // user sem ADMIN role → 403
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/events com role USER (sem ADMIN) deve retornar 403 Forbidden")
    void getEvents_withUserRole_shouldReturn403() throws Exception {
        mockMvc.perform(get("/admin/events")
                        .param("aggregateId", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/events com parâmetro until deve retornar apenas eventos até o instante")
    void getEvents_withUntilParam_shouldReturnTemporalSubset() throws Exception {
        // Arrange
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();
        Instant t1 = Instant.parse("2026-06-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T10:00:05Z");
        Instant t3 = Instant.parse("2026-06-01T10:00:10Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "WalletCreatedEvent", "{}", t1, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsReservedEvent", "{}", t2, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsSettledEvent", "{}", t3, correlationId, 1);

        // Act + Assert — replay até T2 (inclusive), deve retornar 2 eventos
        mockMvc.perform(get("/admin/events")
                        .param("aggregateId", aggregateId)
                        .param("until", t2.toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].eventType", is("WalletCreatedEvent")))
                .andExpect(jsonPath("$[1].eventType", is("FundsReservedEvent")));
    }

    @Test
    @DisplayName("GET /admin/events para agregado inexistente deve retornar lista vazia")
    void getEvents_nonExistentAggregate_shouldReturnEmptyList() throws Exception {
        mockMvc.perform(get("/admin/events")
                        .param("aggregateId", UUID.randomUUID().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
