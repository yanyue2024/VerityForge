import { expect, test, type Page } from '@playwright/test'
import { resolve } from 'node:path'
import type { CredentialRotationStatus } from '../src/types/api'

const captureShowcase = process.env.RAG_CAPTURE_SHOWCASE === 'true'
const showcaseDirectory = resolve(process.cwd(), '../docs/showcase')

async function captureDesktop(page: Page, filename: string, height = 900) {
  if (!captureShowcase) return
  await page.setViewportSize({ width: 1440, height })
  await page.screenshot({
    path: resolve(showcaseDirectory, filename),
    fullPage: false,
    animations: 'disabled',
  })
}

const session = {
  accessToken: 'e2e-token',
  expiresAt: '2099-01-01T00:00:00Z',
  userId: '10000000-0000-0000-0000-000000000001',
  organizationId: '20000000-0000-0000-0000-000000000001',
  displayName: '测试用户',
  role: 'ADMIN',
}

const knowledgeBases = [
  {
    id: '30000000-0000-0000-0000-000000000001',
    name: '产品与研发知识',
    description: '产品规范、技术方案与研发流程',
    documentCount: 18,
    chunkCount: 1260,
    readyCount: 16,
    processingCount: 1,
    failedCount: 1,
    updatedAt: '2026-07-10T08:00:00Z',
  },
  {
    id: '30000000-0000-0000-0000-000000000002',
    name: '客户交付手册',
    description: '实施方法、交付清单与常见问题',
    documentCount: 9,
    chunkCount: 486,
    readyCount: 9,
    processingCount: 0,
    failedCount: 0,
    updatedAt: '2026-07-09T06:30:00Z',
  },
]

const conversationSettings = {
  mode: 'DEEP' as const,
  scope: { knowledgeBaseIds: [knowledgeBases[0].id], documentIds: [] },
  filters: [],
}

const conversations = [
  {
    id: '40000000-0000-0000-0000-000000000001',
    title: '梳理知识入库流程',
    settings: conversationSettings,
    pinned: true,
    pinnedAt: '2026-07-10T08:30:00Z',
    createdAt: '2026-07-10T07:00:00Z',
    updatedAt: '2026-07-10T08:30:00Z',
  },
  {
    id: '40000000-0000-0000-0000-000000000002',
    title: '比较两套检索策略',
    settings: { ...conversationSettings, mode: 'FAST' as const },
    pinned: false,
    pinnedAt: null,
    createdAt: '2026-07-09T07:00:00Z',
    updatedAt: '2026-07-09T08:30:00Z',
  },
]

const documents = [
  {
    id: '90000000-0000-0000-0000-000000000001',
    title: '发布与索引治理规范',
    status: 'ACTIVE',
    currentVersionId: '91000000-0000-0000-0000-000000000001',
    versionNumber: 2,
    versionStatus: 'PUBLISHED',
    validFrom: '2026-07-01T00:00:00Z',
    validTo: null,
    chunkCount: 84,
    accessMode: 'ORGANIZATION',
    updatedAt: '2026-07-10T08:00:00Z',
  },
]

const metadataSchema = {
  id: '92000000-0000-0000-0000-000000000001',
  knowledgeBaseId: knowledgeBases[0].id,
  version: 3,
  active: true,
  createdAt: '2026-07-10T08:00:00Z',
  fields: [
    {
      key: 'department',
      label: '业务部门',
      type: 'TEXT',
      required: false,
      filterable: true,
      allowedValues: ['产品', '研发'],
    },
  ],
}

const indexGenerations = [
  {
    id: '93000000-0000-0000-0000-000000000002',
    generationNumber: 4,
    status: 'BUILDING',
    embeddingProfileId: '94000000-0000-0000-0000-000000000002',
    embeddingModelId: 'bge-m3',
    embeddingModelVersion: 'bge-m3-v1',
    embeddingDimension: 1024,
    chunkPolicyVersion: 'parent-child-v1',
    vectorCount: 620,
    rebuildJob: {
      id: '93000000-0000-0000-0000-000000000003',
      indexGenerationId: '93000000-0000-0000-0000-000000000002',
      status: 'QUEUED',
      totalChunks: 1260,
      completedChunks: 620,
      reusedChunks: 410,
      failedChunks: 640,
      attempt: 1,
      maxAttempts: 3,
      nextAttemptAt: '2026-07-10T08:12:00Z',
      errorMessage: 'Embedding endpoint temporarily unavailable',
      startedAt: '2026-07-10T08:10:00Z',
      completedAt: null,
      createdAt: '2026-07-10T08:09:00Z',
    },
    createdAt: '2026-07-10T08:09:00Z',
    activatedAt: null,
    retiredAt: null,
  },
  {
    id: '93000000-0000-0000-0000-000000000001',
    generationNumber: 3,
    status: 'ACTIVE',
    embeddingProfileId: '94000000-0000-0000-0000-000000000001',
    embeddingModelId: 'bge-small-zh-v1.5',
    embeddingModelVersion: 'local-v1',
    embeddingDimension: 512,
    chunkPolicyVersion: 'parent-child-v1',
    vectorCount: 1260,
    rebuildJob: null,
    createdAt: '2026-07-10T08:00:00Z',
    activatedAt: '2026-07-10T08:10:00Z',
    retiredAt: null,
  },
]

