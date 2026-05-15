# RETRY_AND_RESULT.md

The two cross-cutting concerns that touch every layer:

1. **`Result<T>`** as the return type for any operation that can fail in
   ways the caller might want to react to (instead of throwing).
2. **Retry policies** at every layer where retry makes sense, with
   explicit policy on what's retryable and what isn't.

This document is the canonical reference. CLAUDE.md enforces the rules;
this document explains them.

---

## 1. The `Result<T>` sealed type

Java 21 sealed interfaces let us model success/failure as a closed type:

```java
public sealed interface Result<T> permits Result.Success, Result.Failure {

    record Success<T>(T value) implements Result<T> { ... }
    record Failure<T>(EnrichmentError error) implements Result<T> { ... }

    static <T> Result<T> success(T value)        { return new Success<>(value); }
    static <T> Result<T> failure(EnrichmentError e) { return new Failure<>(e); }

    boolean isSuccess();
    Optional<T> value();
    Optional<EnrichmentError> error();
    <U> Result<U> map(Function<T, U> mapper);
    <U> Result<U> flatMap(Function<T, Result<U>> mapper);
    T getOrElse(T fallback);
    T getOrThrow();              // throws IllegalStateException on Failure
}
```

Pattern matching makes call sites readable:

```java
return switch (idempotencyStore.tryClaim(req)) {
    case Result.Success<ClaimResult>(var claim) when claim.isClaimed() ->
        runCascadeAndPersist(req);
    case Result.Success<ClaimResult>(var claim) ->
        loadCachedResult(claim.idempotencyKey());
    case Result.Failure<ClaimResult>(var error) ->
        Result.failure(error);
};
```

### When to return Result vs throw

| Situation | Use |
|---|---|
| Domain-meaningful failure (cache miss, low confidence, idempotency duplicate) | `Result.failure(...)` |
| Infrastructure failure caller can recover from (network, DB, sidecar) | `Result.failure(...)` |
| Bug in the program (null where forbidden, illegal state) | `throw IllegalStateException` |
| Programmer error (wrong type, wrong arg) | `throw IllegalArgumentException` |
| OOM, classloader explosion, JVM-level | let it propagate |

The principle: if a reasonable caller might want to do something other
than crash, return `Result`. If the only sensible reaction is "crash and
let the framework restart me", throw.

---

## 2. The `EnrichmentError` taxonomy

A flat enum of error categories, plus a structured payload:

```java
public record EnrichmentError(
    Category category,
    String message,
    String correlationId,
    Map<String, Object> context,
    Throwable cause
) {
    public enum Category {
        // Transient - retry may help
        TIMEOUT,
        NETWORK,
        UPSTREAM_RATE_LIMITED,
        UPSTREAM_UNAVAILABLE,
        DATABASE_DEADLOCK,
        DATABASE_CONNECTION,

        // Permanent - retry won't help
        BAD_INPUT,
        UNAUTHORISED,
        FORBIDDEN,
        SCHEMA_MISMATCH,
        UNSUPPORTED_OPERATION,
        VALIDATION,

        // Domain
        CASCADE_NO_RESULT,
        CONFIDENCE_BELOW_THRESHOLD,
        REQUIRED_FIELD_MISSING,
        IDEMPOTENCY_DUPLICATE,
        EXCEPTION_QUEUE_LOCKED,

        // Resilience
        CIRCUIT_OPEN,
        BULKHEAD_FULL,
        SUBSCRIBER_OVERFLOW,

        // Catch-all
        UNKNOWN
    }

    public boolean isRetryable() {
        return switch (category) {
            case TIMEOUT, NETWORK, UPSTREAM_RATE_LIMITED, UPSTREAM_UNAVAILABLE,
                 DATABASE_DEADLOCK, DATABASE_CONNECTION -> true;
            default -> false;
        };
    }
}
```

`isRetryable()` is the single source of truth for whether a retry layer
should reattempt the call.

---

## 3. Retry layers

There are FIVE places in the request path where retry happens. Each has
a clear scope, a clear policy, and a clear interaction with the others.

