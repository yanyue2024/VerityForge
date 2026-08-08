export type UserRole = 'ADMIN' | 'EDITOR' | 'VIEWER'

export interface LoginRequest {
  username: string
  password: string
}

export interface AuthSession {
  accessToken: string
  expiresAt: string
  userId: string
  organizationId: string
  displayName: string
  role: UserRole
}

export interface TeamMember {
  id: string
  username: string
  displayName: string
  role: UserRole
  enabled: boolean
  currentUser: boolean
  createdAt: string
  updatedAt: string
}

export interface CredentialRotationAudit {
  id: string
  activeKeyId: string
  rotatedBy: string | null
  totalCredentials: number
  rotatedCredentials: number
  sourceCounts: Record<string, number>
  previousKeyCounts: Record<string, number>
  createdAt: string
}

export interface CredentialRotationStatus {
  activeKeyId: string
  totalCredentials: number
  needsRotation: number
  unreadableCredentials: number
  credentialsBySource: Record<string, number>
  credentialsByKeyId: Record<string, number>
  lastRotation: CredentialRotationAudit | null
}

export interface ApiFieldViolation {
  field: string
  message: string
}

export interface ApiErrorBody {
  code?: string
  message?: string
  timestamp?: string
  fields?: ApiFieldViolation[]
}

