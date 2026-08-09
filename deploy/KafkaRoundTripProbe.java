import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class KafkaRoundTripProbe {
    private KafkaRoundTripProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: KafkaRoundTripProbe <bootstrapServers> <topic>");
            System.exit(2);
        }

        String bootstrapServers = args[0];
        String topic = args[1];
        String probeId = UUID.randomUUID().toString();
        String groupId = "redblack-phase2-probe-" + probeId;
        String payload = "{\"probeId\":\"" + probeId + "\",\"sentAt\":\"" + Instant.now() + "\"}";

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProperties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        producerProperties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "15000");
        producerProperties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");

        RecordMetadata produced;
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties)) {
            produced = producer.send(new ProducerRecord<>(topic, probeId, payload)).get();
        }

        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(topic));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                    if (probeId.equals(record.key()) && payload.equals(record.value())) {
                        if (record.partition() != produced.partition() || record.offset() != produced.offset()) {
                            throw new IllegalStateException("Consumed record metadata does not match produced record");
                        }
                        System.out.printf(
                                "KAFKA_ROUND_TRIP_OK topic=%s partition=%d offset=%d group=%s probeId=%s%n",
                                topic, record.partition(), record.offset(), groupId, probeId);
                        return;
                    }
                }
            }
        }

        throw new IllegalStateException("Timed out waiting for produced Kafka record " + probeId);
    }
}