```
                   ┌──────────────────────────────┐
                   │ 5. Channel-native redelivery │
                   │    (Kafka rewind, RabbitMQ    │
                   │     HTTP client retry)       │
                   └──────────────┬───────────────┘
                                  │
                                  ▼
                   ┌──────────────────────────────┐
                   │ 4. Service-level             │
                   │    AddressEnrichmentService  │
                   │    retries on idempotency    │
                   │    race only                 │
                   └──────────────┬───────────────┘
                                  │
                                  ▼
                   ┌──────────────────────────────┐
                   │ 3. Persistence retry         │
                   │    Oracle deadlock retry,    │
                   │    HikariCP connection retry │
                   └──────────────┬───────────────┘
                                  │
                                  ▼
                   ┌──────────────────────────────┐
                   │ 2. Per-structurer retry      │
                   │    Resilience4j @Retry       │
                   │    on AddressStructurer call │
                   └──────────────┬───────────────┘
                                  │
                                  ▼
                   ┌──────────────────────────────┐
                   │ 1. Per-RPC retry             │
                   │    gRPC interceptor,         │
                   │    WebClient retryWhen       │
                   └──────────────────────────────┘
```

### Layer 1: Per-RPC retry (lowest)

**Scope:** A single network call to a sidecar (gRPC) or external HTTP
endpoint (LLM gateway).

**Policy:**
- Max attempts: 2 (so 1 retry after first failure)
- Backoff: 50ms initial, 2x multiplier
- Retry only on:
  - gRPC `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`
  - HTTP 502, 503, 504, 429 (with `Retry-After` honoured)
  - Connection-level exceptions (`ConnectException`, `TimeoutException`)
- Never retry on:
  - HTTP 4xx (except 429)
  - gRPC `INVALID_ARGUMENT`, `PERMISSION_DENIED`, `UNAUTHENTICATED`

**Implementation:**
- gRPC: `RetryPolicy` in service config (`grpc.retry-policy`)
- HTTP: `WebClient.retryWhen(Retry.backoff(...))`

**Result on exhaustion:** the call returns `Result.failure(error)` where
`error.category` is `TIMEOUT` or `UPSTREAM_UNAVAILABLE`.

### Layer 2: Per-structurer retry (Resilience4j)

**Scope:** One full structurer call, including any retries at Layer 1.

**Policy:**
- Per structurer config in `application.yml`
- Default: max 2 attempts, 100ms initial backoff
- Retry only when `Result.failure().error.isRetryable() == true`
- Wrapped with circuit breaker: when breaker open, skip retry entirely

**Implementation:** `@Retry(name="<structurer>-retry", fallbackMethod="onRetryExhausted")`
on the structurer's `structure(...)` method, with a fallback that
returns `StructuringResult.empty(...)`.

**Result on exhaustion:** the structurer returns an empty
`StructuringResult` with diagnostics about why. The cascade orchestrator
moves to the next structurer.

### Layer 3: Persistence retry

**Scope:** A single Oracle DML statement or transaction.

**Policy:**
- Spring `@Retryable(retryFor = {DeadlockLoserDataAccessException.class,
  TransientDataAccessException.class, CannotAcquireLockException.class})`
