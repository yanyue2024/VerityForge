package com.yanyue.rag.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class InfrastructureConfiguration {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper jackson2ObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean(name = "ragRunExecutor", destroyMethod = "close")
    ExecutorService ragRunExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