const memoryFacts = [
  {
    id: '95000000-0000-0000-0000-000000000001',
    factText: '回答时优先使用中文，并先给直接结论',
    sourceMessageId: null,
    confidence: 0.95,
    status: 'CONFIRMED',
    validFrom: '2026-07-01T00:00:00Z',
    validTo: null,
    createdAt: '2026-07-10T08:00:00Z',
    updatedAt: '2026-07-10T08:00:00Z',
  },
]

const teamMembers = [
  {
    id: session.userId,
    username: 'admin',
    displayName: '测试用户',
    role: 'ADMIN',
    enabled: true,
    currentUser: true,
    createdAt: '2026-07-01T08:00:00Z',
    updatedAt: '2026-07-10T08:00:00Z',
  },
  {
    id: '11000000-0000-0000-0000-000000000002',
    username: 'knowledge.editor',
    displayName: '知识编辑',
    role: 'EDITOR',
    enabled: true,
    currentUser: false,
    createdAt: '2026-07-02T08:00:00Z',
    updatedAt: '2026-07-09T08:00:00Z',
  },
]

const credentialRotationStatus: CredentialRotationStatus = {
  activeKeyId: 'k2026_07',
  totalCredentials: 3,
  needsRotation: 2,
  unreadableCredentials: 0,
  credentialsBySource: {
    MODEL_PROFILE: 1,
    EVALUATION_SCHEDULE: 1,
    EVALUATION_DELIVERY: 1,
  },
  credentialsByKeyId: { k2026_07: 1, k2026_06: 2 },
  lastRotation: null,
}

const evaluationDataset = {
  id: '60000000-0000-0000-0000-000000000001',
  name: '检索回归集',
  description: '验证当前发布文档能够稳定召回',
  caseCount: 1,
  runCount: 1,
  lastRunStatus: 'COMPLETED',
  lastMetrics: {
    recallAt10: 1,
    mrr: 1,
    hitAt10: 1,
    expectedAnswerCoverage: 1,
    p95LatencyMs: 82,
  },
  createdAt: '2026-07-10T08:00:00Z',
}

const evaluationRun = {
  id: '70000000-0000-0000-0000-000000000001',
  datasetId: evaluationDataset.id,
  status: 'COMPLETED',
  aggregateMetrics: evaluationDataset.lastMetrics,
  startedAt: '2026-07-10T08:30:00Z',
  completedAt: '2026-07-10T08:30:01Z',
  createdAt: '2026-07-10T08:30:00Z',
}

const evaluationRunSummary = {
  id: evaluationRun.id,
  datasetId: evaluationDataset.id,
  name: '检索回归集 · 2026-07-10',
  datasetName: evaluationDataset.name,
  status: 'COMPLETED',
  mode: 'FAST',
  totalCases: 1,
  completedCases: 1,
  failedCases: 0,
  startedAt: evaluationRun.startedAt,
  completedAt: evaluationRun.completedAt,
  createdAt: evaluationRun.createdAt,
}

const showcaseDataset = {
  id: '60000000-0000-0000-0000-000000000008',
  name: 'V8 三策略 · 五案例完整回答',
  description: '同一知识范围下的 Fast / Deep 完整链路对照（公开 benchmark 快照）',
  caseCount: 5,
  runCount: 2,
  lastRunStatus: 'COMPLETED',
  lastMetrics: {
    recallAt5: 1,
    acceptedEvidenceCoverage: 0.9519,
    semanticAnswerScore: 0.974,
    citationEntailmentScore: 0.946,
    averageLatencyMs: 78600,
  },
  createdAt: '2026-08-07T08:00:00Z',
}

