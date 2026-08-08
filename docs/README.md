# VerityForge Design and Evidence

This index is the second layer of the VerityForge portfolio: the root README gives the concise product story, while the material below exposes the design decisions, implementation boundaries, and evidence behind the claims.

## 1. Product Tour

- [Showcase](showcase/README.md): deterministic desktop screenshots, capture rules, and Demo boundaries.
- [Frontend product requirements](verityforge-frontend-product-requirements.md): the information architecture, core workspaces, interaction model, and acceptance criteria.
- [Evaluation workbench design](verityforge-evaluation-workbench-v8-design.md): how datasets, Fast/Deep pairs, metrics, trends, and schedules fit into the product.

## 2. System Design

- [Architecture](architecture.md): modules, runtime topology, persistence, ingestion, retrieval, and security boundaries.
- [Implementation status](implementation-status.md): the current capability inventory and known scope.
- [Goal-batched parent deep read](agentic-v8-goal-batched-parent-deep-read.md): the v8 evidence-retrieval strategy.
- [Final-answer generation v2](agentic-v8-final-answer-generation-v2.md): the grounded synthesis and citation path used after evidence collection.
- [Fast/Deep routing design](../IR/03-fast-deep-router-detailed-design.md): routing signals, budgets, fallbacks, and observability.

## 3. Verification and Operations

- [200-case Agentic retrieval report](../benchmarks/agentic-v8-goal-batched-parent-200-20260807.md): reproducible retrieval quality, latency, token, and leakage results.
- [Five-case full-answer report](../benchmarks/agentic-v8-final-answer-v2-five-case-20260807.md): answer coverage, semantic correctness, citation support, and Fast/Deep trade-offs.
- [Fast/Deep acceptance report](../IR/04-fast-deep-router-acceptance-report.md) and [cost-quality frontier](../IR/05-fast-deep-router-cost-quality-frontier.md): routing acceptance evidence and operating trade-offs.
- [Development](development.md) and [deployment](deployment.md): local prerequisites, configuration, recovery, credential rotation, and operational constraints.

The public repository is a curated snapshot. Completed implementation plans and superseded exploratory designs are intentionally omitted from the clean GitHub history; the documents above describe the current portfolio narrative and its supporting evidence.
