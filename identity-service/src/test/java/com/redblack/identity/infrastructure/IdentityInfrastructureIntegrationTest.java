package com.redblack.identity.infrastructure;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class IdentityInfrastructureIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.9.1");

    @Test
    void redisRoundTripAndKafkaAcknowledgementUseRealContainers() throws Exception {
        LettuceConnectionFactory redis = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis.afterPropertiesSet();
        redis.start();
        try {
            StringRedisTemplate template = new StringRedisTemplate(redis);
            template.afterPropertiesSet();
            template.opsForValue().set("redblack:identity:test", "ready");
            assertThat(template.opsForValue().get("redblack:identity:test")).isEqualTo("ready");
        } finally {
            redis.destroy();
        }

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
        try {
            KafkaTemplate<String, String> kafka = new KafkaTemplate<>(producerFactory);
            var result = kafka.send("redblack.identity.integration.v1", "10001", "ready")
                    .get(10, TimeUnit.SECONDS);
            assertThat(result.getRecordMetadata().hasOffset()).isTrue();
        } finally {
            producerFactory.destroy();
        }
    }
}