const showcaseFastRun = {
  id: '70000000-0000-0000-0000-000000000008',
  datasetId: showcaseDataset.id,
  status: 'COMPLETED',
  aggregateMetrics: {
    caseCount: 5,
    successfulCases: 5,
    failedCases: 0,
    recallAt5: 0.3667,
    semanticAnswerScore: 0.15,
    citationEntailmentScore: 0.35,
    averageLatencyMs: 21300,
  },
  requestSnapshot: { mode: 'FAST', execution: 'STANDARD', judgeMode: 'ANSWER_AND_CITATIONS' },
  startedAt: '2026-08-07T08:10:00Z',
  completedAt: '2026-08-07T08:11:46Z',
  createdAt: '2026-08-07T08:10:00Z',
}

const showcaseDeepRun = {
  id: '70000000-0000-0000-0000-000000000009',
  datasetId: showcaseDataset.id,
  status: 'COMPLETED',
  aggregateMetrics: {
    caseCount: 5,
    successfulCases: 5,
    failedCases: 0,
    recallAt5: 1,
    acceptedEvidenceCoverage: 0.9519,
    semanticAnswerScore: 0.974,
    citationEntailmentScore: 0.946,
    averageLatencyMs: 78600,
    totalTokens: 241718,
  },
  requestSnapshot: { mode: 'DEEP', execution: 'STANDARD', judgeMode: 'ANSWER_AND_CITATIONS' },
  startedAt: '2026-08-07T08:20:00Z',
  completedAt: '2026-08-07T08:26:33Z',
  createdAt: '2026-08-07T08:20:00Z',
}

const showcaseRunSummaries = [
  {
    id: showcaseFastRun.id,
    datasetId: showcaseDataset.id,
    name: '完整链路 · Fast',
    datasetName: showcaseDataset.name,
    status: showcaseFastRun.status,
    mode: 'FAST',
    totalCases: 5,
    completedCases: 5,
    failedCases: 0,
    startedAt: showcaseFastRun.startedAt,
    completedAt: showcaseFastRun.completedAt,
    createdAt: showcaseFastRun.createdAt,
  },
  {
    id: showcaseDeepRun.id,
    datasetId: showcaseDataset.id,
    name: '完整链路 · Deep',
    datasetName: showcaseDataset.name,
    status: showcaseDeepRun.status,
    mode: 'DEEP',
    totalCases: 5,
    completedCases: 5,
    failedCases: 0,
    startedAt: showcaseDeepRun.startedAt,
    completedAt: showcaseDeepRun.completedAt,
    createdAt: showcaseDeepRun.createdAt,
  },
]

function showcaseResults(run: typeof showcaseFastRun | typeof showcaseDeepRun) {
  const deep = run.requestSnapshot.mode === 'DEEP'
  return Array.from({ length: 5 }, (_, index) => ({
    id: `a0000000-0000-0000-0000-00000000000${index + 8}`,
    evaluationCaseId: `80000000-0000-0000-0000-00000000000${index + 8}`,
    ragRunId: null,
    question: ['组织权限如何影响检索结果？', '如何判断索引是否可发布？', '多目标问题如何补齐证据？', '回答中的引用如何定位原文？', 'Fast 与 Deep 应如何选择？'][index],
    expectedAnswer: '基于版本化证据给出可追溯结论。',
    expectedDocumentIds: [documents[0].id],
    caseMetadata: { benchmark: 'deep-final', position: index + 1 },
    metrics: {
      recallAt5: deep ? 1 : index === 0 ? 1 : 0,
      acceptedEvidenceCoverage: deep ? [0.9669, 0.9624, 0.9362, 0.9348, 0.9595][index] : null,
      semanticAnswerScore: deep ? [1, 0.96, 0.95, 0.98, 0.98][index] : 0.15,
      citationEntailmentScore: deep ? [1, 0.94, 0.85, 0.98, 0.96][index] : 0.35,
      latencyMs: deep ? [63100, 99500, 70100, 69700, 90700][index] : [19900, 27600, 18700, 19800, 20500][index],
      selectedMode: deep ? 'DEEP' : 'FAST',
      citationCount: deep ? 3 : 1,
    },
    errorMessage: null,
    createdAt: run.completedAt,
  }))
}