export interface Conversation {
  id: string
  title: string
  settings: ConversationSettings
  pinned: boolean
  pinnedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ConversationSettings {
  mode: RunMode
  scope: CreateRunRequest['scope']
  filters: CreateRunRequest['filters']
}

export interface ConversationPage {
  items: Conversation[]
  nextCursor: string | null
}

export interface UpdateConversationRequest {
  title?: string
  pinned?: boolean
  settings?: ConversationSettings
}

export interface ConversationMessage {
  id: string
  role: 'system' | 'user' | 'assistant' | 'tool'
  content: string
  citations: Citation[]
  restricted: boolean
  runId: string | null
  traceAvailable: boolean
  reprocessable: boolean
  runStatus: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | null
  requestedMode: RunMode | null
  selectedMode: Exclude<RunMode, 'AUTO'> | null
  answerMode: AnswerMode | null
  retrievalHealth: RetrievalHealth | null
  evidenceCount: number | null
  latencyMs: number | null
  assistantName: string | null
  assistantProfileVersion: number | null
  createdAt: string
}

export type RunMode = 'AUTO' | 'FAST' | 'DEEP'
export type AnswerMode =
  | 'GROUNDED'
  | 'PARTIAL_GROUNDED'
  | 'CONVERSATIONAL'
  | 'GENERAL_KNOWLEDGE'
  | 'NO_ENTERPRISE_EVIDENCE'
  | 'TEMPORARILY_UNAVAILABLE'
  | 'ANSWER_WITH_EVIDENCE'
  | 'NO_EVIDENCE'
export type RetrievalHealth = 'SUFFICIENT' | 'PARTIAL' | 'EMPTY' | 'DEGRADED'

export type ModelProfileType = 'CHAT' | 'QUERY_REWRITE' | 'RERANK' | 'EMBEDDING'
export type ModelProvider = 'OPENAI_COMPATIBLE' | 'OLLAMA' | 'LOCAL_BGE' | 'DEMO'
export type ModelProfileTestStatus = 'NOT_TESTED' | 'PASSED' | 'FAILED'

export interface ModelProfile {
  id: string
  profileType: ModelProfileType
  provider: ModelProvider
  name: string
  modelName: string
  baseUrl: string | null
  hasApiKey: boolean
  settings: Record<string, unknown>
  enabled: boolean
  testStatus: ModelProfileTestStatus
  lastTestedAt: string | null
  lastTestMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface AssistantProfile {
  id: string
  version: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  assistantName: string
  identity: string
  capabilities: string[]
  tone: string
  boundaries: string[]
  additionalInstructions: string
  previewedAt: string | null
  publishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface AiConfig {
  publishedPipeline: PipelineConfig
  draftPipeline: PipelineConfig
  publishedAssistant: AssistantProfile
  draftAssistant: AssistantProfile
  modelProfiles: ModelProfile[]
  previewReady: boolean
}

export interface AiConfigPreview {
  answer: string
  modelName: string
  temperature: number
}

export interface AiConfigVersion {
  id: string
  kind: 'PIPELINE' | 'ASSISTANT'
  version: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  name: string
  createdAt: string
  publishedAt: string | null
}

export interface PipelineConfig {
  id: string
  name: string
  pipelineVersion: string
  promptVersion: string
  chatProfileId: string
  queryRewriteProfileId: string
  rerankProfileId: string
  keywordTopK: number
  semanticTopK: number
  rrfCandidateLimit: number
  rerankCandidateLimit: number
  finalContextGroups: number
  contextTokenBudget: number
  minimumRerankScore: number
  fastTimeoutSeconds: number
  maxIterations: number
  maxRetrievalRounds: number
  maxSubQueries: number
  maxSearchCalls: number
  maxDeepReadCalls: number
  maxToolCallsPerRound: number
  maxFinalReferences: number
  recentTurns: number
  maxContextTokens: number
  llmTimeoutSeconds: number
  agenticLoopTimeoutSeconds: number
  toolTimeoutSeconds: number
  maxCompletionTokens: number
  temperature: number
  parallelToolCalls: boolean
  requireDeepReadBeforeAnswer: boolean
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateRunRequest {
  query: string
  mode: RunMode
  scope: {
    knowledgeBaseIds: string[]
    documentIds: string[]
  }
  filters: Array<{
    field: string
    operator: 'EQ' | 'NE' | 'IN' | 'CONTAINS' | 'GT' | 'GTE' | 'LT' | 'LTE'
    value: unknown
  }>
  modelProfileId?: string
}

export type QuestionSuggestionEmptyReason =
  | 'NO_ELIGIBLE_CONTENT'
  | 'INSUFFICIENT_EVIDENCE'
  | 'CATALOG_BUILDING'

export interface QuestionSuggestionRequest {
  mode: RunMode
  scope: CreateRunRequest['scope']
  filters: CreateRunRequest['filters']
  refresh: boolean
  currentBatchId?: string | null
}

export interface QuestionSuggestionView {
  id: string
  text: string
}

export interface QuestionSuggestionResponse {
  batchId: string
  scopeFingerprint: string
  effectiveMode: Exclude<RunMode, 'AUTO'>
  suggestions: QuestionSuggestionView[]
  emptyReason: QuestionSuggestionEmptyReason | null
}

export type MetadataFieldType =
  | 'TEXT'
  | 'NUMBER'
  | 'BOOLEAN'
  | 'DATE'
  | 'DATETIME'
  | 'TEXT_LIST'

export interface MetadataFieldDefinition {
  key: string
  label: string
  type: MetadataFieldType
  required: boolean
  filterable: boolean
  allowedValues: string[]
}

export interface MetadataSchema {
  id: string
  knowledgeBaseId: string | null
  version: number
  fields: MetadataFieldDefinition[]
  active: boolean
  createdAt: string
}

export interface MetadataFilterFieldOption {
  key: string
  label: string
  type: MetadataFieldType
  populated: boolean
  values: string[]
}

export interface MetadataFilterOptions {
  fields: MetadataFilterFieldOption[]
}

export interface RunAccepted {
  runId: string
  requestedMode: RunMode
  eventsUrl: string
}

export type RunTracePath = 'CONVERSATIONAL' | 'FAST' | 'DEEP'
export type RunTraceState = 'PROCESSING' | 'GENERATING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type RunTraceNodeState = 'WAITING' | 'RUNNING' | 'COMPLETED' | 'DEGRADED' | 'FAILED' | 'CANCELLED'

export interface RunTraceDetail {
  label: string
  value: string
}

export interface RunTraceGoal {
  index: number
  label: string
  status: 'WAITING' | 'RUNNING' | 'COMPLETED' | 'DEGRADED'
  summary: string
}

export interface RunTraceNode {
  key: string
  label: string
  status: RunTraceNodeState
  summary: string
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
  details: RunTraceDetail[]
  goals: RunTraceGoal[]
}

export interface RunTrace {
  runId: string
  requestedMode: RunMode
  selectedMode: Exclude<RunMode, 'AUTO'> | null
  path: RunTracePath
  state: RunTraceState
  startedAt: string
  firstAnswerAt: string | null
  completedAt: string | null
  durationMs: number | null
  traceAvailable: boolean
  answerMode: AnswerMode | null
  retrievalHealth: RetrievalHealth | null
  evidenceCount: number
  nodes: RunTraceNode[]
}

export type StreamEventType =
  | 'RUN_ACCEPTED'
  | 'RUN_RECOVERED'
  | 'ROUTE_SELECTED'
  | 'INTENT_CLASSIFIED'
  | 'TRACE_UPDATED'
  | 'QUERY_REWRITE_STARTED'
  | 'QUERY_REWRITTEN'
  | 'MEMORY_APPLIED'
  | 'RETRIEVAL_STARTED'
  | 'RETRIEVAL_RESULT'
  | 'RERANK_COMPLETED'
  | 'RERANK_SKIPPED'
  | 'NO_ANSWER'
  | 'PARTIAL_ANSWER'
  | 'CITATION_VERIFIED'
  | 'PLAN_CREATED'
  | 'GOAL_RESEARCH_STARTED'
  | 'GOAL_RESEARCH_COMPLETED'
  | 'GOAL_RESEARCH_FAILED'
  | 'DEEP_READ_STARTED'
  | 'RETRIEVAL_TASK_CREATED'
  | 'RETRIEVAL_TASK_STARTED'
  | 'RETRIEVAL_TASK_COMPLETED'
  | 'RETRIEVAL_TASK_FAILED'
  | 'DEEP_READ_COMPLETED'
  | 'DEEP_READ_FAILED'
  | 'FACT_ACCEPTED'
  | 'FACT_REJECTED'
  | 'CONFLICT_DETECTED'
  | 'EVIDENCE_JUDGE_STARTED'
  | 'EVIDENCE_JUDGE_COMPLETED'
  | 'EVIDENCE_JUDGE_FAILED'
  | 'COVERAGE_UPDATED'
  | 'GAP_IDENTIFIED'
  | 'GAP_QUERY_CREATED'
  | 'BUDGET_UPDATED'
  | 'REACT_ROUND_STARTED'
  | 'AGENT_ACTION_UPDATED'
  | 'TOOL_CALL_STARTED'
  | 'TOOL_CALL_COMPLETED'
  | 'TOOL_CALL_FAILED'
  | 'CONTEXT_COMPRESSED'
  | 'ANSWER_GENERATION_STARTED'
  | 'ANSWER_DELTA'
  | 'CITATION'
  | 'ANSWER_REPLACED'
  | 'ANSWER_MODE_SELECTED'
  | 'RUN_COMPLETED'
  | 'RUN_CANCELLED'
  | 'RUN_FAILED'

export interface StreamEvent {
  eventId: string
  runId: string
  sequence: number
  type: StreamEventType
  timestamp: string
  payload: Record<string, unknown>
}

export interface Citation {
  index?: number
  chunkId?: string
  documentId?: string
  documentVersionId?: string
  documentTitle?: string
  knowledgeBaseName?: string
  quote?: string
  pageNumber?: number
  sourceStart?: number
  sourceEnd?: number
  documentUpdatedAt?: string
  goalAssociations?: CitationGoalAssociation[]
}

export interface CitationGoalAssociation {
  goalId: string
  goalQuestion: string
  recalledChildChunkIds: string[]
}

export interface KnowledgeBase {
  id: string
  name: string
  description: string
  documentCount: number
  chunkCount: number
  readyCount: number
  processingCount: number
  failedCount: number
  updatedAt: string
}

export interface DocumentRow {
  id: string
  title: string
  status: string
  currentVersionId: string | null
  versionNumber: number | null
  versionStatus: string | null
  validFrom: string | null
  validTo: string | null
  chunkCount: number
  parentChunkCount: number
  accessMode: DocumentAccessMode
  sourceName: string | null
  sourceType: string | null
  contentType: string | null
  byteSize: number | null
  metadata: string
  parseQualityStatus: 'PASS' | 'WARNING' | 'FAIL' | null
  ingestionStatus: string | null
  ingestionCurrentStage: string | null
  updatedAt: string
}

export type DocumentAccessMode = 'ORGANIZATION' | 'RESTRICTED'

export interface DocumentAccessPolicy {
  documentId: string
  mode: DocumentAccessMode
  allowedRoles: UserRole[]
  allowedUserIds: string[]
  accessReason: 'ADMIN' | 'ORGANIZATION' | 'ROLE' | 'USER'
  updatedAt: string
}

export interface DocumentVersion {
  id: string
  versionNumber: number
  sourceName: string
  sourceType: string | null
  status: string
  validFrom: string | null
  validTo: string | null
  publishedAt: string | null
  metadata: string
  ingestionJobId: string | null
  ingestionStatus: string | null
  parserName: string | null
  parserVersion: string | null
  parseQualityStatus: 'PASS' | 'WARNING' | 'FAIL' | null
  parseQualityScore: number | null
  parseQualityReport: string | null
  createdAt: string
}

export interface DocumentDetail {
  id: string
  knowledgeBaseId: string
  title: string
  status: string
  currentVersionId: string | null
  accessPolicy: DocumentAccessPolicy
  versions: DocumentVersion[]
  createdAt: string
  updatedAt: string
}

export interface DocumentMetadataRevision {
  revisionId: string
  documentVersionId: string
  metadata: Record<string, unknown>
  validFrom: string | null
  validTo: string | null
  embeddingChanged: boolean
  createdAt: string
}

export interface VersionDiffEntry {
  changeType: 'ADDED' | 'MODIFIED' | 'REMOVED'
  orderIndex: number
  beforePage: number | null
  afterPage: number | null
  beforeText: string | null
  afterText: string | null
}

export interface VersionDiff {
  documentId: string
  fromVersionId: string
  fromVersionNumber: number
  toVersionId: string
  toVersionNumber: number
  unchangedBlocks: number
  addedBlocks: number
  modifiedBlocks: number
  removedBlocks: number
  metadataChanged: boolean
  validityChanged: boolean
  entries: VersionDiffEntry[]
}

export interface ChunkRow {
  id: string
  parentChunkId: string | null
  type: 'PARENT' | 'CHILD'
  orderIndex: number
  text: string
  contextHeader: string
  estimatedTokens: number
  tokenizerName: string
  tokenCountMethod: 'EXACT' | 'ESTIMATED' | string
  sourceMappingStatus: 'MAPPED' | 'UNMAPPABLE'
  sourceLocation: string
  sourceBlockIds: string[]
  renderedMarkdown: string
  enabled: boolean
}

export interface CreateUploadIntent {
  title: string
  fileName: string
  contentType: string
  byteSize: number
  sha256?: string
  metadata?: Record<string, unknown>
  validFrom?: string
  validTo?: string
  documentId?: string
}

export interface UploadIntent {
  uploadId: string
  documentId: string
  documentVersionId: string
  method: string
  uploadUrl: string
  headers: Record<string, string>
  expiresAt: string
}

export interface MetadataManifestRow {
  rowNumber: number
  fileName: string
  title: string
  metadata: Record<string, unknown>
  validTo: string | null
  errors: string[]
}

export interface MetadataManifest {
  schemaVersion: number
  fields: MetadataFieldDefinition[]
  rows: MetadataManifestRow[]
  errors: string[]
}

export interface CompleteUpload {
  jobId: string
  status: string
}

export interface IngestionStage {
  stage: string
  status: string
  attempt: number
  metrics: string
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
}

export interface IngestionJob {
  id: string
  status: string
  currentStage: string | null
  attempt: number
  maxAttempts: number
  errorMessage: string | null
  stages: IngestionStage[]
  createdAt: string
  startedAt: string | null
  completedAt: string | null
}

export interface DocumentContentBlock {
  id: string
  type: string
  orderIndex: number
  text: string
  pageNumber: number | null
  headingPath: string
  boundingBox: string | null
  sourceStart: number | null
  sourceEnd: number | null
  sourceOffsetUnit: string
  attributes: string
}

export interface DocumentContent {
  documentVersionId: string
  totalBlocks: number
  normalizedMarkdown: string
  parseQualityStatus: 'PASS' | 'WARNING' | 'FAIL' | null
  parseQualityScore: number | null
  parseQualityReport: string | null
  blocks: DocumentContentBlock[]
}

export interface DocumentAsset {
  fileName: string
  contentType: string
  byteSize: number
  fileHash: string
  previewUrl: string
  previewExpiresAt: string
  createdAt: string
}

export interface DocumentMetadataHistory {
  revisionId: string
  documentVersionId: string
  metadata: Record<string, unknown>
  validFrom: string | null
  validTo: string | null
  changedBy: string | null
  createdAt: string
}

export interface IndexRebuildJob {
  id: string
  indexGenerationId: string
  status: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  totalChunks: number
  completedChunks: number
  reusedChunks: number
  failedChunks: number
  attempt: number
  maxAttempts: number
  nextAttemptAt: string
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
  createdAt: string
}

export interface IndexGeneration {
  id: string
  generationNumber: number
  status: 'BUILDING' | 'ACTIVE' | 'RETIRED' | 'FAILED'
  embeddingProfileId: string | null
  embeddingModelId: string
  embeddingModelVersion: string
  embeddingDimension: number
  chunkPolicyVersion: string
  vectorCount: number
  rebuildJob: IndexRebuildJob | null
  createdAt: string
  activatedAt: string | null
  retiredAt: string | null
}

export interface MemoryFact {
  id: string
  factText: string
  sourceMessageId: string | null
  confidence: number
  status: 'INFERRED' | 'CONFIRMED' | 'REJECTED'
  validFrom: string | null
  validTo: string | null
  createdAt: string
  updatedAt: string
}

export interface AgentRunArtifacts {
  runId: string
  status: string
  requestedMode: RunMode
  selectedMode: RunMode | null
  query: string
  runtimeSnapshot: Record<string, unknown>
  checkpoint: Record<string, unknown>
  retrievalTasks: Array<Record<string, unknown>>
  evidence: Array<Record<string, unknown>>
  facts: Array<Record<string, unknown>>
  coverage: Array<Record<string, unknown>>
  artifactVersion: number
  reactSteps: Array<Record<string, unknown>>
  toolCalls: Array<Record<string, unknown>>
  knowledgeReferences: Array<Record<string, unknown>>
  rankedDocuments: Array<Record<string, unknown>>
  budgetSnapshot: Record<string, unknown>
}

export interface EvaluationDataset {
  id: string
  name: string
  description: string
  caseCount: number
  runCount: number
  lastRunStatus: string | null
  lastMetrics: Record<string, unknown>
  createdAt: string
}

export interface EvaluationCase {
  id: string
  datasetId: string
  question: string
  expectedAnswer: string | null
  expectedDocumentIds: string[]
  metadata: Record<string, unknown>
  position: number
}

export interface EvaluationRun {
  id: string
  datasetId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  aggregateMetrics: Record<string, unknown>
  startedAt: string | null
  completedAt: string | null
  createdAt: string
}

export interface EvaluationDatasetDetail {
  dataset: EvaluationDataset
  cases: EvaluationCase[]
  runs: EvaluationRun[]
}

export interface EvaluationResult {
  id: string
  evaluationCaseId: string
  ragRunId: string | null
  question: string
  expectedAnswer: string | null
  expectedDocumentIds: string[]
  caseMetadata: Record<string, unknown>
  metrics: Record<string, unknown>
  errorMessage: string | null
  createdAt: string
}

export interface EvaluationRunDetail {
  run: EvaluationRun
  dataset: EvaluationDataset
  requestSnapshot: Record<string, unknown>
  results: EvaluationResult[]
}

export interface EvaluationRunSummary {
  id: string
  datasetId: string
  name: string
  datasetName: string
  status: EvaluationRun['status']
  mode: RunMode
  totalCases: number
  completedCases: number
  failedCases: number
  startedAt: string | null
  completedAt: string | null
  createdAt: string
}

export type EvaluationJudgeMode = 'NONE' | 'ANSWER' | 'ANSWER_AND_CITATIONS'

export interface EvaluationDatasetBundleCase {
  question: string
  expectedAnswer: string | null
  expectedDocumentIds: string[]
  metadata: Record<string, unknown>
}

export interface EvaluationDatasetBundle {
  schemaVersion: 'rag-evaluation-dataset/v1'
  sourceDatasetId: string | null
  exportedAt: string | null
  name: string
  description: string
  cases: EvaluationDatasetBundleCase[]
}

export interface EvaluationQueryRowPreview {
  rowNumber: number
  question: string
  hasExpectedAnswer: boolean
  expectedDocumentCount: number
  expectNoAnswer: boolean
  errors: string[]
}

export interface EvaluationQueryPreview {
  suggestedName: string
  rows: EvaluationQueryRowPreview[]
  errors: string[]
  bundle: EvaluationDatasetBundle
}

export interface EvaluationComparison {
  id: string
  datasetId: string
  fastRun: EvaluationRun
  deepRun: EvaluationRun
  judgeMode: EvaluationJudgeMode
  createdAt: string
}

export interface EvaluationComparisonDetail {
  comparison: EvaluationComparison
  fast: EvaluationRunDetail
  deep: EvaluationRunDetail
}

export interface EvaluationSchedule {
  id: string
  datasetId: string
  name: string
  cadenceMinutes: number
  enabled: boolean
  scope: CreateRunRequest['scope']
  filters: CreateRunRequest['filters']
  modelProfileId: string | null
  judgeMode: EvaluationJudgeMode
  notification: {
    enabled: boolean
    webhookUrl: string | null
    hasSigningSecret: boolean
  }
  lastNotification: EvaluationNotificationDelivery | null
  nextRunAt: string
  lastRunAt: string | null
  lastComparisonId: string | null
  lastError: string | null
  createdAt: string
  updatedAt: string
}

export interface EvaluationNotificationDelivery {
  id: string
  scheduleId: string
  comparisonId: string
  status: 'WAITING' | 'DELIVERING' | 'RETRY' | 'SUCCEEDED' | 'FAILED'
  attempt: number
  maxAttempts: number
  responseStatus: number | null
  responseBody: string | null
  errorMessage: string | null
  nextAttemptAt: string | null
  deliveredAt: string | null
  createdAt: string | null
  updatedAt: string
}

export interface EvaluationTrendPoint {
  comparisonId: string
  datasetId: string
  judgeMode: EvaluationJudgeMode
  fast: EvaluationRun
  deep: EvaluationRun
  createdAt: string
}
