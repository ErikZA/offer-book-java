package com.vibranium.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Habilita @Scheduled nos servicos: OrderOutboxPublisherService, IdempotencyKeyCleanupJob
@ComponentScan(basePackages = {
        "com.vibranium.orderservice",
        "com.vibranium.utils"
})
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

}
