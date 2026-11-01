# spring-ai-laya

Laya for Spring AI: local, open-weight typed decisions for Java applications.

[Laya](https://github.com/NandhaKishorM/laya) is a self-hosted, Apache-2.0 decision model that answers typed questions (choice, score, noul) about a piece of text. This project connects it to Spring AI. It also interoperates with [spring-ai-typesafe](https://github.com/spring-ai-community/spring-ai-typesafe), because Laya serves the same System One request and response shape that Jev uses.

> **Independent project.** spring-ai-laya is not affiliated with, or endorsed by, TypeSafe AI (the maker of Jev), the Spring AI Community, or the Laya authors. It depends on the released `org.springaicommunity:typesafe-java-sdk` and `typesafe-spring-ai` 0.4.0 artifacts only for protocol compatibility.

## What it adds over pointing a TypeSafe client at Laya

spring-ai-typesafe already works against Laya if you change the base URL. Doing only that has three problems:

| Problem | What spring-ai-laya does |
|---|---|
| The `confidence` field is a different number on Laya (normalised entropy) than on Jev (`(p_max - 1/n)/(1 - 1/n)`), so Jev-tuned thresholds such as `JevConfidenceGate`'s 0.6 floor gate the wrong value. | `LayaTypeSafeClient` puts Jev's formula (`x_jev_confidence`) in `confidence` by default. `ConfidenceMetric.ANSWER` and `ENTROPY` are also available. |
| `systemOneAll` sends one HTTP request per item. | Requests that share their questions go through `POST /v1/systemone/batch`, in length-sorted calls of at most 16 rows by default. |
| Laya's own signals are dropped: `answer_confidence`, abstention, truncation, routing, `/health`, `/v1/route`. The client also requires an API key Laya doesn't need, gets a 404 from `listModels()`, and has no typed 413 or 503 errors. | `LayaClient` models all of it. |

`LayaEscalatingClient` adds an optional Laya-first, Jev-on-doubt policy: it escalates on low confidence, on abstention, on choices with many options, or when Laya is down.

## Modules

| Module | Contents |
|---|---|
| `laya-java-client` | `LayaClient`, `LayaTypeSafeClient`, `LayaEscalatingClient`, `LayaOptions`, `LayaLimits`, `ConfidenceMetric`, response records and typed exceptions. No Spring AI dependency. |
| `spring-ai-laya-starter` | Spring Boot auto-configuration and `spring.ai.laya.*` properties. |
| `spring-ai-laya-spring-ai-tests` | Test-only: unmodified spring-ai-typesafe components (`JevDocumentFilter`, `JevDocumentReranker`, `JevJudge`, `JevConfidenceGate`) running on Laya. |
| `laya-benchmarks` | Accuracy, calibration and throughput measurements. Not published; evidence only. |

Coordinates: `io.github.vaquarkhan:spring-ai-laya-parent:0.1.0-SNAPSHOT`. Packages: `io.github.vaquarkhan.springailaya`.

## Quick start

Run Laya locally:

```bash
uv venv --python 3.12 && uv pip install --torch-backend=cpu "laya[serve]"
LAYA_MODELS=english LAYA_DEFAULT_MODEL=english LAYA_PRELOAD=1 LAYA_PORT=8000 .venv/bin/laya-serve
```

### Spring Boot

```properties
spring.ai.laya.base-url=http://localhost:8000
spring.ai.laya.api-key=${LAYA_API_KEY:}       # only if the server sets LAYA_API_KEY
spring.ai.laya.model=auto                     # or english | multilingual | typed-decisions
spring.ai.laya.confidence-metric=jev          # or answer | entropy
spring.ai.laya.rows-per-batch-call=16         # raise on a GPU
spring.ai.laya.max-concurrent-batch-calls=1   # raise on a GPU or several replicas
```

Once `base-url` is set, the application's `TypeSafeClient` bean is backed by Laya, so the spring-ai-typesafe components you build from it run on Laya.

### Optional: Laya first, Jev on doubt

> **Data leaves your environment.** An escalated request, including its full state (the ticket, passage or prompt being decided on), is sent to the Jev service. Only enable this where that export is approved. The starter refuses to start until you confirm it.

```properties
spring.ai.typesafe.api-key=${TYPESAFE_API_KEY}
spring.ai.laya.escalation.enabled=true
spring.ai.laya.escalation.allow-external-data-export=true
spring.ai.laya.escalation.min-answer-confidence=0.75
spring.ai.laya.escalation.max-laya-choice-options=20
```

### Plain Java

```java
LayaClient laya = LayaClient.builder().baseUrl("http://localhost:8000").build();
TypeSafeClient client = new LayaTypeSafeClient(laya);

JevDocumentFilter filter = JevDocumentFilter.builder(client).build();

TypeSafeClient hybrid = LayaEscalatingClient.builder(laya, jevClient)  // sends escalated requests to Jev
        .minAnswerConfidence(0.75)
        .build();
```

## Build and test

```bash
mvn verify                                                          # unit tests, no server needed
LAYA_BASE_URL=http://localhost:8000 mvn -Pintegration-tests verify  # live tests against laya-serve
LAYA_BASE_URL=http://localhost:8000 mvn -DskipTests install && \
  mvn -pl laya-benchmarks exec:java                                 # benchmarks
```

Tested against `laya-serve` 0.4.19 (english checkpoint), spring-ai-typesafe 0.4.0, Spring AI 2.0.1 and Spring Boot 4.0.7. Other versions are untested.

## Benchmarks

The benchmark uses **no generative LLM**. The models under test are Laya and, when `TYPESAFE_API_KEY` is set, Jev. Ground truth is the labels of public datasets (SST-2, AG News, Banking77), and scoring is exact-match accuracy and 10-bin calibration error. No model is asked to judge another.

The results stored in `laya-benchmarks/results` are **Laya-only** and exploratory: Jev has not been measured yet. A side-by-side comparison needs an authorized Jev key and a fresh run with `-Dbench.requireBoth=true`, which records hashes of the inputs and stops if either backend leaves an input unanswered. See [laya-benchmarks/BENCHMARK-PROTOCOL.md](laya-benchmarks/BENCHMARK-PROTOCOL.md).

## Documents

- [docs/laya-spring-ai-guide.html](docs/laya-spring-ai-guide.html): long-form tutorial (also the GitHub Pages site under `docs/`).
- [docs/DEVELOPER-TUTORIAL.md](docs/DEVELOPER-TUTORIAL.md): developer reference.
- [docs/ANALYSIS.md](docs/ANALYSIS.md): analysis and benchmark interpretation.
- [docs/EMBEDDING-STUDIO-PERSPECTIVE.md](docs/EMBEDDING-STUDIO-PERSPECTIVE.md): what Embedding Studio's decision lifecycle adds to the picture.
- [docs/EXTRACTION.md](docs/EXTRACTION.md): how the code would map onto spring-ai-typesafe if it were ever contributed.
- [laya-benchmarks/results/RESULTS.md](laya-benchmarks/results/RESULTS.md): raw benchmark tables.
- [MAINTAINER-PROPOSAL.md](MAINTAINER-PROPOSAL.md): draft note for the spring-ai-typesafe maintainers (not part of the site).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
