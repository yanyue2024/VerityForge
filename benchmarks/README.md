# Evaluation Benchmarks

`yanyue-operations-v1.blueprint.json` is the portable source for the 33-case operations benchmark. It references
seed documents by stable aliases and exact titles instead of environment-specific UUIDs. The import script resolves
those aliases against one knowledge base and compiles the blueprint into the public
`rag-evaluation-dataset/v1` contract.

Validate the source without calling the application:

```bash
./scripts/validate-evaluation-blueprint.sh
```

Compile against the current deployment without importing:

```bash
./scripts/import-evaluation-blueprint.sh \
  --dry-run \
  --output tmp/benchmarks/yanyue-operations-v1.json
```

Import once after the seed documents are published:

```bash
./scripts/import-evaluation-blueprint.sh \
  --output tmp/benchmarks/yanyue-operations-v1.json
```

Cases with `conversationGroup` and `conversationTurn` run sequentially in one hidden Evaluation conversation.
Different groups and standalone cases receive independent conversations. The benchmark records quality metrics for
observation; it does not define a release-blocking threshold. Cases may also declare `forbiddenDocumentAliases`;
the compiler resolves them to organization-owned document IDs and Evaluation reports Top-10 hits without changing
Run completion status.

## Chinese enterprise dataset v1

`chinese-enterprise-rag-v1.sources.json` fixes the 200-document selection, upstream commits, source paths, formats,
licenses, and source SHA-256 values. `chinese-enterprise-rag-v1.blueprint.json` is its portable 440-case evaluation
source. Generated binary and text corpus files live under the Git-ignored `data/chinese-enterprise-rag-v1/` directory.

Build or verify the corpus from the repository root:

```bash
python3 scripts/build-chinese-enterprise-dataset.py
python3 scripts/build-chinese-enterprise-dataset.py --verify-only
```

Upload the complete corpus and import the evaluation dataset only after all ingestion jobs succeed:

```bash
scripts/import-chinese-enterprise-dataset.sh
```

The builder also supports `--cache-dir`, `--offline`, `--clean-cache`, and `--refresh-selection`; run it with
`--help` for the complete interface. The upload script reads the local `.env` by default and accepts matching API,
credential, knowledge-base, concurrency, and timeout overrides.

## AUTO routing benchmark v1

`chinese-enterprise-auto-routing-v1.blueprint.json` is a deterministic 200-case routing set with 100 `FAST` and
100 `DEEP` labels. FAST contributes 25 cases from each source project and is balanced across direct facts,
procedures, source formats, and negative-rejection cases; all single-intent `no_answer` cases are FAST. DEEP keeps
the 24 genuine cross-document DEEP cases from the base benchmark and adds 76 hard cases stratified by source
project and Agentic challenge type.

Build or verify the generated blueprint:

```bash
python3 scripts/build-auto-routing-benchmark.py
python3 scripts/build-auto-routing-benchmark.py --check
```

## Agentic RAG v2 reports

`scripts/render-agentic-v2-report.py` renders either a full `RAG` run requested in `DEEP`/`AUTO` mode or a
`ROUTING_ONLY` run. Persist the complete response from
`GET /api/v1/evaluation/runs/{runId}` and render it offline so the report remains reproducible after model or
pipeline configuration changes:

```bash
python3 scripts/render-agentic-v2-report.py \
  tmp/benchmarks/<run-id>.json \
  --output benchmarks/<run-id>.report.md
```

The default report gate requires a completed run with exactly 200 unique case rows, 200 unique questions, no
failed cases, and consistent aggregate counts. For every successful full-RAG row that selected `DEEP`, it also
requires an `agentic-hybrid-v2` runtime snapshot using
`rewrite-v1+planner-v3+evidence-v2+coverage-v3+gap-v3`, one consistent chat/query-rewrite/rerank model snapshot,
and a chat profile matching the run's requested model profile. Successful RAG rows must have zero
`toolFailureCount`, zero `deepReadFailureCount`, and zero `tool.evidence_judge.failed`; successful DEEP rows must
also have at least one recorded Evidence Judge call. The report independently recomputes the routing
confusion matrix and decision-source accuracy from case rows instead of trusting aggregate values alone.

Use the matching portable blueprint when rendering a nonstandard dataset or from outside the repository root:

```bash
python3 scripts/render-agentic-v2-report.py \
  tmp/benchmarks/<run-id>.json \
  --blueprint benchmarks/chinese-enterprise-auto-routing-v1.blueprint.json \
  --output benchmarks/<run-id>.report.md
```

Smoke runs can change the cardinality with `--expected-rows`. `--allow-failures`, `--allow-incomplete`, and
`--allow-tool-failures` are explicit diagnostic-only escapes that preserve degraded results in the generated
Markdown. `--allow-tool-failures` waives all four tool-health checks above, records every violation as a warning,
and marks the report's tool-health gate as `WAIVED`; it does not turn a degraded run into a strict pass. Runtime
snapshots that are mixed or differ from the expected v2 values remain a hard error unless
`--allow-mixed-runtime` is supplied, in which case every mismatch is recorded as a report warning.
`--expected-model-profile-id`, `--expected-pipeline-version`, and `--expected-prompt-version` can pin an
independently known deployment snapshot.