async function installApi(page: Page) {
  await page.route('**/api/v1/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (path === '/api/v1/auth/login') {
      await route.fulfill({ status: 200, json: session })
      return
    }
    if (path === '/api/v1/knowledge-bases') {
      await route.fulfill({ status: 200, json: knowledgeBases })
      return
    }
    if (path === `/api/v1/knowledge-bases/${knowledgeBases[0].id}/documents`) {
      await route.fulfill({ status: 200, json: documents })
      return
    }
    if (path === `/api/v1/knowledge-bases/${knowledgeBases[0].id}/metadata-schema`) {
      await route.fulfill({ status: 200, json: metadataSchema })
      return
    }
    if (path === '/api/v1/metadata-schema') {
      await route.fulfill({ status: 200, json: { ...metadataSchema, knowledgeBaseId: null } })
      return
    }
    if (path === '/api/v1/metadata-schema/versions') {
      await route.fulfill({ status: 200, json: [{ ...metadataSchema, knowledgeBaseId: null }] })
      return
    }
    if (path === `/api/v1/knowledge-bases/${knowledgeBases[0].id}/metadata-schema/versions`) {
      await route.fulfill({ status: 200, json: [metadataSchema] })
      return
    }
    if (path === `/api/v1/knowledge-bases/${knowledgeBases[0].id}/index-generations`) {
      await route.fulfill({ status: 200, json: indexGenerations })
      return
    }
    if (path === '/api/v1/memory-facts') {
      await route.fulfill({ status: 200, json: memoryFacts })
      return
    }
    if (path === '/api/v1/team/members' && route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: teamMembers })
      return
    }
    if (path === '/api/v1/security/credential-rotation') {
      await route.fulfill({ status: 200, json: credentialRotationStatus })
      return
    }
    if (path === '/api/v1/conversations' && route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: { items: conversations, nextCursor: null } })
      return
    }
    if (path === `/api/v1/conversations/${conversations[0].id}` && route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: conversations[0] })
      return
    }
    if (path === `/api/v1/conversations/${conversations[1].id}` && route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: conversations[1] })
      return
    }
    if (path.endsWith('/messages')) {
      await route.fulfill({
        status: 200,
        json: [
          {
            id: '50000000-0000-0000-0000-000000000001',
            role: 'user',
            content: '知识入库会经历哪些步骤？',
            citations: [],
            restricted: false,
            runId: '51000000-0000-0000-0000-000000000001',
            traceAvailable: false,
            reprocessable: false,
            runStatus: null,
            requestedMode: 'DEEP',
            selectedMode: null,
            answerMode: null,
            retrievalHealth: null,
            evidenceCount: null,
            latencyMs: null,
            assistantName: null,
            assistantProfileVersion: null,
            createdAt: '2026-07-10T08:31:00Z',
          },
          {
            id: '50000000-0000-0000-0000-000000000002',
            role: 'assistant',
            content: '当前流程依次包含解析、规范化、分块、向量化与发布。完成索引后，系统会原子发布当前版本，并保留版本与来源定位。【1】',
            citations: [
              {
                index: 1,
                chunkId: '96000000-0000-0000-0000-000000000001',
                documentId: documents[0].id,
                documentVersionId: documents[0].currentVersionId,
                documentTitle: documents[0].title,
                quote: '索引完成后原子发布当前版本。',
                pageNumber: 3,
                sourceStart: 128,
                sourceEnd: 144,
              },
            ],
            restricted: false,
            runId: '51000000-0000-0000-0000-000000000001',
            traceAvailable: true,
            reprocessable: true,
            runStatus: 'COMPLETED',
            requestedMode: 'DEEP',
            selectedMode: 'DEEP',
            answerMode: 'GROUNDED',
            retrievalHealth: 'SUFFICIENT',
            evidenceCount: 1,
            latencyMs: 38600,
            assistantName: 'VerityForge Agent',
            assistantProfileVersion: 8,
            createdAt: '2026-07-10T08:31:05Z',
          },
        ],
      })
      return
    }
    if (path === `/api/v1/documents/${documents[0].id}`) {
      await route.fulfill({
        status: 200,
        json: {
          id: documents[0].id,
          knowledgeBaseId: knowledgeBases[0].id,
          title: documents[0].title,
          status: documents[0].status,
          currentVersionId: documents[0].currentVersionId,
          accessPolicy: {
            documentId: documents[0].id,
            mode: 'ORGANIZATION',
            allowedRoles: [],
            allowedUserIds: [],
            accessReason: 'ADMIN',
            updatedAt: documents[0].updatedAt,
          },
          versions: [
            {
              id: documents[0].currentVersionId,
              versionNumber: 2,
              sourceName: 'release-governance.pdf',
              sourceType: 'PDF',
              status: 'PUBLISHED',
              validFrom: documents[0].validFrom,
              validTo: null,
              publishedAt: '2026-07-10T08:00:00Z',
              metadata: '{}',
              ingestionJobId: null,
              ingestionStatus: null,
              createdAt: '2026-07-10T07:30:00Z',
            },
          ],
          createdAt: '2026-07-01T00:00:00Z',
          updatedAt: documents[0].updatedAt,
        },
      })
      return
    }
    if (path === `/api/v1/document-versions/${documents[0].currentVersionId}/chunks`) {
      await route.fulfill({
        status: 200,
        json: [
          {
            id: '96000000-0000-0000-0000-000000000002',
            parentChunkId: null,
            type: 'PARENT',
            orderIndex: 0,
            text: '索引完成后原子发布当前版本。',
            contextHeader: '发布流程',
            estimatedTokens: 24,
            tokenizerName: 'estimated',
            tokenCountMethod: 'ESTIMATED',
            sourceMappingStatus: 'MAPPED',
            sourceLocation: 'page 3',
            sourceBlockIds: [],
            renderedMarkdown: '索引完成后原子发布当前版本。',
            enabled: true,
          },
          {
            id: '96000000-0000-0000-0000-000000000001',
            parentChunkId: '96000000-0000-0000-0000-000000000002',
            type: 'CHILD',
            orderIndex: 1,
            text: '索引完成后原子发布当前版本。',
            contextHeader: '发布流程 / 版本发布',
            estimatedTokens: 15,
            tokenizerName: 'estimated',
            tokenCountMethod: 'ESTIMATED',
            sourceMappingStatus: 'MAPPED',
            sourceLocation: 'page 3',
            sourceBlockIds: [],
            renderedMarkdown: '索引完成后原子发布当前版本。',
            enabled: true,
          },
        ],
      })
      return
    }
    if (path === `/api/v1/document-versions/${documents[0].currentVersionId}/asset`) {
      await route.fulfill({
        status: 200,
        json: {
          fileName: 'release-governance.pdf',
          contentType: 'application/pdf',
          byteSize: 2048,
          fileHash: 'fixture-hash',
          previewUrl: 'http://127.0.0.1:4173/fixture/release-governance.pdf',
          previewExpiresAt: '2099-01-01T00:00:00Z',
          createdAt: '2026-07-10T07:30:00Z',
        },
      })
      return
    }
    if (path === `/api/v1/document-versions/${documents[0].currentVersionId}/metadata-revisions`) {
      await route.fulfill({ status: 200, json: [] })
      return
    }
    if (path === `/api/v1/documents/${documents[0].id}/access-policy`
      && route.request().method() === 'PUT') {
      const request = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        status: 200,
        json: {
          documentId: documents[0].id,
          mode: request.mode,
          allowedRoles: request.allowedRoles,
          allowedUserIds: request.allowedUserIds,
          accessReason: 'ADMIN',
          updatedAt: '2026-07-13T14:00:00Z',
        },
      })
      return
    }
    if (path === '/api/v1/evaluation/runs') {
      await route.fulfill({ status: 200, json: [evaluationRunSummary, ...showcaseRunSummaries] })
      return
    }
    if (path === '/api/v1/evaluation/datasets') {
      await route.fulfill({ status: 200, json: [evaluationDataset, showcaseDataset] })
      return
    }
    if (path === `/api/v1/evaluation/datasets/${evaluationDataset.id}`) {
      await route.fulfill({
        status: 200,
        json: {
          dataset: evaluationDataset,
          cases: [
            {
              id: '80000000-0000-0000-0000-000000000001',
              datasetId: evaluationDataset.id,
              question: '发布流程包含哪些阶段？',
              expectedAnswer: '解析、分块、向量化与发布',
              expectedDocumentIds: ['90000000-0000-0000-0000-000000000001'],
              metadata: {
                conversationGroup: 'release-follow-up',
                conversationTurn: 1,
              },
              position: 1,
            },
          ],
          runs: [evaluationRun],
        },
      })
      return
    }
    if (path === `/api/v1/evaluation/datasets/${evaluationDataset.id}/schedules`) {
      await route.fulfill({ status: 200, json: [] })
      return
    }
    if (path === `/api/v1/evaluation/datasets/${evaluationDataset.id}/trends`) {
      await route.fulfill({ status: 200, json: [] })
      return
    }
    if (path === `/api/v1/evaluation/runs/${showcaseFastRun.id}` || path === `/api/v1/evaluation/runs/${showcaseDeepRun.id}`) {
      const run = path.endsWith(showcaseFastRun.id) ? showcaseFastRun : showcaseDeepRun
      await route.fulfill({
        status: 200,
        json: {
          run,
          dataset: showcaseDataset,
          requestSnapshot: run.requestSnapshot,
          results: showcaseResults(run),
        },
      })
      return
    }
    if (path === `/api/v1/evaluation/runs/${evaluationRun.id}`) {
      await route.fulfill({
        status: 200,
        json: {
          run: evaluationRun,
          dataset: evaluationDataset,
          requestSnapshot: { mode: 'FAST', execution: 'STANDARD', judgeMode: 'NONE' },
          results: [
            {
              id: 'a0000000-0000-0000-0000-000000000001',
              evaluationCaseId: '80000000-0000-0000-0000-000000000001',
              ragRunId: null,
              question: '发布流程包含哪些阶段？',
              expectedAnswer: '解析、分块、向量化与发布',
              expectedDocumentIds: [documents[0].id],
              caseMetadata: { conversationGroup: 'release-follow-up', conversationTurn: 1 },
              metrics: {
                recallAt5: 1,
                recallAt10: 1,
                reciprocalRank: 1,
                hitAt10: 1,
                firstRelevantRank: 1,
                latencyMs: 82,
                selectedMode: 'FAST',
                citationCount: 1,
                topDocuments: [],
                conversationGroup: 'release-follow-up',
                conversationTurn: 1,
                conversationReused: false,
              },
              errorMessage: null,
              createdAt: '2026-07-10T08:30:01Z',
            },
          ],
        },
      })
      return
    }
    await route.fulfill({
      status: 404,
      json: { code: 'NOT_FOUND', message: `No test route for ${path}` },
    })
  })
}

