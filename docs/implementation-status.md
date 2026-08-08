# Implementation Status

This repository is an executable platform under active development. Model infrastructure,
generation-aware retrieval, real Fast RAG, the persisted Agentic loop, and the main knowledge
governance, memory, true-RAG evaluation, team authorization, and core observability workflows are implemented.
HTTPS, off-host backup, and off-host alert routing remain in progress.

## DEEP ReAct delivery (agentic-react-v1)

The current DEEP entry point is the WeKnora-v2-style native Tool Calling
runtime in `ReactAgentEngine`. It is a model-owned ReAct loop (`assistant` tool
call -> scoped Tool Result -> next round), not the historical Planner/
RetrievalTask/Fact/Coverage state machine. FAST continues to use its existing
pipeline and AUTO remains unchanged for the later intent-routing phase. DEEP
requires a passed native Tool Calling probe; it never falls back to FAST or
textual JSON tool simulation, and OLLAMA is explicitly unsupported for DEEP.

The runtime persists version-2 checkpoints, typed React steps/tool calls,
knowledge references and a deep-read-first document projection. The four
server-enforced tools are `knowledge_search`, `grep_chunks`,
`list_knowledge_chunks`, and `get_document_info`; every read re-checks
organization, ACL, current published version, effective dates and metadata
scope. Retrieval-only evaluation uses the same loop and tools but writes no
assistant message or citation.

The GPT-5.5 retrieval-only benchmark uses dataset
`7415acd4-3858-4c0b-93ae-b4c09179120b`, KB
`a350ed89-123f-4af4-a8f3-2720dd0c67a3`, Profile
`50873e75-7244-479b-aecf-5faf58802d97`, and two case workers. Its quality
metrics are diagnostic; operational acceptance is 200 unique successful cases,
native Tool Calling success, retrieval-only persistence, and zero scope/version
leakage. Use `scripts/render-agentic-retrieval-report.py` to render the
persisted run JSON after completion.

## Implemented

- Git monorepo and Maven modules for contract, domain, application, infrastructure, API, and worker.
- JWT login, Argon2id passwords, bootstrap administrator, and fixed organization roles. The admin-only Team
  workspace creates, updates, disables, and resets members; case-insensitive usernames and transactional
  final-administrator protection prevent ambiguous or locked-out teams. PostgreSQL-backed session versions revoke
  old JWTs immediately after role, enabled-state, or password changes, and every member can change their own password.
- Knowledge-base creation, upload intents, MinIO upload, document versions, publish/invalidate/reparse operations,
  metadata persistence, chunk preview, and ingestion stage timeline.
- PDF, DOCX, and XLSX parsing in Java, plus a versioned Python 3.12 Sidecar contract for advanced parsing.
- Idempotent outbox-to-Redis ingestion with persisted PostgreSQL jobs and stage artifacts. Cross-consumer Pending
  entries are reclaimed after an idle threshold, missing groups self-heal, malformed messages move to a dead-letter
  Stream, Stream keys remain persistent, and failed Redis publication commits PostgreSQL retry/backoff state.
- Adaptive parent/child chunking, full-text and generation-aware pgvector retrieval, validity and
  current-version filters, RRF, parent/adjacent context expansion, citations, and resumable SSE.
- Conversation history, recent-message query rewrite, persisted Fast and Deep answers, citations, Agent checkpoints,
  evidence, facts, coverage reports, persisted cancellation, and bounded search/deep-read counters. Deep Runs do not
  have a 45-second wall-clock cutoff and final generation is not shortened to fit the Agent search budget.
