package com.yanyue.rag.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication(
        scanBasePackages = "com.yanyue.rag",
        exclude = UserDetailsServiceAutoConfiguration.class
)
public class RagApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagApiApplication.class, args);
    }
}
