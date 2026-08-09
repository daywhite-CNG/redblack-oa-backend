package com.redblack.office.infrastructure.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "redblack.oss")
public class OssProperties {
    private String region;
    private String endpoint;
    private String bucket;
    private String accessKeyId;
    private String accessKeySecret;
    private String prefix = "redblack/v1/";

    public boolean configured() {
        return present(region) && present(endpoint) && present(bucket)
                && present(accessKeyId) && present(accessKeySecret);
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }
}