- Vue workbenches for chat, knowledge operations, document inspection, research runs, and retrieval evaluation.
- Evaluation dataset/case management with asynchronous real Fast/Deep RAG Runs, hidden evaluation conversations,
  linked `ragRunId` results, Recall@10, MRR, Hit@10, generated-answer coverage, no-answer accuracy, citation
  resolvability, effective-version leakage, runtime snapshots, and P95 end-to-end latency. Deep grading falls back
  to persisted `evidence_item` records when the Agent pipeline has no Fast retrieval-candidate rows. Versioned JSON
  import/export, linked Fast/Deep comparison Runs, and opt-in semantic-answer/citation-entailment model judging are
  implemented; judge errors remain separate from RAG execution errors. Persisted regression schedules retain their
  scope, typed filters, model override, cadence, and judge mode. PostgreSQL atomically claims due schedules and will
  not overlap a schedule while its previous Fast/Deep pair is still active; the Evaluation workspace exposes manual
  `run-now`, enable/disable, editing, deletion, and the latest 20 comparison trend points. Schedules can also persist
  a signed webhook configuration. Terminal comparison pairs create independently retried, attempt-fenced delivery
  records with history and manual retry in the Evaluation workspace; webhook failure does not alter RAG metrics.
  Case insertion order is durable, and `conversationGroup`/`conversationTurn` metadata drives real ordered follow-up
  execution in a shared hidden conversation. Invalid or incomplete turn sequences are rejected. Imported forbidden
  document IDs are ownership-validated and reported as Top-10 leakage diagnostics without becoming a hard gate.
  A portable 33-case operations benchmark covers direct facts, calculation, cross-document reasoning, version
  isolation, two three-turn conversations, and no-answer behavior; its validator and deployment-aware compiler avoid
  embedding environment-specific document UUIDs in source control.
- Docker Compose infrastructure, application images, Python and browser tests, and CI.
- Admin-only Model Profile CRUD and endpoint tests for OpenAI-compatible,
  Ollama, Local BGE, and explicit Demo providers.
- Versioned AES-256-GCM model credentials; API responses never return plaintext
  or encrypted key material.
- Public migration V43 removes the temporary V42 plaintext model-key column and refuses to proceed when that column
  contains data, preventing a silent credential drop during upgrade.
- Versioned credential Key IDs, legacy-envelope compatibility, decryption-only fallback keys, an admin-only Security
  workspace, and transactional master-key rotation cover model Profile keys plus Evaluation webhook configuration
  and delivery snapshots. Rotation preflights every ciphertext, records count-only audit history, and never returns
  key material to the API client.
- An independent GPU `model-sidecar` for local BGE-M3 1024-dimensional Embedding
  and BGE cross-encoder Rerank, with read-only checkpoint mounts and readiness
  checks.
- Per-knowledge-base Index Generations with 384/512/1024-dimensional query compatibility,
  hash-based vector reuse, atomic activation, rollback to retained generations, and
  30-day retired-generation cleanup. Rebuild batches resume in the same Generation after transient failure,
  recompute their target against the current published Chunk set, defer activation while document ingestion is
  active, and use attempt fencing plus heartbeat recovery so a superseded Worker cannot overwrite a newer attempt.
- Organization-level Pipeline Config management for Chat, Query Rewrite, and Rerank
  Profiles plus versioned retrieval parameters. Each Run persists the resolved config
  and Profile snapshot.
- Real Fast RAG with conditional schema-validated GPT query rewrite, one repair attempt,
  concurrent keyword/vector retrieval, BGE reranking, configurable evidence thresholds,
  token-budgeted context packing, stable Evidence IDs, native model SSE, and effective-version
  citation verification.
- Explicit no-answer behavior when no evidence passes reranking. Rerank failures emit
  `RERANK_SKIPPED` and retain RRF order; Embedding failures never switch dimensions or
  silently fall back to Demo behavior.
- Persisted retrieval diagnostics including keyword/semantic rank, RRF score, Rerank score,
  accepted-context state, latency, and retrieval sources.
- Agentic RAG v2 with structured intent routing, schema-validated 1-6 item planning,
  persisted Retrieval Tasks, maximum parallelism 4, multi-family Deep Read, atomic Fact
  extraction, a separate entailment pass, rejected facts, conflict groups, structured
  Coverage Judge, Gap Query loops, and explicit budget events.
- Deep Read uses round-robin task allocation so earlier subquestions cannot consume every evidence slot. One physical
  Chunk may be reused by multiple subquestions without consuming duplicate deep-read budget, and reuse remains visible
  through persisted `cross-question-reuse` diagnostics. Agent-only threshold fallback retains one best reranked
  candidate for deep reading when all scores fall below the configured floor; Fact Entailment remains authoritative.
