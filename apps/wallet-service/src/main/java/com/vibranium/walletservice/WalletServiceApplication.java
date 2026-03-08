package com.vibranium.walletservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.vibranium.walletservice.config.GatewayProperties;

@SpringBootApplication
@EnableScheduling  // Habilita @Scheduled nos jobs: OutboxCleanupJob, IdempotencyKeyCleanupJob (AT-2.3.1)
@EnableConfigurationProperties(GatewayProperties.class)
@ComponentScan(basePackages = {
        "com.vibranium.walletservice",
        "com.vibranium.utils"
})
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }

}