async function useSession(page: Page, authSession = session) {
  await page.addInitScript(
    ([key, value]) => window.localStorage.setItem(key, value),
    ['rag-workbench-auth', JSON.stringify(authSession)],
  )
}

test('login exposes a clear API error without claiming success', async ({ page }) => {
  await page.route('**/api/v1/auth/login', (route) => route.abort('connectionrefused'))
  await page.goto('/login')

  await page.getByLabel('用户名').fill('admin')
  await page.getByLabel('密码').fill('admin123!')
  await page.getByRole('button', { name: '进入工作台' }).click()

  await expect(page.getByText('无法连接 API，请确认后端服务已启动')).toBeVisible()
  await expect(page).toHaveURL(/\/login/)
})

test('desktop knowledge workspace is readable and stable', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'desktop-only visual check')
  await installApi(page)
  await useSession(page)
  await page.goto('/knowledge')

  await expect(page.getByRole('heading', { name: '知识库' })).toBeVisible()
  await expect(page.getByText('产品与研发知识')).toBeVisible()
  if (!captureShowcase) {
    await expect(page.locator('body')).toHaveScreenshot('knowledge-desktop.png', {
      animations: 'disabled',
      maxDiffPixelRatio: 0.015,
    })
  }
  await captureDesktop(page, 'knowledgeops-desktop.png')

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})