- Coverage evidence-family counts, accepted-Fact presence, and conflict state are derived from persisted Java domain
  objects. Model output can make coverage stricter and explain gaps, but cannot invent an evidence family or mark a
  subquestion covered without deep-read evidence and an accepted fact.
- Agent synthesis sees only `ACCEPTED` Facts. Fact support spans, rejection reasons, and
  effective validity inherited from supporting document versions are persisted; unresolved
  gaps or conflicts are disclosed in the final answer.
- Agent cancellation is authoritative in PostgreSQL. API startup scans interrupted DEEP Runs;
  Runs with accepted Facts resume at synthesis, while pre-fact interruptions safely restart
  from planning under the original Run ID and event sequence.
- `GET /api/v1/runs/{runId}/artifacts` returns the persisted runtime snapshot, checkpoint,
  tasks, evidence, facts, coverage rounds, budget, gaps, and task errors for diagnostics and
  page refresh recovery.
- Versioned Metadata Schemas enforce field type, required, allowed-value, and filterability rules
  during upload, metadata-only updates, and retrieval. Typed Metadata filters are available in the
  Chat workbench and cannot bypass `RetrievalScope` validity/current-version rules.
- Metadata-only updates persist an audit revision without rebuilding vectors. Reingestion reuses an
  unchanged normalized artifact and embeddings by file, block, chunk, model, and policy hashes while
  exposing unchanged/added/modified/removed stage metrics.
- Document expiration propagates to the current document and disables its chunks. Version comparison,
  metadata editing, generation rebuild/rollback, and exact-version/chunk citation navigation are exposed
  in the KnowledgeOps interface.
- Flyway V9 and the Worker retention job independently audit search-index cleanup and physical-content
  cleanup. Superseded/expired versions lose vectors after retention; blocks, chunks, parser artifacts, and
  object-store assets are removed only when no historical retrieval, evidence, or citation references them.
- Redis holds the recent eight-message window and rolling summary while PostgreSQL remains the complete
  conversation source of truth. User-scoped, confirmed, currently valid `MemoryFact` records are applied
  only as personalization and emit `MEMORY_APPLIED` with `evidenceEligible=false`.
- The Vue workbench includes Metadata Schema management, typed upload controls, a Metadata Filter Builder,
  index generation controls, persisted Research diagnostics, a long-term memory workspace, run diagnostics,
  an admin-only responsive Team workspace, version diff, and responsive citation navigation. Citation events and history carry the normalized source
  range and page number from parser Block offsets through retrieval and persistence to the document inspector.
- Browser regression for the public snapshot includes mocked desktop flows and an environment-driven production Smoke test.
  The mocked suite has 26 scenarios (23 active and 3 intentional skips). The production test checks the real public
  deployment for viewport overflow and page/console errors across KnowledgeOps, Chat filters, Memory, Team, Security,
  and Evaluation desktop views, and saves screenshots for review.
- Flyway V8 adds governance constraints, metadata revision history, memory update timestamps, and persisted
  Run ownership. Flyway V9 adds version-retention audit state. Flyway V10 distinguishes `USER` and hidden
  `EVALUATION` conversations so regression Runs never pollute chat history. Flyway V11 persists paired Evaluation
  comparisons without duplicating the linked Run metrics. Flyway V12 persists regression schedules and their last
  triggered comparison while keeping historical metrics in the linked Runs. Flyway V13 adds durable Index Rebuild
  attempts, retry dispatch times, and stale-Worker recovery state. Flyway V14 adds ingestion heartbeats and a partial
  index for stale-attempt recovery. Flyway V15 adds encrypted schedule webhook configuration and durable notification
  deliveries with terminal-state readiness, retry scheduling, and dispatcher attempt fencing. Flyway V16 gives
  Evaluation cases a durable insertion position for conversation turn ordering and reproducible exports. Flyway V17
  adds user session versions and case-insensitive organization username uniqueness. Flyway V18 adds count-only
  credential-rotation audit history.
- WireMock protocol tests cover normal streaming, missing usage, 429/5xx pre-stream retries, structured-request
  timeout, malformed JSON, and faulted connections. A precise partial-stream test verifies that emitted deltas are
  never replayed after a stream fails.
