package com.yanyue.rag.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(scanBasePackages = {
        "com.yanyue.rag.worker",
        "com.yanyue.rag.infrastructure",
        "com.yanyue.rag.application.telemetry"
})
public class RagWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagWorkerApplication.class, args);
    }
}
