package com.redblack.office.infrastructure.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.redblack.office.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

@Component
public class OssStorage {
    private final OssProperties properties;

    public OssStorage(OssProperties properties) { this.properties = properties; }

    public String put(String bucket, String objectKey, byte[] content, String contentType) {
        return execute(client -> {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType(contentType);
            return client.putObject(bucket(bucket), objectKey,
                    new ByteArrayInputStream(content), metadata).getETag();
        });
    }

    public StoredObject get(String bucket, String objectKey) {
        return execute(client -> {
            try (var object = client.getObject(bucket(bucket), objectKey);
                 var stream = object.getObjectContent()) {
                return new StoredObject(stream.readAllBytes(), object.getObjectMetadata().getETag());
            }
        });
    }

    public StoredStream open(String bucket, String objectKey) {
        if (!properties.configured()) throw unavailable();
        OSS client = null;
        try {
            client = new OSSClientBuilder().build(properties.getEndpoint(),
                    properties.getAccessKeyId(), properties.getAccessKeySecret());
            OSSObject object = client.getObject(bucket(bucket), objectKey);
            return new StoredStream(object.getObjectContent(), object.getObjectMetadata().getETag(), client);
        } catch (BusinessException exception) {
            if (client != null) client.shutdown();
            throw exception;
        } catch (Exception exception) {
            if (client != null) client.shutdown();
            throw unavailable();
        }
    }

    public StoredMetadata metadata(String bucket, String objectKey) {
        return execute(client -> {
            String actualBucket = bucket(bucket);
            if (!client.doesObjectExist(actualBucket, objectKey)) return new StoredMetadata(false, null, 0);
            ObjectMetadata metadata = client.getObjectMetadata(actualBucket, objectKey);
            return new StoredMetadata(true, metadata.getETag(), metadata.getContentLength());
        });
    }

    public void delete(String bucket, String objectKey) {
        execute(client -> { client.deleteObject(bucket(bucket), objectKey); return null; });
    }

    private <T> T execute(Operation<T> operation) {
        if (!properties.configured()) throw unavailable();
        OSS client = null;
        try {
            client = new OSSClientBuilder().build(properties.getEndpoint(),
                    properties.getAccessKeyId(), properties.getAccessKeySecret());
            return operation.run(client);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable();
        } finally {
            if (client != null) client.shutdown();
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "FILE_STORAGE_UNAVAILABLE", "对象存储暂不可用");
    }

    private String bucket(String value) {
        return value == null || value.isBlank() ? properties.getBucket() : value;
    }

    public record StoredObject(byte[] content, String etag) { }
    public record StoredMetadata(boolean exists, String etag, long contentLength) { }

    public static final class StoredStream implements AutoCloseable {
        private final InputStream content;
        private final String etag;
        private final OSS client;

        public StoredStream(InputStream content, String etag, OSS client) {
            this.content = content;
            this.etag = etag;
            this.client = client;
        }

        public InputStream content() { return content; }
        public String etag() { return etag; }

        @Override
        public void close() throws IOException {
            try {
                content.close();
            } finally {
                if (client != null) client.shutdown();
            }
        }
    }

    @FunctionalInterface
    private interface Operation<T> { T run(OSS client) throws Exception; }
}