- PostgreSQL/pgvector fault-injection tests cover duplicate ingestion delivery, failed Embedding continuation,
  atomic document-version publication rollback, Index Generation activation rollback, transient and terminal
  rebuild failures, corpus changes during a rebuild, active-ingestion deferral, stale Worker recovery, retry-limit
  exhaustion, and late-Worker attempt fencing. Real Redis tests cover Pending ownership transfer, group recreation,
  dead-lettering, persistent Streams, and committed Outbox backoff; a real MinIO test covers bounded failure and
  recovery. Signed notification protocol tests cover delivery, retry, stale-claim recovery, and blocked private
  destinations and production Spring constructor selection. PostgreSQL-backed team security tests cover password
  hashing, organization isolation, final-admin invariants, and immediate JWT revocation. The Java suite currently
  contains 99 tests, including PostgreSQL-backed document ACL coverage.
- Micrometer/OpenTelemetry observations cover Run mode, retrieval, model calls, Agent stages, and ingestion stages.
  Prometheus exposes low-cardinality latency and call counters plus model Token/cost counters. OTLP trace, metric,
  and log export are independently configurable and disabled by default. The optional local-only observability
  profile now includes Collector, Prometheus, Tempo, Alertmanager, and a provisioned Grafana overview dashboard;
  official configuration validators and a runtime smoke test cover the stack.
- Hardened user-systemd templates run API and Worker with restricted filesystem and privilege settings. Compose
  services have health checks, restart/graceful-stop policies, and `no-new-privileges`; Java images run as a non-root
  user. PostgreSQL custom dumps and MinIO object archives are checksummed, and restore drills refuse the production
  database and automatically remove the isolated drill database.

## Deliberate development defaults

- `DemoLanguageModelAdapter` remains only for the deterministic Agent baseline and explicit
  demo Profiles. The active Fast RAG pipeline uses `gpt-5.5`, local BGE Embedding, and
  local BGE Rerank through persisted Profiles.
- Recovery resumes from the nearest safe semantic boundary (`SYNTHESIZE` when accepted Facts
  exist, otherwise `PLAN`); it does not attempt to replay a partially consumed model stream.
- Evaluation grades actual RAG output and supports current-versus-previous and explicit paired Fast/Deep comparison.
  Semantic answer judging, citation entailment judging, and versioned benchmark import/export are implemented;
  the evaluator waits for the underlying Run without a separate three-minute cutoff. Scheduled regression execution
  and historical trends are implemented; signed schedule notifications are persisted and independently retried.
  Quality values remain observational. A real FAST run of the 33-case operations corpus completed all cases using
  29 isolated/shared Evaluation conversations, with no failed cases, no forbidden-document hits, no effective-version
  leaks, and fully resolvable citations for all cited cases. Exact values vary with model and retrieval configuration.
- Agent search rounds retain bounded counters and the original 120-second search-state timeout, but there is
  intentionally no outer Deep Run hard deadline. Final generation currently receives all accepted Facts selected by
  the evidence workflow and is not shortened, rejected, or timed against the search budget.
- Document-level ACL is enforced as an immediate logical-document policy across hybrid retrieval, Agent recovery,
  citation verification, governance reads, historical citations, and research artifacts. Organization-visible and
  restricted role/member grants are supported with administrator recovery access and an audit trail. Field-level
  redaction remains a follow-up only if real documents require it.
- Docling is an optional Sidecar extra; PaddleOCR is not packaged in the default image.
- Citation locations use offsets in the versioned normalized source contract. The immutable Chunk remains the
  visual fallback when a source format cannot render a native character selection.

## Next delivery order

1. Add HTTPS termination and configure encrypted off-host business backups. The development credential master key
   has been rotated through the documented Keyring workflow and its old fallback key removed after verification.
2. Route selected alerts to an authenticated off-host receiver after tuning them from real workload history.
3. Expand the benchmark with real usage cases while keeping quality values observational rather than release-blocking.
4. Expand parser-sidecar OCR/table fixtures and exercise multi-Worker ingestion during rolling deployment.
