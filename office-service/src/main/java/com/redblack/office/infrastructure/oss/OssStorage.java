package com.redblack.office.infrastructure.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.redblack.office.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

@Component
public class OssStorage {
    private final OssProperties properties;

    public OssStorage(OssProperties properties) { this.properties = properties; }

    public String put(String objectKey, byte[] content, String contentType) {
        return execute(client -> {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.length);
            metadata.setContentType(contentType);
            return client.putObject(properties.getBucket(), objectKey,
                    new ByteArrayInputStream(content), metadata).getETag();
        });
    }

    public byte[] get(String objectKey) {
        return execute(client -> {
            try (var object = client.getObject(properties.getBucket(), objectKey);
                 var stream = object.getObjectContent()) {
                return stream.readAllBytes();
            }
        });
    }

    public void delete(String objectKey) { execute(client -> { client.deleteObject(properties.getBucket(), objectKey); return null; }); }

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
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE", "对象存储暂不可用");
    }

    @FunctionalInterface
    private interface Operation<T> { T run(OSS client) throws Exception; }
}
