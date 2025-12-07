package com.example.buffered;

import org.apache.kafka.clients.producer.*;

import java.util.Properties;

public class LockoutEventProducer {
    private final KafkaProducer<String,String> producer;
    private final String topic = "lockout-events";

    public LockoutEventProducer() {
        Properties p = new Properties();
        p.put("bootstrap.servers","localhost:9092");
        p.put("acks","all");
        p.put("key.serializer","org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer","org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(p);
    }

    public void sendLockoutEvent(String user, int failedCount) {
        String json = "{\"user\":\""+user+"\",\"failedCount\":"+failedCount+"}";
        System.out.println("Sending User lock events: "+ json);

        producer.send(new ProducerRecord<>(topic, user, json));
    }
}

