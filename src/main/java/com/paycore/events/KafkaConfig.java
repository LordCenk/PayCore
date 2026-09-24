package com.paycore.events;

import com.paycore.config.PayCoreProperties;
import java.time.format.DateTimeParseException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;

@Configuration
@ConditionalOnProperty(prefix = "paycore.events", name = "publisher", havingValue = "kafka")
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    public static String deadLetterTopic(String topic) {
        return topic + ".DLT";
    }

    @Bean
    NewTopic eventsTopic(PayCoreProperties properties) {
        return TopicBuilder.name(properties.events().topic()).partitions(properties.events().partitions()).build();
    }

    @Bean
    NewTopic deadLetterTopic(PayCoreProperties properties) {
        return TopicBuilder.name(deadLetterTopic(properties.events().topic())).partitions(1).build();
    }

    /**
     * A record that keeps failing (3 tries) is parked on the dead-letter topic with the exception in its
     * headers, so one bad message can't block its partition. Malformed messages skip the retries.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka, (record, ex) -> {
            log.error("sending record to dead-letter topic topic={} offset={} key={}: {}", record.topic(),
                    record.offset(), record.key(), ex.getMessage());
            return new TopicPartition(deadLetterTopic(record.topic()), -1);
        });
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
        handler.addNotRetryableExceptions(IllegalArgumentException.class, JacksonException.class,
                DateTimeParseException.class);
        return handler;
    }
}
