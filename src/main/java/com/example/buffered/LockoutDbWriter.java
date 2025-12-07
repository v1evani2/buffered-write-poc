package com.example.buffered;

import org.apache.kafka.clients.consumer.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;

public class LockoutDbWriter {

    public static void main(String[] args) throws Exception {
        Class.forName("oracle.jdbc.driver.OracleDriver");

        KafkaConsumer<String,String> c = consumer();
        c.subscribe(List.of("lockout-events"));

        Connection conn = DriverManager.getConnection(
                "jdbc:oracle:thin:@//localhost:1521/XEPDB1",
                "LOCKOUT_USER","LOCKOUT_PASS");
        conn.setAutoCommit(false);

        System.out.println("DB Writer Running...");

        while (true) {
            ConsumerRecords<String,String> recs = c.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String,String> r : recs) {
                String user = r.key();
                System.out.println("Got the event for : " + user);
                System.out.println("Got the event data : " + r.value());

                int failed = extractFailed(r.value());

                System.out.println("Got the r.value failed value : " + failed);


                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE USERS SET LOCK_STATUS='LOCKED',FAILED_COUNT=?,UPDATED_AT=SYSTIMESTAMP WHERE USERNAME=?")) {
                    ps.setInt(1, failed);
                    ps.setString(2, user);
                    ps.executeUpdate();
                    System.out.println("User DB lock status is set");
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO LOCKOUT_EVENTS (USERNAME,FAILED_COUNT,RAW_EVENT) VALUES (?,?,?)")) {
                    ps.setString(1, user);
                    ps.setInt(2, failed);
                    ps.setString(3, r.value());
                    ps.executeUpdate();
                    System.out.println("User DB LOCKOUT_EVENTS is added");

                }
                conn.commit();
            }
        }
    }

    static KafkaConsumer<String,String> consumer() {
        Properties p = new Properties();
        p.put("bootstrap.servers","localhost:9092");
        p.put("group.id","writer");
        p.put("auto.offset.reset","earliest");
        p.put("enable.auto.commit","false");
        p.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        return new KafkaConsumer<>(p);
    }

    static int extractFailed(String json) {
        return Integer.parseInt(json.split("failedCount\":")[1].replace("}",""));
    }
}

