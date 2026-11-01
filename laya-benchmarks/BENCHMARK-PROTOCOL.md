# Laya and Jev side-by-side benchmark protocol

This protocol measures Laya and Jev on the **same decision task**. It avoids claiming that two distinct model deployments can use identical model identifiers or identical transport implementations.

## Comparison contract

For every dataset and every example, the harness sends both backends the same:

1. labelled input state, byte for byte after Java JSON serialization;
2. question id (`label`);
3. question type (`choice`);
4. question instructions;
5. option labels, option descriptions and their insertion order;
6. dataset split, sampling seed and example order;
7. scoring method: exact selected-label match and 10-bin ECE.

The backends necessarily differ in the only fields that identify or operate a model:

| Backend-specific field | Laya | Jev |
|---|---|---|
| Model identifier | `english` by default, configurable with `-Dbench.laya.model` | `jev-latest` by default, configurable with `-Dbench.jev.model` |
| Transport | Native `POST /v1/systemone/batch` to avoid treating network round trips as model quality | `TypeSafeClient.systemOneAll`, concurrency 4 |
| Deployment | Local `laya-serve` | Managed API |

The comparison is therefore **same data, same decision schema, same evaluator and fixed model versions**, with each backend using its documented transport. It is not a same-latency test.

## Receipt

Every comparison row in `results.json` includes `parity_receipt`:

- `state_label_sha256`: SHA-256 of the ordered state and expected-label sequence.
- `question_sha256`: SHA-256 of the exact serialized `label` question, including criteria order.
- `examples`, `question_id`, `question_type`, `option_count`.
- configured `laya_model` and `jev_model`.
- the native batch strategy used by each backend.

The harness fails the comparison if either backend returns fewer answers than the fixed input count. It never computes a side-by-side result from partial data.

## Reproduce a parity run

Start Laya and export both endpoints. Keep the key private: the command does not print it.

```bash
# Bash / macOS / Linux
export LAYA_BASE_URL=http://localhost:8000
export TYPESAFE_API_KEY='your-Jev-key'

mvn -pl laya-benchmarks exec:java \
  -Dbench.requireBoth=true \
  -Dbench.laya.model=english \
  -Dbench.jev.model=jev-latest \
  -Dbench.sst2=200 \
  -Dbench.ag=200 \
  -Dbench.banking.perLabel=2
```

```powershell
# PowerShell
$env:LAYA_BASE_URL = 'http://localhost:8000'
$env:TYPESAFE_API_KEY = 'your-Jev-key'
mvn -pl laya-benchmarks exec:java `
  '-Dbench.requireBoth=true' `
  '-Dbench.laya.model=english' `
  '-Dbench.jev.model=jev-latest'
```

`-Dbench.requireBoth=true` stops immediately if `TYPESAFE_API_KEY` is missing. Without it, the harness runs Laya-only and labels the result that way.

## Interpreting results

- Treat accuracy as a property of the chosen dataset, question wording, label descriptions and model version.
- Use `answer_confidence` for Laya's calibrated-confidence analysis. Do not compare Laya's native entropy confidence against Jev's `confidence` as if they were the same statistic.
- Compare latency only in a separate controlled experiment. A local CPU server and a remote managed API have different network, hardware, warm-up and batching conditions.
- Run a representative, held-out domain dataset before using any result to set an application policy.
