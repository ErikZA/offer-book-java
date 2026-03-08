package com.vibranium.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.vibranium.orderservice.config.GatewayProperties;

@SpringBootApplication
@EnableScheduling  // Habilita @Scheduled nos servicos: OrderOutboxPublisherService, IdempotencyKeyCleanupJob
@EnableConfigurationProperties(GatewayProperties.class)
@ComponentScan(basePackages = {
        "com.vibranium.orderservice",
        "com.vibranium.utils"
})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

}