test('mobile chat keeps history and composer usable', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'mobile', 'mobile-only responsive check')
  await installApi(page)
  await useSession(page)
  await page.goto(`/chat?conversation=${conversations[0].id}`)

  await expect(page.getByText('当前流程依次包含解析、规范化、分块、向量化与发布。')).toBeVisible()
  await expect(page.getByPlaceholder('输入问题，描述得越具体，回答越准确')).toBeVisible()
  await expect(page.locator('body')).toHaveScreenshot('chat-mobile.png', {
    animations: 'disabled',
  })

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})

test('desktop chat keeps conversation history inside the primary sidebar', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'desktop-only history navigation check')
  await installApi(page)
  await useSession(page)
  await page.goto(`/chat?conversation=${conversations[0].id}`)

  await expect(page.getByText('当前流程依次包含解析、规范化、分块、向量化与发布。')).toBeVisible()
  await captureDesktop(page, 'chat-desktop.png')

  const history = page.locator('#conversation-history')
  await expect(page.getByRole('button', { name: /最近对话/ })).toHaveAttribute('aria-expanded', 'true')
  await expect(history.getByTitle(conversations[0].title)).toBeVisible()
  await expect(history.getByTitle(conversations[1].title)).toBeVisible()
  await history.getByTitle(conversations[1].title).click()
  await expect(page).toHaveURL(new RegExp(`conversation=${conversations[1].id}`))
})

test('citation opens the immutable version, chunk and normalized source span', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'desktop evidence drawer check')
  await installApi(page)
  await useSession(page)
  await page.goto(`/chat?conversation=${conversations[0].id}`)

  await page.getByTitle('查看证据 1').click()
  await expect(page.getByTestId('citation-panel').getByText('第 3 页')).toBeVisible()
  await page.getByRole('button', { name: '在文档中查看' }).click()

  await expect(page).toHaveURL(/document=90000000.*documentView=chunks.*chunk=96000000.*page=3.*sourceStart=128.*sourceEnd=144/)
  await expect(page.getByText(/引用定位.*第 3 页.*128.*144/)).toBeVisible()
  await expect(page.locator('#workspace-chunk-96000000-0000-0000-0000-000000000001')).toBeVisible()
})

