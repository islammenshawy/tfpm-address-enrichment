# LLM_MODEL_INTEGRATION.md

The `LlmModelClient` abstraction lets the LLM cascade stage call any
model — JPMC's internal LLM gateway, an OpenAI-compatible endpoint, a
local Ollama instance for dev — via configuration only. Switching models
requires zero code changes.

This document covers the contract, the two delivery modes (stream and
request/response), the retry semantics, and the JPMC-network-specific
considerations (proxy buffering, internal gateway authentication).

---

## 1. The contract

```java
package com.jpmc.tfpm.address.domain;

@ThreadSafe
public interface LlmModelClient {

    /** Stable identifier for this client. Used in metrics, logs, audit. */
    String name();

    /** What this model can do. */
    LlmModelMetadata metadata();

    /**
     * Synchronous request/response mode. Returns the full response as a
     * single Result. Suitable for short completions where streaming
     * provides no UX value (e.g. structurer output).
     *
     * MUST be thread-safe. MUST honour the request's per-call timeout.
     * MUST return Result.failure on infrastructure error rather than
     * throwing — the caller's retry policy decides what to do next.
     */
    Result<LlmCompletionResponse> complete(LlmCompletionRequest request);

    /**
     * Streaming mode. Returns a Flow.Publisher of partial chunks. Caller
     * subscribes; chunks arrive as they are produced server-side.
     *
     * Used by the future maker/checker UI for progressive rendering and
     * by long-running RAG flows. The address structurer cascade does NOT
     * use this — it uses complete(...) — because the cascade is bounded
     * to 500ms total.
     *
     * If the model does not support streaming (metadata().supportsStreaming()
     * returns false), this method MUST throw UnsupportedOperationException
     * synchronously, NOT silently fall back to complete(...).
     */
    Flow.Publisher<LlmStreamChunk> stream(LlmCompletionRequest request);
}
```

---

## 2. Why this abstraction exists

JPMC's LLM landscape is moving fast. In a 12-month window we can expect:

- The internal LLM gateway adds new models (different sizes, different vendors).
- A specific structurer might benefit from a different model than the rest
  of the platform.
- Vendor evaluations (e.g. trying a regional model for UAE addresses)
  require A/B-able switching without redeploys.
- Cost/latency tuning may move workloads between fast/cheap and
  slow/accurate models on the fly.

By depending on `LlmModelClient` and not on any specific HTTP client or
SDK, the structurer code remains untouched through all of these changes.
A new model is a new bean implementing the interface, plus a config flag.

---

## 3. Configuration

Models are declared in `application.yml` and selected by name:

```yaml
llm:
  default-client: jpmc-internal-gateway

  clients:
    jpmc-internal-gateway:
      type: jpmc-internal-gateway
      endpoint: ${LLM_GATEWAY_ENDPOINT}
      model: gpt-4o-2024-08-06
      mode: SYNC                    # SYNC | STREAM
      timeout-ms: 2000
      max-retries: 2
      retry-backoff-ms: [100, 500, 2000]
      auth:
        type: oauth2-client-credentials
        token-endpoint: ${LLM_TOKEN_ENDPOINT}
        client-id-secret: vault:llm/client-id
        client-secret-secret: vault:llm/client-secret

    openai-compatible-fallback:
      type: openai-compatible
      endpoint: https://api.openai.com/v1
      model: gpt-4o-mini
      mode: SYNC
      timeout-ms: 3000
      auth:
        type: bearer
        token-secret: vault:openai/api-key

    local-ollama-dev:
      type: openai-compatible       # Ollama exposes OpenAI-compatible API
      endpoint: http://localhost:11434/v1
      model: llama3.1:8b
      mode: STREAM
      timeout-ms: 30000

# Per-structurer assignment. The address-structuring use case uses sync mode.
enrichment:
  llm:
    enabled: true
    client: jpmc-internal-gateway   # references llm.clients.<name>
    fields-allowed: [CTRY, TWN_NM, PST_CD, CTRY_SUB_DVSN, STRT_NM, BLDG_NB, BLDG_NM]
    prompt:
      template-resource: classpath:prompts/address-structuring.json
```

Switching models is a config change. Adding a new model type (a new
implementation of `LlmModelClient`) is a new class in `adapter-llm` and
a new `case` in the factory.

---

## 4. Built-in implementations (drop in CLAUDE Code)

The bundle ships two reference implementations:

