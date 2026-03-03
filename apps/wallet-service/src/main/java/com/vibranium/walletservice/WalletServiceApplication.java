package com.vibranium.walletservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // Habilita @Scheduled nos jobs: OutboxCleanupJob, IdempotencyKeyCleanupJob (AT-2.3.1)
@ComponentScan(basePackages = {
        "com.vibranium.walletservice",
        "com.vibranium.utils"
})
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }

}
