
---

# High-Performance Trading Aggregator

## Overview

This project implements a **high-performance in-memory trading data aggregator**. It supports real-time processing of large batches of trading prices, computing rolling statistics over configurable sliding windows (`10^1` to `10^8` values). The system is designed for **eventual consistency** and high throughput with multiple symbols and high-frequency updates.

The system exposes a **REST API** via Quarkus for batch insertion and statistics retrieval.

---

## Architecture

### SymbolAggregator

`SymbolAggregator` is the core component responsible for a **single financial symbol**:

* Maintains a **ring buffer** for all received prices (to efficiently evict old values).
* Keeps **rolling statistics** (min, max, last, average, variance) for each window size `10^k`.
* Uses **monotonic deques** for O(1) amortized min/max updates.
* Processes incoming batches asynchronously from a **batch queue** (`BatchQueue`).
* **Snapshots** of the latest statistics are maintained using `AtomicReference` for lock-free reads.
* Guarantees **O(1) enqueue** and **O(1) read** of the latest snapshot.

Workflow:

1. `enqueueBatch(values)` adds a new batch of prices to the internal queue.
2. A worker thread or thread pool **consumes batches asynchronously**, calling `processValue` for each price.
3. `processValue` updates rolling sums, variance, monotonic min/max deques, and ring buffer.
4. `updateSnapshots` publishes the latest statistics snapshot for each window `k`.

---

### GlobalDispatcher / Thread Pool

To efficiently handle multiple symbols with limited CPU cores:

* A **global thread pool** is created with size equal to the number of available CPU cores.
* SymbolAggregators **do not spawn one thread per symbol**; instead, they act as **jobs in a dispatcher queue**.
* The dispatcher **assigns available threads** to process pending batches for different symbols.
* Ensures **high CPU utilization** without oversubscription, even with hundreds of symbols.

```
+------------------------+
| REST API / Producers   |
| (addBatch calls)       |
+-----------+------------+
            |
            v
+-----------+------------+
| SymbolAggregator Pool  |  <-- all symbols act as "jobs"
+-----------+------------+
            |
            v
+-----------+------------+
| Dispatcher / Work Queue|
+-----------+------------+
            |
   +--------+--------+--------+
   |        |        |        |
   v        v        v        v
+-----+  +-----+  +-----+  +-----+
|Thread| |Thread| |Thread| |Thread|  <-- N threads = CPU cores
|  #1  | |  #2  | |  #3  | |  #4  |
+-----+  +-----+  +-----+  +-----+
   |        |        |        |
   +--------+--------+--------+
            |
            v
+------------------------+
| SymbolAggregator.run() |  <-- processes pending batches
+------------------------+
```


Workflow:

1. Each `SymbolAggregator` adds batches to its queue.
2. The dispatcher monitors all symbol queues.
3. Idle threads take the next `SymbolAggregator` job and process pending batches.
4. Processing is asynchronous, so `enqueueBatch` returns immediately, achieving **eventual consistency**.

````
+-----------------+
|  enqueueBatch() |
|  (incoming batch|
|   of prices)    |
+--------+--------+
         |
         v
+--------+--------+
|   BatchQueue    |  <-- stores batches for async processing
+--------+--------+
         |
         v
+--------+--------+
| Worker Thread   |  <-- single thread per job in pool
|  (run())        |
+--------+--------+
         |
         v
+--------+--------+   +----------------+
| processValue()  |-->| RingBuffer     |  <-- stores all prices for eviction
+--------+--------+   +----------------+
         |
         v
+--------+--------+
| updateSnapshots() |
|   (atomic refs)  |
+--------+--------+
         |
         v
+--------+--------+
|  getStats(k)    |  <-- returns latest snapshot (O(1))
+-----------------+
````

---

## REST API

### 1. Add Batch

```
POST /add_batch/
Content-Type: application/json
```

**Request JSON:**

```json
{
  "symbol": "AAPL",
  "values": [120.5, 121.0, 119.8, 122.3]
}
```

**Response JSON:**

```json
{
  "message": "Batch added for AAPL",
  "size": 4
}
```

### 2. Get Statistics

```
GET /stats/?symbol=AAPL&k=3
```

* `symbol`: financial instrument symbol
* `k`: window exponent (1–8), representing `10^k` last values

**Response JSON:**

```json
{
  "min": 119.8,
  "max": 122.3,
  "last": 122.3,
  "avg": 120.95,
  "var": 1.47,
  "count": 4
}
```

---

## Usage

1. Build and run with Maven (install Maven before https://maven.apache.org/install.html):

```bash
mvn clean package && mvn quarkus:dev
```

2. Add a batch using `curl`:

```bash
curl -X POST http://localhost:8080/add_batch/ \
  -H "Content-Type: application/json" \
  -d '{"symbol":"AAPL","values":[120.5,121.0,119.8,122.3]}'
```

3. Retrieve statistics:

```bash
curl "http://localhost:8080/stats/?symbol=AAPL&k=3"
```

---

## Performance Considerations

* **RingBuffer** efficiently stores all values and evicts old ones once the sliding window exceeds its capacity.
* **Monotonic deques** maintain min/max in amortized O(1) per insertion.
* **BatchQueue + thread pool** decouples producers (API) and consumers (workers), ensuring high throughput and low latency.
* Designed for **eventual consistency**, suitable for high-frequency trading simulations.

---

## Notes

* in case of using java 17+ user should use the following options for the non-heap data structures: https://github.com/OpenHFT/OpenHFT/blob/ea/docs/Java-Version-Support.adoc
* `capacity` of RingBuffer must be at least as large as the largest window (`10^8`) to avoid overwriting data prematurely.
* For **stronger consistency**, you could add a synchronous flush after `enqueueBatch`, but this would reduce throughput.
* Supports **multiple symbols**, and the dispatcher ensures optimal CPU utilization.

---


## Performance for 8 cpu and 16gb mem

---

### CPU

* Each worker just **iterates over batches and updates sums/deques** — operations are fully **CPU-bound**, almost lock-free.

### Memory

* The RingBuffer stores **each value as a double** → 8 bytes per element.
* Maximum window = `10^8` → 800 MB per symbol.
* For 8 symbols → \~6.4 GB just for ring buffers.
* Plus BatchQueue, deques, sum/sumSq arrays, snapshots, and some GC overhead.
* 16 GB RAM is enough with headroom for 8 symbols and a `10^8` window, but with 50–100 symbols, OOM may occur quickly.

---

### Limitations

1. **RAM** — the most critical constraint for large windows and many symbols.
2. **BatchQueue** — under extreme load, the queue can overflow → either increase its capacity or drop old batches.
3. **GC** — intensive object allocation (`Batch`, `Deque<long[]>`) may trigger frequent pauses, especially with large batches and hundreds of symbols.

---

### Summary

| Parameter               | Approximate Limits on 8 CPU / 16 GB RAM    |
| ----------------------- | ------------------------------------------ |
| Number of symbols       | up to 10–15 with window `10^8` without OOM |
| Window size             | up to 10^8 values                          |
| Throughput (values/sec) | 100–150M (with optimal batch size)         |
| Batch size              | 1k–10k optimal                             |

**Scaling recommendations:**

* Limit the number of symbols in memory or store older data off-heap.
* Tune **GC for low-latency** (G1/ZGC).
* Adjust **batch size** to minimize queue overhead.

