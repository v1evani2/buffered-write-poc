package com.example.buffered;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class LoginServer {

    private static final Logger LOG = LoggerFactory.getLogger(LoginServer.class);
    private static final int MAX_ATTEMPTS = 5;

    private static JedisPool jedisPool;
    private static LockoutEventProducer producer;
    private static UserDao userDao;

    public static void main(String[] args) throws Exception {

        // DB + Redis + Kafka config
        String jdbcUrl = System.getenv().getOrDefault("ORACLE_JDBC_URL",
                "jdbc:oracle:thin:@//localhost:1521/XEPDB1");
        String dbUser = System.getenv().getOrDefault("ORACLE_DB_USER", "LOCKOUT_USER");
        String dbPass = System.getenv().getOrDefault("ORACLE_DB_PASS", "LOCKOUT_PASS");

        userDao = new UserDao(jdbcUrl, dbUser, dbPass);
        jedisPool = RedisClientProvider.getPool();
        producer = new LockoutEventProducer();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/login", LoginServer::handleLogin);
        server.createContext("/status", LoginServer::handleStatus);
        server.setExecutor(Executors.newFixedThreadPool(64));
        server.start();

        LOG.info("LoginServer listening on http://localhost:8080");
        System.out.println("LoginServer listening on http://localhost:8080...");

    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        try {
            Class.forName("oracle.jdbc.driver.OracleDriver");
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Method Not Allowed\n");
                return;
            }

            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String user = params.get("user");
            String password = params.get("password");

            if (user == null || password == null) {
                send(exchange, 400, "Missing user/password\n");
                return;
            }

            try (Jedis jedis = jedisPool.getResource()) {
                String failKey = "login:fail:" + user;
                String lockKey = "login:lock:" + user;

                // Fast lock check
                if (jedis.exists(lockKey)) {
                    send(exchange, 423, "USER_LOCKED\n");
                    return;
                }

                UserRecord rec = userDao.findByUsername(user);
                if (rec == null) {
                    send(exchange, 404, "USER_NOT_FOUND\n");
                    return;
                }

                if (rec.isLocked()) {
                    jedis.set(lockKey, "1");
                    send(exchange, 423, "USER_LOCKED\n");
                    return;
                }

                // Password check (plain-text demo; you know to hash in real world)
                if (password.equals(rec.password())) {
                    jedis.del(failKey);
                    jedis.del(lockKey);
                    send(exchange, 200, "LOGIN_SUCCESS\n");
                    return;
                }

                // Wrong password
                long newCount = jedis.incr(failKey);
                if (newCount >= MAX_ATTEMPTS) {
                    jedis.set(lockKey, "1");
                    try {
                        producer.sendLockoutEvent(user, (int) newCount);
                    } catch (Exception e) {
                        LOG.error("Failed to send Kafka lockout event for {}", user, e);
                    }
                    send(exchange, 423, "USER_LOCKED_AFTER_5_INVALID_ATTEMPTS\n");
                } else {
                    send(exchange, 401, "INVALID_PASSWORD attempt=" + newCount + "\n");
                }
            }
        } catch (Exception e) {
            System.out.println(e);
            LOG.error("Error in /login", e);
            send(exchange, 500, "INTERNAL_ERROR: " + e.getMessage() + "\n");
        }
    }

    private static void handleStatus(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                send(exchange, 405, "Method Not Allowed\n");
                return;
            }

            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String user = params.get("user");
            if (user == null) {
                send(exchange, 400, "Missing user\n");
                return;
            }

            StringBuilder sb = new StringBuilder();

            UserRecord rec = userDao.findByUsername(user);
            if (rec == null) {
                sb.append("DB.USER=NOT_FOUND\n");
            } else {
                sb.append("DB.USERNAME=").append(rec.username()).append("\n");
                sb.append("DB.LOCK_STATUS=").append(rec.lockStatus()).append("\n");
                sb.append("DB.FAILED_COUNT=").append(rec.failedCount()).append("\n");
            }

            try (Jedis jedis = jedisPool.getResource()) {
                String failKey = "login:fail:" + user;
                String lockKey = "login:lock:" + user;
                sb.append("REDIS.FAIL_COUNT=").append(jedis.get(failKey)).append("\n");
                sb.append("REDIS.LOCK_FLAG=").append(jedis.get(lockKey)).append("\n");
            }

            send(exchange, 200, sb.toString());
        } catch (Exception e) {
            LOG.error("Error in /status", e);
            send(exchange, 500, "INTERNAL_ERROR: " + e.getMessage() + "\n");
        }
    }

    // ---------- Helper methods ----------

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String param : query.split("&")) {
            String[] pair = param.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length > 1
                    ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
                    : "";
            result.put(key, value);
        }
        return result;
    }
}
