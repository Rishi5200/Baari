package com.baari.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Scheduling and the Kafka error policy (PLAN §11.3): a message that keeps failing is retried three
 * times with growing pauses, then parked on {@code baari.queue-events.DLT} so it cannot block the
 * partition behind it.
 */
@Configuration
@EnableScheduling
class EventsConfig {

    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafka, MeterRegistry meters) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafka, (record, e) -> new TopicPartition(EventTopics.QUEUE_EVENTS_DLT, 0));
        var counter = meters.counter("baari.events.dlt");
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxAttempts(3);
        backOff.setMaxInterval(2_000);
        return new DefaultErrorHandler(
                (record, e) -> {
                    counter.increment();
                    recoverer.accept(record, e);
                },
                backOff);
    }
}
