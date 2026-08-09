package com.redblack.identity.application;

import com.redblack.identity.domain.OutboxEventEntity;
import com.redblack.identity.infrastructure.persistence.OutboxEventMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private final OutboxEventMapper mapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;

    public OutboxPublisher(OutboxEventMapper mapper, KafkaTemplate<String, String> kafkaTemplate, Clock clock) {
        this.mapper = mapper;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${redblack.outbox.fixed-delay:3000}")
    public void publishPending() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        for (OutboxEventEntity event : mapper.findPending(now, 50)) {
            publish(event);
        }
    }

    private void publish(OutboxEventEntity event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
            mapper.markSent(event.getEventId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        } catch (Exception exception) {
            int attempts = event.getAttempts() == null ? 0 : event.getAttempts();
            LocalDateTime next = LocalDateTime.ofInstant(
                    clock.instant().plus(OutboxBackoffPolicy.forAttempt(attempts)), ZoneOffset.UTC);
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            mapper.markRetry(event.getEventId(), next, message.substring(0, Math.min(message.length(), 1000)));
        }
    }
}