### 4a. `JpmcInternalGatewayLlmClient`

Default. Talks to the JPMC internal LLM gateway. Handles:

- OAuth2 client-credentials token acquisition with rotation
- The internal gateway's chat-completions API shape
- Streaming via SSE (Server-Sent Events) when `mode: STREAM`
- ProxySG/ICAP buffering workaround: explicit `Connection: close`
  on streaming requests, plus a small ping payload every 5 seconds to
  keep the proxy from holding the response
- Distributed tracing context propagation via standard headers

### 4b. `OpenAiCompatibleLlmClient`

For any endpoint that speaks the OpenAI chat-completions API: OpenAI
itself, Anthropic via OpenAI-compat shim, Together.ai, vLLM, Ollama,
LocalAI. Bearer auth, JSON in / JSON or SSE out.

Useful for local dev (`ollama` on a laptop), for vendor evaluations, and
as a fallback when the internal gateway is offline.

### 4c. Adding a new implementation

1. New class in `adapter-llm`, implementing `LlmModelClient`.
2. Annotate `@Component`, `@ThreadSafe`.
3. Register in the `LlmModelClientFactory` switch on `type`.
4. Add config schema docs to this file.
5. Done — structurers use it via name, no code change in structurer.

---

## 5. Streaming concerns

### Why streaming matters

For the **address structurer**, streaming does not matter — completions
are short (<200 tokens), the cascade is bounded to 500ms, and the
structurer needs the full JSON response to parse before passing to the
merger. This use case calls `complete(...)`.

For **future use cases** (RAG-based maker/checker assistant, longer
generative explanations of why an address was rejected), streaming
significantly improves perceived latency. These call `stream(...)` and
flush chunks to the UI as they arrive.

### JPMC-specific streaming gotchas

JPMC's network has ProxySG and ICAP scanning between most clients and
external endpoints. These can buffer streaming HTTP responses,
defeating the point of SSE.

`JpmcInternalGatewayLlmClient` mitigates by:

- Routing through the **internal** gateway, not directly to vendors —
  the gateway handles the chunk-flushing pattern that proxies tolerate.
- Sending `Accept: text/event-stream` and `Cache-Control: no-cache,
  no-transform` to discourage middlebox buffering.
- Setting `Connection: close` on streaming requests so middleboxes don't
  keep the connection in a buffered state.
- Issuing a heartbeat read every 5 seconds; if no data has been seen
  for >15 seconds, the client closes the connection and the caller's
  retry policy kicks in.

For local dev with Ollama, none of this matters — direct connection,
no proxy. The `OpenAiCompatibleLlmClient` does basic SSE without the
middlebox-defeating workarounds.

### Backpressure

`Flow.Publisher` provides reactive-streams backpressure. The caller
subscribes with a request count; the client writes chunks as fast as
the subscriber consumes. The HTTP/2 layer handles flow control on the
underlying connection.

If the subscriber falls behind by more than `llm.streaming.max-buffer`
chunks (default 256), the client sends `cancel` to the model and
returns a `Result.failure(LlmError.SUBSCRIBER_OVERFLOW)`.

---

## 6. Retry, timeout, and circuit breaking

Three layers of resilience, configured per client:

### Layer 1: per-call timeout (always)

Hard timeout on a single HTTP request, set on the underlying WebClient.
On expiry, the `complete(...)` call returns `Result.failure(LlmError.TIMEOUT)`.

### Layer 2: per-client retry (Resilience4j)

Configured via `llm.clients.<name>.max-retries` and `retry-backoff-ms`.
Retries on:

- `LlmError.TIMEOUT`
- `LlmError.NETWORK` (connection refused, DNS failure)
- HTTP 502, 503, 504
- HTTP 429 (rate limit) with backoff honouring `Retry-After` header

Does NOT retry on:

