package com.example.buffered;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class RedisClientProvider {
    private static JedisPool pool;

    public static synchronized JedisPool getPool() {
        if (pool == null) {
            pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379);
        }
        return pool;
    }
}