test('knowledge governance exposes schema and active index generation', async ({ page }) => {
  await installApi(page)
  await useSession(page)
  await page.goto(`/knowledge/${knowledgeBases[0].id}`)

  await page.getByRole('button', { name: 'Metadata 字段' }).click()
  await expect(page.getByRole('heading', { name: '统一文档 Metadata' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'department' })).toBeVisible()
  await page.getByRole('button', { name: '正在构建' }).click()
  await expect(page.getByText('知识库索引')).toBeVisible()
  await expect(page.getByText('构建进度')).toBeVisible()
  await expect(page.getByText('620 / 1260')).toBeVisible()

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})

test('administrator can inspect document versions and immutable metadata', async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== 'desktop', 'desktop document workspace check')
  await installApi(page)
  await useSession(page)

  await page.goto(`/knowledge/${knowledgeBases[0].id}`)
  await page.getByText(documents[0].title).click()
  const workspace = page.getByRole('dialog', { name: '文档工作区' })
  await expect(workspace.getByRole('heading', { name: documents[0].title })).toBeVisible()
  await expect(workspace.getByText('release-governance.pdf · 2.0 KB · v2')).toBeVisible()
  await workspace.getByRole('button', { name: 'Metadata' }).click()
  await expect(workspace.getByRole('heading', { name: '文档 Metadata' })).toBeVisible()
  await expect(workspace.getByText('还没有字段修改记录。')).toBeVisible()
})

test('long-term memory clearly separates confirmation from evidence', async ({ page }) => {
  await installApi(page)
  await useSession(page)
  await page.goto('/memory')

  await expect(page.getByRole('heading', { name: '长期记忆' })).toBeVisible()
  await expect(page.getByText('回答时优先使用中文，并先给直接结论')).toBeVisible()
  await expect(page.getByText('不会作为知识证据')).toBeVisible()

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})

test('administrator manages team roles in a responsive workspace', async ({ page }, testInfo) => {
  await installApi(page)
  await useSession(page)
  let createBody: Record<string, unknown> | null = null
  await page.route('**/api/v1/team/members**', async (route) => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/v1/team/members' && route.request().method() === 'GET') {
      await route.fulfill({ status: 200, json: teamMembers })
      return
    }
    if (url.pathname === '/api/v1/team/members' && route.request().method() === 'POST') {
      createBody = route.request().postDataJSON() as Record<string, unknown>
      await route.fulfill({
        status: 201,
        json: {
          id: '11000000-0000-0000-0000-000000000003',
          username: 'support.viewer',
          displayName: '支持同事',
          role: 'VIEWER',
          enabled: true,
          currentUser: false,
          createdAt: '2026-07-13T08:00:00Z',
          updatedAt: '2026-07-13T08:00:00Z',
        },
      })
      return
    }
    await route.fallback()
  })

  await page.goto('/team')
  await expect(page.getByRole('heading', { name: '团队成员' })).toBeVisible()
  await expect(page.getByText('知识编辑')).toBeVisible()
  await expect(page.getByText('当前登录成员')).toBeVisible()
  await expect(page.getByTitle('重置密码')).toHaveCount(1)

  await page.getByRole('button', { name: '添加成员' }).click()
  await page.getByLabel('显示名称').fill('支持同事')
  await page.getByLabel('用户名').fill('support.viewer')
  await page.getByLabel('角色').selectOption('VIEWER')
  await page.getByLabel('初始密码').fill('SupportPassword123!')
  await page.getByRole('button', { name: '保存成员' }).click()

  await expect.poll(() => createBody).not.toBeNull()
  expect(createBody).toEqual({
    username: 'support.viewer',
    displayName: '支持同事',
    role: 'VIEWER',
    password: 'SupportPassword123!',
  })
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
  if (testInfo.project.name === 'mobile') {
    await expect(page.getByRole('link', { name: '团队' })).toBeVisible()
  }
})

test('viewer cannot discover or open administrator team controls', async ({ page }) => {
  await installApi(page)
  await useSession(page, { ...session, role: 'VIEWER' })
  await page.goto('/team')

  await expect(page).toHaveURL(/\/chat$/)
  await expect(page.getByRole('link', { name: '团队' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '安全' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '团队成员' })).toHaveCount(0)
})

