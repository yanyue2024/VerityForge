package com.yanyue.rag.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class ObjectStorageConfigurationTest {
    @Test
    void minioInterruptionFailsWithinTheConfiguredBoundaryAndRecoversAfterResume() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
        var minio = new GenericContainer<>("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
                .withEnv("MINIO_ROOT_USER", "ragadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "ragadmin123")
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
        minio.start();
        var endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        var s3 = new ObjectStorageConfiguration().s3Client(
                endpoint, "us-east-1", "ragadmin", "ragadmin123", 1, 1, 2);
        var bucket = "rag-test";
        var key = "documents/policy.txt";
        var content = "recoverable source".getBytes(StandardCharsets.UTF_8);
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
            var get = GetObjectRequest.builder().bucket(bucket).key(key).build();
            assertThat(s3.getObjectAsBytes(get).asByteArray()).isEqualTo(content);

            DockerClientFactory.instance().client().pauseContainerCmd(minio.getContainerId()).exec();
            try {
                assertThatThrownBy(() -> s3.getObjectAsBytes(get)).isInstanceOf(RuntimeException.class);
            } finally {
                DockerClientFactory.instance().client().unpauseContainerCmd(minio.getContainerId()).exec();
            }

            assertThat(s3.getObjectAsBytes(get).asByteArray()).isEqualTo(content);
        } finally {
            s3.close();
            minio.stop();
        }
    }
}
