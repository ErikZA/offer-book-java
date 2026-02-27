package com.vibranium.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Utilitários comuns para serialização, ID generation e logging.
 */
@Slf4j
@Component
public class CommonUtils {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Gera um UUID único para correlação/idempotência
     */
    public static String generateUUID() {
        return UUID.randomUUID().toString();
    }

    /**
     * Serializa objeto para JSON
     */
    public static <T> String toJson(T obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (IOException e) {
            log.error("Erro ao serializar objeto: {}", obj, e);
            throw new RuntimeException("JSON Serialization error", e);
        }
    }

    /**
     * Desserializa JSON para objeto
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return mapper.readValue(json, clazz);
        } catch (IOException e) {
            log.error("Erro ao desserializar JSON: {}", json, e);
            throw new RuntimeException("JSON Deserialization error", e);
        }
    }

    /**
     * Valida se string é nula ou vazia
     */
    public static boolean isNullOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

}