test('administrator verifies and rotates credential envelopes', async ({ page }, testInfo) => {
  await installApi(page)
  await useSession(page)
  let rotationRequested = false
  let currentStatus = credentialRotationStatus
  await page.route('**/api/v1/security/credential-rotation', async (route) => {
    if (route.request().method() === 'POST') {
      rotationRequested = true
      currentStatus = {
        ...credentialRotationStatus,
        needsRotation: 0,
        credentialsByKeyId: { k2026_07: 3 },
        lastRotation: {
          id: '12000000-0000-0000-0000-000000000001',
          activeKeyId: 'k2026_07',
          rotatedBy: session.userId,
          totalCredentials: 3,
          rotatedCredentials: 2,
          sourceCounts: credentialRotationStatus.credentialsBySource,
          previousKeyCounts: credentialRotationStatus.credentialsByKeyId,
          createdAt: '2026-07-13T14:00:00Z',
        },
      }
      await route.fulfill({ status: 200, json: currentStatus })
      return
    }
    await route.fulfill({ status: 200, json: currentStatus })
  })

  await page.goto('/security')
  await expect(page.getByRole('heading', { name: '凭据安全' })).toBeVisible()
  await expect(page.getByText('k2026_07').first()).toBeVisible()
  await expect(page.getByText('模型服务密钥')).toBeVisible()
  await page.getByRole('button', { name: '重新加密', exact: true }).click()
  await expect(page.getByRole('heading', { name: '重新加密历史凭据' })).toBeVisible()
  await page.getByRole('button', { name: '确认重新加密' }).click()

  await expect.poll(() => rotationRequested).toBe(true)
  await expect(page.getByText('全部凭据已使用活动密钥封装。')).toBeVisible()
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
  if (testInfo.project.name === 'mobile') {
    await expect(page.getByRole('link', { name: '安全' })).toBeVisible()
  }
})

test('evaluation workspace restores a persisted run', async ({ page }) => {
  await installApi(page)
  await useSession(page)
  await page.goto('/evaluation')

  if (captureShowcase) {
    await expect(page.getByText('Fast / Deep 完整链路对照')).toBeVisible()
    await captureDesktop(page, 'evaluation-desktop.png', 1000)
    return
  }

  await expect(page.getByText('检索回归集').first()).toBeVisible()
  await page.getByText(evaluationRunSummary.name).click()
  await expect(page.getByText('Recall@5').first()).toBeVisible()
  await expect(page.getByText('100%').first()).toBeVisible()
  await expect(page.getByText('发布流程包含哪些阶段？')).toBeVisible()

  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )
  expect(overflow).toBeLessThanOrEqual(1)
})

test('evaluation dataset view exposes reproducible cases', async ({ page }) => {
  await installApi(page)
  await useSession(page)
  await page.goto('/evaluation')
  await page.getByRole('button', { name: /数据集/ }).click()
  await page.getByText(evaluationDataset.name).click()
  await expect(page).toHaveURL(new RegExp(`/evaluation/datasets/${evaluationDataset.id}`))
  await expect(page.getByRole('heading', { name: evaluationDataset.name })).toBeVisible()
  await expect(page.getByText('发布流程包含哪些阶段？')).toBeVisible()
  await page.getByText('发布流程包含哪些阶段？').click()
  await expect(page.getByText('解析、分块、向量化与发布')).toBeVisible()
})

test('evaluation creation submits Deep mode and knowledge scope', async ({ page }) => {
  await installApi(page)
  await useSession(page)
  let requestBody: Record<string, unknown> | null = null
  await page.route(`**/api/v1/evaluation/datasets/${evaluationDataset.id}/runs`, async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    requestBody = route.request().postDataJSON() as Record<string, unknown>
    await route.fulfill({ status: 202, json: { ...evaluationRun, status: 'QUEUED' } })
  })

  await page.goto(`/evaluation/new?dataset=${evaluationDataset.id}`)
  await page.getByRole('button', { name: '深度' }).click()
  await page.getByLabel('产品与研发知识').check()
  await expect(page.getByText('数据集可用')).toBeVisible()
  await page.getByRole('button', { name: '创建并运行' }).click()

  await expect.poll(() => requestBody).not.toBeNull()
  expect(requestBody).toMatchObject({
    mode: 'DEEP',
    scope: { knowledgeBaseIds: [knowledgeBases[0].id], documentIds: [] },
    filters: [],
    judgeMode: 'ANSWER_AND_CITATIONS',
  })
})
