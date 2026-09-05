# Redis High Availability (Sentinel & Dynamic Failover)

This guide documents the enterprise High Availability (HA) architecture for the Redis Token Bucket state store, eliminating single points of failure (SPOF) via automated master-replica replication, quorum-based health monitoring, and zero-touch failover.

---

## 1. High Availability Architecture

In the standard standalone configuration, Redis is a Single Point of Failure (SPOF). If the single Redis process crashes or is rebooted, all rate-limiting operations fail.

The HA architecture distributes duties across 5 nodes:

```
                      ┌───────────────────────────┐
                      │    Spring Cloud Gateway   │
                      │  (Lettuce Sentinel Client)│
                      └───────┬───────────┬───────┘
                              │           │
       1. Query Master &      │           │ 2. Direct Read/Write
          Subscribe to Events │           │    Token Bucket Commands
                              ▼           ▼
                   ┌──────────────────────────────┐
                   │    Sentinel Quorum (3x)      │
                   │  - redis-sentinel-1 (:26379) │
                   │  - redis-sentinel-2 (:26380) │
                   │  - redis-sentinel-3 (:26381) │
                   └──────────────┬───────────────┘
                                  │ Monitored Heartbeats
                                  │ (Ping / Info)
                                  ▼
                   ┌──────────────────────────────┐
                   │      Active Redis Master     │
                   │    rate-limiter-redis-master │
                   └──────────────┬───────────────┘
                                  │
                                  │ Asynchronous Replication
                                  │ (AOF / RDB Streaming)
                                  ▼
                   ┌──────────────────────────────┐
                   │     Hot Standby Replica      │
                   │   rate-limiter-redis-replica │
                   └──────────────────────────────┘
```

---

## 2. Failover State Machine

1. **Subjective Down (`+sdown`)**: If an individual Sentinel does not receive a response to `PING` within 3,000ms (`down-after-milliseconds`), it marks the master as subjectively down.
2. **Objective Down (`+odown`)**: When a quorum of at least 2 out of 3 Sentinels agree that the master is unreachable, the master is flagged as objectively down.
3. **Leader Election**: Sentinels vote using the Raft algorithm to elect an orchestrating leader Sentinel.
4. **Promotion**: The leader promotes `redis-replica` to the new master via `SLAVEOF NO ONE`.
5. **Reconfiguration**: Sentinels broadcast a `+switch-master` event. Lettuce receives this event in real-time over its pub/sub connection and transparently repoints its internal connection pool to the new master IP with zero process restarts.
6. **Self-Healing on Recovery**: When the old `redis-master` recovers, Sentinels automatically issue `REPLICAOF` to re-attach it as a synchronized replica under the new master.

---

## 3. Launching the HA Cluster

To boot the full 5-node HA stack:

```bash
docker compose -f docker-compose.ha.yml up --build -d
```

Check cluster status:
```bash
docker compose -f docker-compose.ha.yml ps
```

Expected output:
```
NAME                         IMAGE                      STATUS                    PORTS
rate-limiter-gateway-ha      api-rate-limiter-gateway   Up (healthy)              0.0.0.0:8080->8080/tcp
rate-limiter-redis-master    redis:7-alpine             Up (healthy)              0.0.0.0:6379->6379/tcp
rate-limiter-redis-replica   redis:7-alpine             Up (healthy)              0.0.0.0:6380->6379/tcp
rate-limiter-sentinel-1      redis:7-alpine             Up                        0.0.0.0:26379->26379/tcp
rate-limiter-sentinel-2      redis:7-alpine             Up                        0.0.0.0:26380->26379/tcp
rate-limiter-sentinel-3      redis:7-alpine             Up                        0.0.0.0:26381->26379/tcp
rate-limiter-zipkin-ha       openzipkin/zipkin:3        Up (healthy)              0.0.0.0:9411->9411/tcp
```

---

## 4. Hands-On Failover Simulation

Experience automated failover in real-time:

### Step 1: Verify current master through Sentinel
Query `redis-sentinel-1` for the active master address:
```bash
docker exec -it rate-limiter-sentinel-1 redis-cli -p 26379 sentinel get-master-addr-by-name mymaster
```
Output:
```
1) "redis-master"
2) "6379"
```

### Step 2: Simulate Master Hardware / Network Failure
Simulate a catastrophic master outage by stopping `rate-limiter-redis-master`:
```bash
docker stop rate-limiter-redis-master
```

### Step 3: Observe Sentinel Failover Logs
Inspect the Sentinel election in real time:
```bash
docker logs -f rate-limiter-sentinel-1
```
You will observe:
```
+sdown master mymaster redis-master 6379
+odown master mymaster redis-master 6379 #quorum 2/2
+try-failover master mymaster redis-master 6379
+vote-for-leader ...
+failover-end master mymaster redis-master 6379
+switch-master mymaster redis-master 6379 redis-replica 6379
```

### Step 4: Verify Gateway Continues Serving Traffic
Execute authenticated API requests against the gateway:
```bash
curl -i -H "X-API-Key: my-secure-api-key" http://localhost:8080/echo/get
```
The gateway responds with `200 OK` and continues rate limiting seamlessly using the newly promoted master!

### Step 5: Self-Healing when Old Master Recovers
Start the old master container back up:
```bash
docker start rate-limiter-redis-master
```
Sentinels automatically detect the recovered node and reconfigure it as a replica of `redis-replica`.