- Max attempts: 3
- Backoff: 50ms initial, 2x multiplier with 100ms jitter
- Never retry on:
  - `DataIntegrityViolationException` (e.g. unique-key violation —
    that's the success signal for INSERT-first idempotency)
  - `BadSqlGrammarException` (programmer error)

**Result on exhaustion:** throws the underlying exception, which the
service layer translates into `Result.failure(DATABASE_DEADLOCK)` or
similar.

### Layer 4: Service-level retry

**Scope:** The cross-replica idempotency race window.

**Policy:** When the service tries to load a cached result for a
`PERSISTED_DUPLICATE` claim but the result row hasn't been committed
yet by the winning replica:

- Max attempts: 3
- Backoff: 10ms, 50ms, 200ms (linear; total 260ms ≤ caller SLO)
- Retry only on `findCachedResultRowId(...)` returning empty

**Result on exhaustion:** `Result.failure(IDEMPOTENCY_DUPLICATE)` with
context indicating the cache load timed out. The caller (channel
adapter) can decide: HTTP returns 409 with retry advice, Kafka NACKs
to trigger redelivery, RabbitMQ nacks for redelivery.

### Layer 5: Channel-native redelivery (highest)

**Scope:** End-to-end message delivery, including any service failures.

**Policy depends on the channel:**

- **HTTP**: client controls; server returns appropriate status code
  (5xx for retryable, 4xx for permanent). Server does not buffer for
  retry — that's the client's job.
- **Kafka**: manual `Acknowledgment.acknowledge()` only after Oracle
  commit. On exception, `acknowledge()` is not called; the consumer
  rewinds on next poll. After 3 redeliveries (configured at the
  listener container level via `DefaultErrorHandler`), the record is
  forwarded to the DLT topic for human review.
- **MQ**: client-mode acknowledgement. On exception, the message
  rolls back to the queue. After `BackoutCount` exceeds threshold
  (default 3), it goes to the DLQ.

**Result:** at-least-once delivery from the channel + idempotency
table = exactly-once processing.

---

## 4. Concrete retry config in application.yml

```yaml
resilience4j:
  retry:
    instances:
      libpostal-retry:
        max-attempts: 2
        wait-duration: 50ms
        exponential-backoff-multiplier: 2.0
        retry-exceptions:
          - io.grpc.StatusRuntimeException
          - java.util.concurrent.TimeoutException
        ignore-exceptions:
          - com.jpmc.tfpm.address.domain.NonRetryableException
      swift-crf-retry:
        max-attempts: 2
        wait-duration: 100ms
        exponential-backoff-multiplier: 2.0
      llm-retry:
        max-attempts: 2
        wait-duration: 200ms
        exponential-backoff-multiplier: 2.0
        retry-exceptions:
          - org.springframework.web.reactive.function.client.WebClientResponseException$ServiceUnavailable
          - org.springframework.web.reactive.function.client.WebClientResponseException$BadGateway
          - org.springframework.web.reactive.function.client.WebClientResponseException$GatewayTimeout
          - org.springframework.web.reactive.function.client.WebClientRequestException
          - java.util.concurrent.TimeoutException

  circuitbreaker:
    instances:
      libpostal-cb:
        failure-rate-threshold: 50
        slow-call-rate-threshold: 50
        slow-call-duration-threshold: 1s
        sliding-window-size: 100
        minimum-number-of-calls: 20
        wait-duration-in-open-state: 30s
      llm-cb:
        failure-rate-threshold: 50
        sliding-window-size: 50
        minimum-number-of-calls: 10
        wait-duration-in-open-state: 60s

  bulkhead:
    instances:
      libpostal-bh:
        max-concurrent-calls: 50
        max-wait-duration: 100ms
      llm-bh:
        max-concurrent-calls: 20
        max-wait-duration: 200ms

spring:
  kafka:
    listener:
      ack-mode: manual_immediate
      type: batch
  retry:
    enabled: true
    properties:
      max-attempts: 3
      initial-interval: 50ms
      multiplier: 2.0
      max-interval: 500ms

# Oracle deadlock retry
spring.transaction.default-timeout: 5s
```

---

## 5. The success/failure return contract

Every public method on every domain interface returns either:

- A direct value (when failure is impossible by construction — e.g.
  `RawAddress.canonical()` always succeeds)
- A `Result<T>` (when the operation can fail in any of the categories above)
- A `Flow.Publisher<T>` for streaming (which itself emits `Result`-typed
  items where appropriate)

Method names follow conventions:

- `canonical()` returns `String` (cannot fail)
- `enrich(req)` returns `Result<EnrichmentResult>` (network, DB, validation)
- `tryClaim(req)` returns `Result<ClaimResult>` (DB)
- `findCachedResultRowId(key)` returns `Result<Optional<Long>>` (DB)
- `complete(req)` (LLM) returns `Result<LlmCompletionResponse>` (network, parse)
- `stream(req)` (LLM) returns `Flow.Publisher<Result<LlmStreamChunk>>`

The cascade orchestrator catches `Result.failure` from each structurer
and, instead of propagating, records it in the per-structurer trace and
moves on. The orchestrator's own return is `Result<StructuredAddress>`
which is `Failure(CASCADE_NO_RESULT)` only if every structurer failed.

---

## 6. Translating Result to channel-native responses

### HTTP

```
Result.Success                           → 200 OK + JSON body
Result.Failure(IDEMPOTENCY_DUPLICATE)    → 200 OK + JSON body (cached result)
Result.Failure(BAD_INPUT)                → 400 Bad Request
Result.Failure(UNAUTHORISED)             → 401 Unauthorised
Result.Failure(FORBIDDEN)                → 403 Forbidden
Result.Failure(VALIDATION)               → 422 Unprocessable Entity
Result.Failure(CIRCUIT_OPEN)             → 503 Service Unavailable + Retry-After
Result.Failure(BULKHEAD_FULL)            → 503 Service Unavailable + Retry-After
Result.Failure(TIMEOUT)                  → 504 Gateway Timeout
Result.Failure(*) other retryable        → 503 Service Unavailable + Retry-After
Result.Failure(*) other non-retryable    → 500 Internal Server Error
```

The HTTP controller does this translation in a single `mapToHttpResponse(Result)`
method, never inline in handler methods.

### Kafka

```
Result.Success                           → ack(), publish output, log INFO
Result.Failure(IDEMPOTENCY_DUPLICATE)    → ack() (already processed, no-op)
Result.Failure(*) retryable              → don't ack; consumer rewinds on next poll
Result.Failure(*) non-retryable          → ack() + send to DLT topic with reason
```

After 3 retries (Spring `DefaultErrorHandler` config), the record auto-routes
to the DLT.

### RabbitMQ (AMQP)

```
Result.Success                           → basicAck, publish output
Result.Failure(IDEMPOTENCY_DUPLICATE)    → basicAck (already processed)
Result.Failure(*) retryable              → basicNack(requeue=true); broker redelivers
Result.Failure(*) non-retryable          → basicNack(requeue=false); routes to DLQ
```

After 3 redeliveries (x-delivery-count header), RabbitMQ routes to the DLQ
via the dead-letter exchange.

---

## 7. The non-obvious design decisions

### Why no XA transactions

XA across RabbitMQ + Oracle would give exactly-once at the cost of:
- Coordinator setup complexity
- Two-phase commit overhead per message
- Recovery complexity when coordinator state is lost

Instead, idempotency table + manual ack gives exactly-once *processing*
(which is what we actually need) without the operational cost.

### Why retries are bounded so low

Default max-attempts = 2 (one retry) at most layers. This is deliberate:
- Sidecar SLOs are tight (500ms) — long retry chains blow them
- Distributed retry storms are real; bounded retries at each layer
  prevent N×M×K total attempts when components fail simultaneously
- Channel-native redelivery is the long-haul retry mechanism (Kafka
  rewind has unlimited attempts within retention; MQ has BackoutCount
  config). Per-call retry is for transient blips, not for outages.

### Why Result is in domain (not a third-party Either/Try)

The domain module has zero third-party dependencies (other than SLF4J).
A custom sealed `Result` keeps this invariant. It also lets us model
the exact `EnrichmentError` taxonomy we care about, rather than
shoehorning into a generic `Throwable` or `String`.

### Why `EnrichmentError.isRetryable()` lives on the error, not on call sites

So that retry policy is data, not code. A new error category gets the
right treatment in every layer automatically, just by setting the
flag in `isRetryable()`. Call sites stay simple: `if (error.isRetryable())
{ retry } else { fail fast }`.

### Why we don't retry on idempotency duplicate

Because there's nothing to retry — the work is already done. The
service loads the cached result and returns Success. The only retry
case is the rare race where we hit DUPLICATE but the cached result
hasn't committed yet (Layer 4 above), and that's a tight 260ms loop.
