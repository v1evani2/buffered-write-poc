# Buffered Write Demo – Redis + Kafka + Oracle

This repo demonstrates a realistic **buffered write** pattern for login lockout:

- **Oracle**: Authoritative `USERS` table (username, password, lock_status) + `LOCKOUT_EVENTS` audit log.
- **Redis**: Fast path for failed-attempt counters and lock flags.
- **Kafka**: WAL buffer for lock events.
- **LoginServer**:
  - Reads user/password + lock status from Oracle.
  - Uses Redis to track failed attempts and lock flag.
  - On 5th invalid password, sets lock in Redis and emits a Kafka lockout event.
- **LockoutDbWriter**:
  - Consumes lock events from Kafka.
  - Updates `USERS.LOCK_STATUS = 'LOCKED'`.
  - Inserts into `LOCKOUT_EVENTS`.

## Architecture quick view

The **request path never writes to Oracle** – all writes are buffered through Kafka. Buffered write architecture moved the concurrency control out of the DB and into:
  - Redis atomic increments
  - Kafka’s ordered log
  - The single-threaded consumer model

DB row versioning is only needed if the DB itself is participating in concurrency.

A resilient, conflict-free pipeline for handling high-volume concurrent login attempts:
```
Concurrent Login Attempts
        │
        ▼
 Redis Atomic Counters
        │
        ▼
 Kafka Serialized Events
        │
        ▼
 DB Writer (Single Consumer)
        │
        ▼
 Oracle UPDATE (No Conflict)
```
## Prereqs

- Java 17+
- Maven 3.8+
- Docker + Docker Compose

## Start Infra

From the project root:

  docker-compose up -d

This starts Redis, Zookeeper, Kafka, and Oracle XE with schema + seed user.

## Build

  mvn clean package

## Run Login Server

  mvn -q exec:java -Dexec.mainClass=com.example.buffered.LoginServer

## Run Async DB Writer

In another terminal:

  mvn -q exec:java -Dexec.mainClass=com.example.buffered.LockoutDbWriter

## Test

Wrong password attempts:

  curl "http://localhost:8080/login?user=john&password=wrong"

Correct password:

  curl "http://localhost:8080/login?user=john&password=password123"

Status:

  curl "http://localhost:8080/status?user=john"

You should see Redis counters go up, user lock flag set, and eventually DB lock status updated by the async writer.


