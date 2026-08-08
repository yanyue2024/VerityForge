package com.yanyue.rag.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class ObjectStorageConfiguration {
    @Bean
    S3Client s3Client(@Value("${rag.storage.endpoint:http://localhost:9000}") String endpoint,
                      @Value("${rag.storage.region:us-east-1}") String region,
                      @Value("${rag.storage.access-key:ragadmin}") String accessKey,
                      @Value("${rag.storage.secret-key:ragadmin123}") String secretKey,
                      @Value("${rag.storage.connect-timeout-seconds:3}") long connectTimeoutSeconds,
                      @Value("${rag.storage.attempt-timeout-seconds:10}") long attemptTimeoutSeconds,
                      @Value("${rag.storage.request-timeout-seconds:30}") long requestTimeoutSeconds) {
        var connectTimeout = Duration.ofSeconds(Math.max(1, connectTimeoutSeconds));
        var attemptTimeout = Duration.ofSeconds(Math.max(1, attemptTimeoutSeconds));
        var requestTimeout = Duration.ofSeconds(Math.max(attemptTimeout.toSeconds(), requestTimeoutSeconds));
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(attemptTimeout)
                        .apiCallTimeout(requestTimeout)
                        .build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(connectTimeout)
                        .socketTimeout(attemptTimeout))
                .build();
    }

    @Bean
    S3Presigner s3Presigner(@Value("${rag.storage.public-endpoint:http://localhost:9000}") String publicEndpoint,
                            @Value("${rag.storage.region:us-east-1}") String region,
                            @Value("${rag.storage.access-key:ragadmin}") String accessKey,
                            @Value("${rag.storage.secret-key:ragadmin123}") String secretKey) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
