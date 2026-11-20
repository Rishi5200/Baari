package com.baari.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** The queue-event topic and its dead-letter topic (PLAN §11.2, §11.3). */
@Configuration
public class EventTopics {

    public static final String QUEUE_EVENTS = "baari.queue-events";
    public static final String QUEUE_EVENTS_DLT = QUEUE_EVENTS + ".DLT";

    /** Events of one queue day share a key, so they stay in one partition and in order. */
    @Bean
    NewTopic queueEventsTopic() {
        return TopicBuilder.name(QUEUE_EVENTS).partitions(6).replicas(1).build();
    }

    @Bean
    NewTopic queueEventsDlt() {
        return TopicBuilder.name(QUEUE_EVENTS_DLT).partitions(1).replicas(1).build();
    }
}
