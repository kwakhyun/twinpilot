package io.twinpilot.api.model.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import io.twinpilot.api.config.TwinPilotProperties;

@Configuration
public class StorageConfig {

    @Bean
    public ObjectStorage objectStorage(TwinPilotProperties props) {
        var storage = props.storage();
        if ("s3".equalsIgnoreCase(storage.type())) {
            return new S3ObjectStorage(storage.s3());
        }
        return new LocalObjectStorage(Path.of(storage.localRoot()));
    }

    static final class LocalObjectStorage implements ObjectStorage {

        private final Path root;

        LocalObjectStorage(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        private Path resolve(String key) {
            Path p = root.resolve(key).normalize();
            if (!p.startsWith(root)) {
                throw new IllegalArgumentException("잘못된 저장 키: " + key);
            }
            return p;
        }

        @Override
        public void put(String key, byte[] data, String contentType) {
            try {
                Path p = resolve(key);
                Files.createDirectories(p.getParent());
                Files.write(p, data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void put(String key, InputStream data, long length, String contentType) {
            try {
                Path p = resolve(key);
                Files.createDirectories(p.getParent());
                Path tmp = Files.createTempFile(p.getParent(), ".upload-", ".tmp");
                try {
                    Files.copy(data, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public InputStream get(String key) {
            try {
                return Files.newInputStream(resolve(key));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void delete(String key) {
            try {
                Files.deleteIfExists(resolve(key));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String kind() {
            return "local";
        }
    }

    /**
     * NCP Object Storage는 S3 API와 호환된다.
     * endpoint=https://kr.object.ncloudstorage.com, region=kr-standard, Path-style 접근을 쓴다.
     */
    static final class S3ObjectStorage implements ObjectStorage {

        private final S3Client client;
        private final String bucket;

        S3ObjectStorage(TwinPilotProperties.Storage.S3 cfg) {
            this.bucket = cfg.bucket();
            this.client = S3Client.builder()
                    .endpointOverride(URI.create(cfg.endpoint()))
                    .region(Region.of(cfg.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(cfg.accessKey(), cfg.secretKey())))
                    .forcePathStyle(true)
                    .build();
        }

        @Override
        public void put(String key, byte[] data, String contentType) {
            client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(data));
        }

        @Override
        public void put(String key, InputStream data, long length, String contentType) {
            client.putObject(b -> b.bucket(bucket).key(key).contentType(contentType).contentLength(length),
                    RequestBody.fromInputStream(data, length));
        }

        @Override
        public InputStream get(String key) {
            return client.getObject(b -> b.bucket(bucket).key(key));
        }

        @Override
        public void delete(String key) {
            client.deleteObject(b -> b.bucket(bucket).key(key));
        }

        @Override
        public String kind() {
            return "s3";
        }
    }
}
