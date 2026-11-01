# Developer tutorial: Laya with spring-ai-typesafe

This tutorial runs Laya locally, uses it from Spring AI through the TypeSafe API, adds RAG screening, then introduces a controlled Laya-first / Jev escalation policy.

The implementation used here is in this repository. It is intentionally additive: all Laya classes are under `io.github.vaquarkhan.springailaya`, so they can move into `spring-ai-typesafe` without renaming existing TypeSafe or Jev classes.

## What you will build

```text
Spring Boot application
  └─ TypeSafeClient
       ├─ LayaTypeSafeClient: local Laya, with Laya-aware confidence and batching
       └─ LayaEscalatingClient (optional): Laya first, Jev for selected decisions

JevDocumentFilter / JevDocumentReranker / JevToolIndex / JevJudge
  └─ keep their existing Spring AI APIs
```

## Prerequisites

- Java 17 or later.
- Maven 3.9 or later.
- Python 3.10 to 3.13 and [uv](https://docs.astral.sh/uv/).
- A machine that can run Laya. CPU is sufficient for development. Use a compatible GPU for production throughput.
- A TypeSafe key only for the optional Jev escalation path.

## 1. Run Laya locally

Create an isolated environment and install Laya's server extra:

```bash
uv venv --python 3.12
uv pip install --torch-backend=cpu "laya[serve]"
```

Start the English checkpoint on the default port:

```bash
LAYA_DEVICE=cpu \
LAYA_MODELS=english \
LAYA_DEFAULT_MODEL=english \
LAYA_PRELOAD=1 \
LAYA_THREADS=8 \
laya-serve
```

`LAYA_PRELOAD=1` loads the checkpoint before the server reports ready. The first startup downloads model files. On a production host, set `LAYA_DEVICE=cuda` when CUDA is available and benchmark the right `LAYA_THREADS`, batch size and concurrency for that host.

Verify liveness:

```bash
curl http://localhost:8000/health
```

When no `LAYA_API_KEY` is configured, `/health` reports resident checkpoints and the actual device. When a key is configured, a request without it still receives `{"status":"ok"}`, because liveness does not require access to deployment details.

## 2. Add the Laya backend

For this repository, install the starter locally:

```bash
mvn clean install
```

Add the project starter and the existing TypeSafe Spring AI integration to your application. The final upstream coordinates should be confirmed with the maintainers. Until then, build locally or use a composite build.

```xml
<dependency>
  <groupId>io.github.vaquarkhan</groupId>
  <artifactId>spring-ai-laya-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>org.springaicommunity</groupId>
  <artifactId>typesafe-spring-ai</artifactId>
  <version>0.4.0</version>
</dependency>
```

Enable Laya:

```properties
spring.ai.laya.base-url=http://localhost:8000
spring.ai.laya.model=auto
spring.ai.laya.confidence-metric=jev
```

`base-url` is the switch. When set, `LayaAutoConfiguration` supplies the application's `TypeSafeClient`, and the regular TypeSafe auto-configuration backs off.

### Why `confidence-metric=jev` is the default

Jev and Laya use the `confidence` field for different calculations:

| Metric | Meaning | Use it when |
|---|---|---|
| `jev` | Jev's formula, from Laya's `x_jev_confidence` field or computed from probabilities in strict mode | Existing thresholds were tuned on Jev. This is the default. |
| `answer` | `answer_confidence`, the probability mass on the selected answer | You tune thresholds on labelled Laya traffic. |
| `entropy` | Laya's native `confidence`, which measures how concentrated the distribution is | You explicitly want the server's native entropy signal. |

Do not move a threshold from one metric to another. Measure it on labelled examples from your workload.

## 3. Make a typed local decision

The adapter is a normal `TypeSafeClient`. Existing question builders and response accessors remain the same.

```java
import java.util.Map;

import org.springaicommunity.typesafe.TypeSafeClient;
import org.springaicommunity.typesafe.question.Choice;
import org.springaicommunity.typesafe.response.SystemOneResponse;

Choice department = Choice.builder()
        .instructions("Which team should handle this customer request?")
        .option("billing", "payments, invoices and refunds")
        .option("technical", "bugs, outages and login issues")
        .option("account", "profile, identity and subscription access")
        .build();

SystemOneResponse response = client.systemOne(
        Map.of("ticket", "I was charged twice this month. Please refund one charge."),
        Map.of("department", department));

String queue = response.choiceValue("department");
double jevCompatibleConfidence = response.choice("department").confidence();
```

To inspect Laya-specific fields, inject `LayaClient` as well:

```java
import io.github.vaquarkhan.springailaya.LayaClient;
import io.github.vaquarkhan.springailaya.LayaOptions;
import io.github.vaquarkhan.springailaya.response.LayaResponse;

LayaResponse decision = layaClient.systemOne(
        SystemOneRequest.builder()
                .state(Map.of("ticket", "I was charged twice this month."))
                .question("department", department)
                .build(),
        LayaOptions.builder().minConfidence(0.75).build());

boolean abstained = decision.anyAbstained();
boolean inputWasTruncated = decision.usage().wasTruncated();
String checkpoint = decision.routing() == null ? "unknown" : decision.routing().model();
double calibratedConfidence = decision.answerConfidence("department").orElse(0.0d);
```

## 4. Use Laya in the existing RAG pipeline

The same question set over many passages is Laya's native batch shape. `LayaTypeSafeClient.systemOneAll` groups those requests, sorts them by state length to reduce padding, and sends bounded batch calls. Existing RAG components need no API change.

```java
@Bean
JevDocumentFilter documentFilter(TypeSafeClient client) {
    return JevDocumentFilter.builder(client)
            .policy(JevDocumentFilter.Policy.defaults())
            .build();
}

@Bean
JevDocumentReranker documentReranker(TypeSafeClient client) {
    return JevDocumentReranker.builder(client)
            .topK(5)
            .build();
}
```

Set the batching controls only after measuring your own workload:

```properties
# CPU-safe starting point. rows means states × questions.
spring.ai.laya.rows-per-batch-call=16
spring.ai.laya.max-concurrent-batch-calls=1
```

On the CPU host used for this tutorial, one large padded batch was not the best shape for long multi-question passages. Start with 16 rows and one call in flight. A GPU can prefer a larger batch and more concurrency, so benchmark before raising either setting.

### Fail-open is intentional, but it must be observed

`JevDocumentFilter` passes documents through when its decision backend fails. That avoids silently shrinking RAG context, but it also means a timeout can pass an unscreened injection attempt into the prompt. The Laya adapter defaults to a 90-second HTTP timeout because a CPU batch of long passages exceeded 30 seconds during the live tests. In production:

1. Set the timeout from measured p95 and p99 latency.
2. Alert on the filter's fail-open log message.
3. Use deterministic prompt-injection controls as a second layer when the risk warrants it.
4. Prefer GPU inference for high-throughput RAG screening.

## 5. Add a Laya-first, Jev-on-doubt policy

A hybrid policy is useful when local data handling matters for routine requests, but a managed decision model is appropriate for selected cases.

```properties
# Laya primary path
spring.ai.laya.base-url=http://localhost:8000
spring.ai.laya.options.min-confidence=0.75

# Jev fallback path
spring.ai.typesafe.api-key=${TYPESAFE_API_KEY}
spring.ai.laya.escalation.enabled=true
spring.ai.laya.escalation.allow-external-data-export=true
spring.ai.laya.escalation.min-answer-confidence=0.75
spring.ai.laya.escalation.max-laya-choice-options=20
spring.ai.laya.escalation.on-abstention=true
spring.ai.laya.escalation.on-failure=true
```

The selected `TypeSafeClient` is now a `LayaEscalatingClient`. It routes as follows:

| Condition | Route |
|---|---|
| Choice has at most 20 options and Laya's calibrated confidence clears the floor | Laya |
| Choice has more than 20 options | Jev without first sending the request to Laya |
| Laya marks an answer as abstained | Jev |
| Laya is unavailable, overloaded, times out, or rejects the request for a size limit | Jev |
| Laya's `answer_confidence` is below the policy floor | Jev |

The client exposes counters for metrics:

```java
LayaEscalatingClient hybrid = (LayaEscalatingClient) client;
long lowConfidence = hybrid.stats().get(EscalationReason.LOW_CONFIDENCE);
long broadChoice = hybrid.stats().get(EscalationReason.TOO_MANY_OPTIONS);
```

You can also attach an `EscalationListener` when building the client programmatically. Do not log the full `SystemOneRequest` in production if it can contain sensitive content.

## 6. Tune on your own labelled data

The benchmark in this repository is a starting point, not a production threshold. Create a labelled evaluation set that represents your real traffic:

1. Sample requests across expected languages, lengths, input formats and label frequencies.
2. Keep a test split that tuning never sees.
3. Run each candidate checkpoint through `LayaClient`.
4. Measure accuracy, calibration error and selective accuracy for `answer_confidence` thresholds.
5. Decide whether to use Laya directly, fine-tune it, change the question wording, shortlist labels, or escalate to Jev.

The included benchmark supports this workflow:

```bash
# Laya only
LAYA_BASE_URL=http://localhost:8000 \
mvn -pl laya-benchmarks exec:java

# Same examples against Jev too
LAYA_BASE_URL=http://localhost:8000 \
TYPESAFE_API_KEY=... \
mvn -pl laya-benchmarks exec:java
```

The results include accuracy, ECE for every confidence metric, confidence-threshold coverage, the effect on `JevConfidenceGate`, and end-to-end RAG filter timings.

## 7. Production checklist

- [ ] Run Laya with an API key if the endpoint is reachable beyond its trusted network.
- [ ] Put the Laya endpoint behind network controls and TLS where appropriate.
- [ ] Monitor `/health`, including the actual device, loaded checkpoints and CPU fallbacks.
- [ ] Pin and record the Laya version and checkpoint revision.
- [ ] Alert when `LayaUsage.wasTruncated()` is true. A response can still be valid while part of the state was not read.
- [ ] Measure batch settings separately for CPU, GPU and each input-length distribution.
- [ ] Make every high-impact action pass an independent authorization and confirmation control. A model decision is not authorization.
- [ ] Measure confidence thresholds and the escalation rate with production-like labels before automating decisions.

## Next reading

- [Benchmark results](https://github.com/vaquarkhan/spring-ai-laya/blob/main/laya-benchmarks/results/RESULTS.md)
- [Laya implementation analysis](ANALYSIS.md)
- [Extraction guide](EXTRACTION.md)
- [Laya HTTP API](https://github.com/NandhaKishorM/laya/blob/main/docs/http-api.md)

Content derived from external documentation was rephrased for compliance with licensing restrictions.