- HTTP 400 (bad request — likely prompt issue, retry won't help)
- HTTP 401, 403 (auth — retry won't help, may aggravate rate limits)
- HTTP 5xx other than 502/503/504 (server-side error not classified
  as transient)
- Subscriber overflow (caller's problem, not network's)

### Layer 3: per-client circuit breaker (Resilience4j)

When the failure rate exceeds `llm.clients.<name>.cb.failure-rate-threshold`
(default 50%) over a sliding window, the breaker opens. All subsequent
calls return `Result.failure(LlmError.CIRCUIT_OPEN)` immediately.

After `cb.wait-duration-in-open-state` (default 30s), the breaker
half-opens; a small number of test calls determine whether to close
or re-open.

Circuit-open is propagated as a `Result.failure`, which the `CascadeOrchestrator`
treats as "this structurer is unavailable, skip and continue with the
others". The cascade does not abort.

---

## 7. The full call flow (sync mode)

```
LlmAddressStructurer.structure(RawAddress)
    │
    ▼
LlmModelClient.complete(LlmCompletionRequest)
    │
    ├─ Resilience4j retry (max-retries, backoff-ms)
    │      │
    │      ├─ Resilience4j circuit breaker (per client)
    │      │      │
    │      │      ▼
    │      │   WebClient.post().bodyValue(...).retrieve()
    │      │      .timeout(timeout-ms)
    │      │
    │      ▼
    │   Result<LlmCompletionResponse>
    │
    ▼
LlmAddressStructurer parses JSON to StructuringResult
    │
    ├─ on success: return populated StructuringResult
    └─ on failure: return StructuringResult.empty(name(), latency)
                   with diagnostics including the LlmError reason
```

The structurer NEVER throws on LLM failure. The cascade orchestrator
sees an empty result and moves to the next structurer.

---

## 8. The full call flow (stream mode, future use)

```
ChatHandler.respond(query)
    │
    ▼
LlmModelClient.stream(LlmCompletionRequest)
    │
    ▼
Flow.Publisher<LlmStreamChunk>
    │
    ▼
WebFlux Sinks.Many<String> for SSE response
    │
    ▼
Browser EventSource receives token-by-token
```

Stream mode is NOT implemented as part of the address-structurer
roadmap; the abstraction supports it for downstream use cases. The
`OpenAiCompatibleLlmClient` and `JpmcInternalGatewayLlmClient` both
implement `stream(...)` as a reference.

---

## 9. Prompt management

Prompts are versioned files under `app/src/main/resources/prompts/`:

```
app/src/main/resources/prompts/
├── address-structuring.json     ← structurer use case
├── address-structuring.v2.json  ← experimental, A/B candidate
└── chat-system.json             ← future maker/checker assistant
```

Each file is a JSON document with:

```json
{
  "version": "v1.0.0",
  "system": "You structure postal addresses into ISO 20022 fields...",
  "fewShots": [
    { "input": "...", "output": "..." },
    ...
  ],
  "outputSchema": "json-schema for validating LLM output"
}
```

The `LlmAddressStructurer` validates the LLM response against
`outputSchema` before accepting fields. Invalid responses are dropped
(empty `StructuringResult`) with a diagnostic logged.

---

## 10. Observability for LLM calls

Every `complete(...)` and `stream(...)` call emits:

| Metric | Type | Tags |
|---|---|---|
| `llm.calls` | Counter | client, model, mode, outcome |
| `llm.latency.first-byte` | Timer | client, model |
| `llm.latency.total` | Timer | client, model |
| `llm.tokens.input` | Counter | client, model |
| `llm.tokens.output` | Counter | client, model |
| `llm.cost.estimate` | Counter | client, model (only if pricing config available) |
| `llm.retries` | Counter | client, model, reason |
| `llm.circuit-state` | Gauge | client (0=closed, 1=half-open, 2=open) |

Logs include the prompt fingerprint (SHA-256 of the rendered prompt) but
NOT the prompt itself or the model output — both could contain customer
data subject to retention and redaction policies.

---

## 11. What you (Claude Code) must implement on Day 2

```
adapters/adapter-llm/src/main/java/com/jpmc/tfpm/address/adapter/llm/
├── LlmAddressStructurer.java          ← uses LlmModelClient via name
├── LlmConfidenceCalibrator.java       ← identity to start
├── LlmModelClientFactory.java         ← @Bean factory, switches on `type`
├── JpmcInternalGatewayLlmClient.java  ← provided as exemplar in this bundle
├── OpenAiCompatibleLlmClient.java     ← provided as exemplar in this bundle
├── PromptTemplateLoader.java          ← loads JSON prompts from classpath
└── config/
    ├── LlmProperties.java             ← @ConfigurationProperties("llm")
    └── LlmGatewayConfig.java          ← WebClient beans, OAuth token manager
```

The two LLM client classes shipped in this bundle are the reference
implementations. Implement the rest following the patterns documented
in this file and exercised in the unit tests Claude Code generates.
