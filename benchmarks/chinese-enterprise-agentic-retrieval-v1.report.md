# Chinese Enterprise Agentic Retrieval v1 Comparison Report

## Scope

- Dataset: `中文企业技术知识库 Agentic Retrieval 困难集 v1`
- Dataset UUID: `7415acd4-3858-4c0b-93ae-b4c09179120b`
- Knowledge Base UUID: `a350ed89-123f-4af4-a8f3-2720dd0c67a3`
- Cases: 200 answerable questions; no negative/no-answer cases
- Basic retrieval run: `9da94cf0-7e48-4f44-b216-ccd032411bfe`
- Agentic primary run: `952e6927-96b5-42ee-ad96-caa4658438ae`
- Agentic retry runs: `e39a79e2-fa63-4e38-9dc3-9ff743abe7d6`,
  `e7d83f06-0748-4021-93bc-86b833242a01`

## Execution Definitions

Basic retrieval submits each original question as one query to keyword and BGE semantic search in parallel. Both
branches retrieve Top-30 chunks and are fused with RRF to at most 40 candidates. It stops before query rewriting,
decomposition, reranking, context packing, and answer generation.

Agentic retrieval executes planning, sub-question decomposition, per-query keyword/semantic/hybrid retrieval, local
BGE reranking, deep read, fact extraction and entailment checks, conflict analysis, coverage judging, and gap search.
It persists deep-read evidence and the reranked candidates from every decomposed query. Evaluation ranks deep-read
evidence first and uses remaining reranked candidates to fill Top-5/10. It then stops before final answer synthesis.

Both methods compute metrics from the first occurrence of each distinct document. No target-document filter or answer
hint is applied at retrieval time.

## Overall Comparison

| Metric | Basic | Agentic | Absolute delta |
| --- | ---: | ---: | ---: |
| Recall@5 | 0.6067 | 0.7592 | **+0.1525** |
| Recall@10 | 0.6567 | 0.7925 | **+0.1358** |
| Hit@5 | 0.8250 | 0.8800 | +0.0550 |
| Hit@10 | 0.8550 | 0.8900 | +0.0350 |
| MRR@5 | 0.7515 | 0.8223 | +0.0708 |
| MRR@10 | 0.7557 | 0.8239 | +0.0683 |
| Evidence answer coverage | 0.7031 | 0.7892 | +0.0861 |
| Average candidate chunks | 37.38 | 39.87 | +2.49 |
| P50 latency | 771 ms | 117,699 ms | +116,928 ms |
| P95 latency | 1,250 ms | 186,045 ms | +184,795 ms |
| Full / partial / zero Recall@5 | 78 / 87 / 35 | 124 / 52 / 24 | +46 / -35 / -11 |

Agentic improves Recall@5 on 76 questions, ties on 107, and regresses on 17. At Recall@10 the paired counts are
69 improved, 117 tied, and 14 regressed. Recall@5 improves by 25.1% relative to Basic, with substantially higher
latency because Agentic invokes multiple structured reasoning stages.

## Challenge Comparison

| Challenge | Cases | Basic R@5 | Agentic R@5 | Delta | Basic R@10 | Agentic R@10 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Keyword sparse | 20 | 0.5000 | 0.5500 | +0.0500 | 0.5000 | 0.5500 |
| Multi-intent | 80 | 0.6750 | 0.8313 | +0.1563 | 0.7250 | 0.8438 |
| Query decomposition | 60 | 0.5056 | 0.7389 | **+0.2333** | 0.5722 | 0.8167 |
| Semantic paraphrase | 40 | 0.6750 | 0.7500 | +0.0750 | 0.7250 | 0.7750 |

The largest gain is on three-intent query-decomposition questions, which are the primary target for the Agentic
pipeline. Keyword-sparse questions improve least and remain the clearest retrieval weakness.

## Source Comparison

| Source | Cases | Basic R@5 | Agentic R@5 | Delta | Basic R@10 | Agentic R@10 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| openEuler | 50 | 0.3400 | 0.5267 | **+0.1867** | 0.3667 | 0.5667 |
| Kubernetes | 50 | 0.7567 | 0.8800 | +0.1233 | 0.8233 | 0.9333 |
| Ant Design | 50 | 0.6467 | 0.7667 | +0.1200 | 0.6967 | 0.7733 |
| Apache Doris | 50 | 0.6833 | 0.8633 | +0.1800 | 0.7400 | 0.8967 |

## Intent Count Comparison

| Expected documents / intents | Cases | Basic R@5 | Agentic R@5 | Delta | Basic R@10 | Agentic R@10 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 60 | 0.6167 | 0.6833 | +0.0667 | 0.6500 | 0.7000 |
| 2 | 80 | 0.6750 | 0.8313 | +0.1563 | 0.7250 | 0.8438 |
| 3 | 60 | 0.5056 | 0.7389 | **+0.2333** | 0.5722 | 0.8167 |

## Completion Audit

- The primary Agentic run produced 200 result rows: 189 successful and 11 failed structured-reasoning validations.
- Retry 1 recovered 10 of the 11 failed questions without changing questions, scope, model profiles, or budgets.
- Retry 2 recovered the remaining question after gap-key handling was narrowed to normalize `qN` keys, accept direct
  sub-question UUIDs, and ignore only entries that cannot be mapped to the active plan.
- The merged result contains exactly 200 unique questions and 200 unique successful Agent runs.
- All 200 runs and checkpoints are `COMPLETED`; all 200 checkpoints have `answerGenerationSkipped=true`.
- Persisted artifacts: 13,702 retrieval candidates, 1,591 evidence items, and 239 coverage reports.
- Final-generation artifacts: 0 conversation messages and 0 citations.

The comparison therefore measures retrieval and evidence selection only. It does not include answer correctness,
faithfulness, citation quality, or final-generation latency.
