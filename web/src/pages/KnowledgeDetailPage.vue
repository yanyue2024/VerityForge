<script setup lang="ts">
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import {
  ArrowLeft,
  CalendarClock,
  ChevronDown,
  File,
  FileText,
  FileUp,
  Layers3,
  Search,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import type { DocumentWorkspaceTab } from '@/components/DocumentWorkspace.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import KnowledgeIndexStatus from '@/components/KnowledgeIndexStatus.vue'
import MetadataFilterBuilder from '@/components/MetadataFilterBuilder.vue'
import MetadataSchemaEditor from '@/components/MetadataSchemaEditor.vue'
import StatusPill from '@/components/StatusPill.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import { api, readableError } from '@/lib/api'
import { formatBytes, formatDate } from '@/lib/format'
import { useAuthStore } from '@/stores/auth'
import type { CreateRunRequest, DocumentRow, KnowledgeBase, MetadataSchema } from '@/types/api'

type KnowledgeTab = 'documents' | 'metadata'
type StatusFilter = 'ALL' | 'READY' | 'PROCESSING' | 'FAILED' | 'INACTIVE'
type SortMode = 'UPDATED_DESC' | 'TITLE_ASC' | 'SIZE_DESC'
type MetadataFilter = CreateRunRequest['filters'][number]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const knowledgeBaseId = computed(() => String(route.params.id))
const uploadOpen = ref(false)
const search = ref(typeof route.query.q === 'string' ? route.query.q : '')
const statusFilter = ref<StatusFilter>(parseStatus(route.query.status))
const typeFilter = ref(typeof route.query.type === 'string' ? route.query.type : 'ALL')
const sortMode = ref<SortMode>(parseSort(route.query.sort))
const metadataFilters = ref<MetadataFilter[]>(parseMetadataFilters(route.query.filters))
const DocumentWorkspace = defineAsyncComponent(() => import('@/components/DocumentWorkspace.vue'))

const activeTab = computed<KnowledgeTab>(() => route.query.tab === 'metadata' ? 'metadata' : 'documents')
const activeDocumentId = computed(() => typeof route.query.document === 'string' ? route.query.document : null)
const activeDocumentTab = computed<DocumentWorkspaceTab>(() => {
  const value = String(route.query.documentView || 'original')
  return ['original', 'content', 'chunks', 'metadata', 'processing'].includes(value)
    ? value as DocumentWorkspaceTab
    : 'original'
})
const activeChunkId = computed(() => typeof route.query.chunk === 'string' ? route.query.chunk : null)
const activePageNumber = computed(() => parseNumberQuery(route.query.page))
const activeSourceStart = computed(() => parseNumberQuery(route.query.sourceStart))
const activeSourceEnd = computed(() => parseNumberQuery(route.query.sourceEnd))

const knowledgeQuery = useQuery({
  queryKey: ['knowledge-bases'],
  queryFn: () => api.get<KnowledgeBase[]>('/api/v1/knowledge-bases'),
})
const knowledgeBase = computed(() => knowledgeQuery.data.value?.find((item) => item.id === knowledgeBaseId.value))
const documentsQuery = useQuery(computed(() => ({
  queryKey: ['documents', knowledgeBaseId.value],
  queryFn: () => api.get<DocumentRow[]>(`/api/v1/knowledge-bases/${knowledgeBaseId.value}/documents`),
})))
const schemaQuery = useQuery({
  queryKey: ['metadata-schema', 'organization'],
  queryFn: () => api.get<MetadataSchema>('/api/v1/metadata-schema'),
})

function parseStatus(value: unknown): StatusFilter {
  const text = String(value || 'ALL').toUpperCase()
  return ['ALL', 'READY', 'PROCESSING', 'FAILED', 'INACTIVE'].includes(text) ? text as StatusFilter : 'ALL'
}

function parseSort(value: unknown): SortMode {
  const text = String(value || 'UPDATED_DESC').toUpperCase()
  return ['UPDATED_DESC', 'TITLE_ASC', 'SIZE_DESC'].includes(text) ? text as SortMode : 'UPDATED_DESC'
}

function parseNumberQuery(value: unknown) {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

function parseMetadataFilters(value: unknown): MetadataFilter[] {
  if (typeof value !== 'string' || !value) return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch { return [] }
}

function documentMetadata(document: DocumentRow) {
  if (!document.metadata) return {} as Record<string, unknown>
  try { return JSON.parse(document.metadata) as Record<string, unknown> } catch { return {} as Record<string, unknown> }
}

function documentState(document: DocumentRow): Exclude<StatusFilter, 'ALL'> {
  if (document.status === 'INACTIVE') return 'INACTIVE'
  if (document.versionStatus === 'FAILED' || document.ingestionStatus === 'FAILED') return 'FAILED'
  if (document.versionStatus === 'PROCESSING'
    || ['QUEUED', 'RUNNING', 'AWAITING_REVIEW'].includes(document.ingestionStatus ?? '')
    || !document.currentVersionId) return 'PROCESSING'
  return 'READY'
}

function normalizedValues(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [value]
}

function compareMetadata(actual: unknown, filter: MetadataFilter) {
  const actualValues = normalizedValues(actual).filter((value) => value != null)
  const expectedValues = normalizedValues(filter.value)
  if (!actualValues.length) return false
  if (filter.operator === 'IN') return actualValues.some((actualValue) => expectedValues.some((expected) => String(actualValue) === String(expected)))
  if (filter.operator === 'EQ') return actualValues.some((actualValue) => String(actualValue) === String(filter.value))
  if (filter.operator === 'NE') return actualValues.every((actualValue) => String(actualValue) !== String(filter.value))
  if (filter.operator === 'CONTAINS') return actualValues.some((actualValue) => String(actualValue).toLowerCase().includes(String(filter.value).toLowerCase()))
  return actualValues.some((actualValue) => {
    const actualComparable = Number.isFinite(Number(actualValue)) && Number.isFinite(Number(filter.value))
      ? Number(actualValue)
      : String(actualValue)
    const expectedComparable = typeof actualComparable === 'number' ? Number(filter.value) : String(filter.value)
    if (filter.operator === 'GT') return actualComparable > expectedComparable
    if (filter.operator === 'GTE') return actualComparable >= expectedComparable
    if (filter.operator === 'LT') return actualComparable < expectedComparable
    if (filter.operator === 'LTE') return actualComparable <= expectedComparable
    return false
  })
}

function matchesMetadata(document: DocumentRow) {
  const metadata = documentMetadata(document)
  return metadataFilters.value.every((filter) => compareMetadata(metadata[filter.field], filter))
}

const fileTypes = computed(() => {
  const values = new Set((documentsQuery.data.value ?? []).map((document) => fileType(document)).filter(Boolean))
  return ['ALL', ...Array.from(values).sort()]
})

const filteredDocuments = computed(() => {
  const term = search.value.trim().toLowerCase()
  return (documentsQuery.data.value ?? [])
    .filter((document) => {
      const searchable = `${document.title} ${document.sourceName ?? ''}`.toLowerCase()
      if (term && !searchable.includes(term)) return false
      if (statusFilter.value !== 'ALL' && documentState(document) !== statusFilter.value) return false
      if (typeFilter.value !== 'ALL' && fileType(document) !== typeFilter.value) return false
      return matchesMetadata(document)
    })
    .sort((left, right) => {
      if (sortMode.value === 'TITLE_ASC') return left.title.localeCompare(right.title, 'zh-CN')
      if (sortMode.value === 'SIZE_DESC') return (right.byteSize ?? 0) - (left.byteSize ?? 0)
      return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()
    })
})

const counts = computed(() => (documentsQuery.data.value ?? []).reduce((result, document) => {
  result[documentState(document)] += 1
  return result
}, { READY: 0, PROCESSING: 0, FAILED: 0, INACTIVE: 0 }))

function fileType(document: DocumentRow) {
  const fromSource = document.sourceType?.replace(/^\./, '').trim()
  if (fromSource) return fromSource.toUpperCase()
  const extension = document.sourceName?.split('.').pop()
  return extension ? extension.toUpperCase() : 'FILE'
}

function statusFor(document: DocumentRow) {
  const state = documentState(document)
  return state === 'READY' ? 'READY' : state === 'INACTIVE' ? 'INACTIVE' : document.ingestionStatus || document.versionStatus || state
}

function metadataBadges(document: DocumentRow) {
  const metadata = documentMetadata(document)
  const priority = ['category', 'department', 'organization', 'version', 'document_type']
  const labels = new Map((schemaQuery.data.value?.fields ?? []).map((field) => [field.key, field.label]))
  return Object.entries(metadata)
    .sort(([left], [right]) => {
      const leftPriority = priority.indexOf(left)
      const rightPriority = priority.indexOf(right)
      return (leftPriority < 0 ? 999 : leftPriority) - (rightPriority < 0 ? 999 : rightPriority)
    })
    .filter(([, value]) => value != null && value !== '')
    .slice(0, 2)
    .map(([key, value]) => ({ label: labels.get(key) || key, value: Array.isArray(value) ? value.join('、') : String(value) }))
}

function queryPatch(patch: Record<string, string | undefined>) {
  const next = { ...route.query, ...patch }
  Object.keys(next).forEach((key) => {
    if (next[key] == null || next[key] === '') delete next[key]
  })
  void router.replace({ path: route.path, query: next })
}

function setTab(tab: KnowledgeTab) {
  queryPatch({ tab: tab === 'documents' ? undefined : tab })
}

function openDocument(documentId: string, tab: DocumentWorkspaceTab = 'original', chunkId?: string | null) {
  queryPatch({ document: documentId, documentView: tab === 'original' ? undefined : tab, chunk: chunkId || undefined })
}

function closeDocument() {
  queryPatch({ document: undefined, documentView: undefined, chunk: undefined })
}

function changeDocumentView(tab: DocumentWorkspaceTab, chunkId?: string | null) {
  queryPatch({ documentView: tab === 'original' ? undefined : tab, chunk: chunkId || undefined })
}

watch([search, statusFilter, typeFilter, sortMode, metadataFilters], () => {
  queryPatch({
    q: search.value || undefined,
    status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
    type: typeFilter.value === 'ALL' ? undefined : typeFilter.value,
    sort: sortMode.value === 'UPDATED_DESC' ? undefined : sortMode.value,
    filters: metadataFilters.value.length ? JSON.stringify(metadataFilters.value) : undefined,
  })
}, { deep: true })

watch(() => route.query, (query) => {
  const queryFilters = parseMetadataFilters(query.filters)
  if (search.value !== (typeof query.q === 'string' ? query.q : '')) search.value = typeof query.q === 'string' ? query.q : ''
  if (statusFilter.value !== parseStatus(query.status)) statusFilter.value = parseStatus(query.status)
  if (typeFilter.value !== (typeof query.type === 'string' ? query.type : 'ALL')) typeFilter.value = typeof query.type === 'string' ? query.type : 'ALL'
  if (sortMode.value !== parseSort(query.sort)) sortMode.value = parseSort(query.sort)
  if (JSON.stringify(metadataFilters.value) !== JSON.stringify(queryFilters)) metadataFilters.value = queryFilters
}, { deep: true })
</script>

<template>
  <main class="knowledge-page">
    <RouterLink to="/knowledge" class="inline-flex h-8 items-center gap-2 text-sm font-medium text-ink-500 hover:text-ink-950">
      <ArrowLeft :size="16" aria-hidden="true" />知识库
    </RouterLink>

    <header class="knowledge-header">
      <div class="min-w-0">
        <div class="flex min-w-0 items-center gap-3">
          <h1>{{ knowledgeBase?.name || (knowledgeQuery.isPending.value ? '正在载入' : '知识库') }}</h1>
          <span v-if="knowledgeBase" class="knowledge-count">{{ knowledgeBase.documentCount }} 篇文档</span>
        </div>
        <p>{{ knowledgeBase?.description || '集中管理可检索文档及其解析工件。' }}</p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <KnowledgeIndexStatus :knowledge-base-id="knowledgeBaseId" />
        <button v-if="auth.canEdit" type="button" class="button-primary" @click="uploadOpen = true">
          <FileUp :size="17" aria-hidden="true" />上传文档
        </button>
      </div>
    </header>

    <nav class="knowledge-tabs" aria-label="知识库详情">
      <button type="button" :class="{ active: activeTab === 'documents' }" @click="setTab('documents')">文档</button>
      <button type="button" :class="{ active: activeTab === 'metadata' }" @click="setTab('metadata')">Metadata 字段</button>
    </nav>

    <section v-if="activeTab === 'documents'">
      <div class="document-controls">
        <div class="relative min-w-[260px] flex-1">
          <Search :size="16" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400" aria-hidden="true" />
          <input v-model="search" class="control h-10 pl-9" placeholder="搜索标题或文件名" />
        </div>
        <select v-model="statusFilter" class="control h-10 w-36 py-1.5 text-sm" aria-label="处理状态">
          <option value="ALL">全部状态</option>
          <option value="READY">可检索 {{ counts.READY }}</option>
          <option value="PROCESSING">处理中 {{ counts.PROCESSING }}</option>
          <option value="FAILED">异常 {{ counts.FAILED }}</option>
          <option value="INACTIVE">已停用 {{ counts.INACTIVE }}</option>
        </select>
        <select v-model="typeFilter" class="control h-10 w-32 py-1.5 text-sm" aria-label="文件类型">
          <option v-for="type in fileTypes" :key="type" :value="type">{{ type === 'ALL' ? '全部类型' : type }}</option>
        </select>
        <MetadataFilterBuilder v-model="metadataFilters" :knowledge-base-ids="[knowledgeBaseId]" context="knowledge" />
        <label class="relative">
          <select v-model="sortMode" class="control h-10 w-36 appearance-none py-1.5 pr-8 text-sm" aria-label="排序方式">
            <option value="UPDATED_DESC">最近更新</option>
            <option value="TITLE_ASC">标题排序</option>
            <option value="SIZE_DESC">文件大小</option>
          </select>
          <ChevronDown :size="14" class="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-ink-400" aria-hidden="true" />
        </label>
      </div>

      <div class="result-summary">
        <span>显示 {{ filteredDocuments.length }} / {{ documentsQuery.data.value?.length ?? 0 }} 篇</span>
        <button v-if="search || statusFilter !== 'ALL' || typeFilter !== 'ALL' || metadataFilters.length" type="button" @click="search = ''; statusFilter = 'ALL'; typeFilter = 'ALL'; metadataFilters = []">清除筛选</button>
      </div>

      <div v-if="documentsQuery.isPending.value" class="document-grid">
        <div v-for="index in 8" :key="index" class="h-[176px] animate-pulse rounded-lg bg-paper-100" />
      </div>
      <ErrorState v-else-if="documentsQuery.isError.value" class="mt-7" :message="readableError(documentsQuery.error.value)" @retry="documentsQuery.refetch()" />
      <EmptyState
        v-else-if="!filteredDocuments.length"
        class="mt-10"
        :icon="FileText"
        :title="documentsQuery.data.value?.length ? '没有符合条件的文档' : '还没有文档'"
        :description="documentsQuery.data.value?.length ? '调整搜索、状态、类型或 Metadata 条件。' : '上传文件与 Metadata 清单后，系统会自动解析、分块并建立索引。'"
      >
        <button v-if="auth.canEdit && !documentsQuery.data.value?.length" type="button" class="button-primary" @click="uploadOpen = true"><FileUp :size="17" />上传文档</button>
      </EmptyState>

      <div v-else class="document-grid">
        <article
          v-for="document in filteredDocuments"
          :key="document.id"
          class="document-card"
          role="button"
          tabindex="0"
          @click="openDocument(document.id)"
          @keydown.enter="openDocument(document.id)"
          @keydown.space.prevent="openDocument(document.id)"
        >
          <div class="card-source-track"><i /></div>
          <div class="flex min-w-0 items-start justify-between gap-3">
            <div class="file-mark"><File :size="17" aria-hidden="true" /></div>
            <StatusPill :status="statusFor(document)" />
          </div>
          <div class="mt-3 min-w-0">
            <h2>{{ document.title }}</h2>
            <p class="source-name">{{ document.sourceName || '未记录原文件名' }}</p>
          </div>
          <div class="metadata-badges">
            <span v-for="badge in metadataBadges(document)" :key="badge.label" :title="`${badge.label}：${badge.value}`">{{ badge.value }}</span>
          </div>
          <footer>
            <button type="button" title="打开检索分块" @click.stop="openDocument(document.id, 'chunks')">
              <Layers3 :size="14" aria-hidden="true" />{{ document.chunkCount }} 子块 · {{ document.parentChunkCount }} 父块
            </button>
            <button type="button" title="查看处理过程" @click.stop="openDocument(document.id, 'processing')">
              <CalendarClock :size="14" aria-hidden="true" />{{ formatDate(document.updatedAt, false) }}
            </button>
            <span v-if="document.byteSize != null">{{ formatBytes(document.byteSize) }}</span>
          </footer>
        </article>
      </div>
    </section>

    <section v-else class="metadata-page">
      <div class="metadata-intro">
        <div>
          <h2>文档 Metadata 字段</h2>
          <p>字段结构由系统统一定义，上传清单和后续筛选都复用这里的规则。</p>
        </div>
        <span>当前 Schema v{{ schemaQuery.data.value?.version ?? 0 }}</span>
      </div>
      <MetadataSchemaEditor organization />
    </section>

    <UploadDialog
      :open="uploadOpen"
      :knowledge-base-id="knowledgeBaseId"
      @close="uploadOpen = false"
      @uploaded="documentsQuery.refetch()"
    />
    <DocumentWorkspace
      :open="Boolean(activeDocumentId)"
      :document-id="activeDocumentId"
      :ordered-document-ids="filteredDocuments.map((document) => document.id)"
      :active-tab="activeDocumentTab"
      :initial-chunk-id="activeChunkId"
      :initial-page-number="activePageNumber"
      :initial-source-start="activeSourceStart"
      :initial-source-end="activeSourceEnd"
      @close="closeDocument"
      @navigate="openDocument($event, activeDocumentTab)"
      @change-view="changeDocumentView"
      @updated="documentsQuery.refetch()"
    />
  </main>
</template>

<style scoped>
.knowledge-page { width: 100%; max-width: 1480px; margin: 0 auto; padding: 30px 40px 52px; }
.knowledge-header { display: flex; min-height: 100px; align-items: flex-start; justify-content: space-between; gap: 40px; padding-top: 18px; }
.knowledge-header h1 { overflow: hidden; color: #0d172a; font-size: 27px; font-weight: 680; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.knowledge-header p { max-width: 820px; margin-top: 8px; color: #64748b; font-size: 13px; line-height: 1.65; }
.knowledge-count { flex-shrink: 0; border-radius: 5px; background: #edf1f6; padding: 5px 8px; color: #64748b; font-size: 11px; font-weight: 600; }
.knowledge-tabs { display: flex; height: 49px; align-items: end; gap: 30px; border-bottom: 1px solid #dfe6ef; }
.knowledge-tabs button { position: relative; height: 49px; color: #728198; font-size: 13px; font-weight: 650; }
.knowledge-tabs button.active { color: #111d32; }
.knowledge-tabs button.active::after { position: absolute; inset-inline: 0; bottom: -1px; height: 2px; background: #2d68e8; content: ''; }
.document-controls { display: flex; align-items: center; gap: 10px; padding: 18px 0 13px; }
.result-summary { display: flex; height: 32px; align-items: start; justify-content: space-between; color: #8a97aa; font-size: 11px; }
.result-summary button { color: #2863dd; font-weight: 600; }
.document-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(268px, 1fr)); gap: 14px; }
.document-card { position: relative; display: flex; min-width: 0; height: 176px; cursor: pointer; flex-direction: column; overflow: hidden; border: 1px solid #dfe6ef; border-radius: 8px; background: #fff; padding: 15px 15px 0 18px; transition: border-color 150ms ease, box-shadow 150ms ease, transform 150ms ease; }
.document-card:hover, .document-card:focus-visible { transform: translateY(-1px); border-color: #b8c7da; box-shadow: 0 9px 24px rgba(15, 23, 42, .07); outline: none; }
.card-source-track { position: absolute; top: 16px; bottom: 44px; left: 0; width: 3px; background: #dfe6ef; }
.card-source-track i { display: block; width: 3px; height: 37%; background: #2d68e8; }
.file-mark { display: flex; width: 32px; height: 32px; align-items: center; justify-content: center; border-radius: 6px; background: #eef4ff; color: #2863dd; }
.document-card h2 { overflow: hidden; color: #17243a; font-size: 14px; font-weight: 650; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.source-name { overflow: hidden; margin-top: 4px; color: #8794a8; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.metadata-badges { display: flex; min-height: 24px; align-items: center; gap: 6px; overflow: hidden; margin-top: 10px; }
.metadata-badges span { max-width: 132px; overflow: hidden; border: 1px solid #e2e8f0; border-radius: 4px; background: #f8fafc; padding: 3px 7px; color: #617086; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.document-card footer { display: flex; height: 40px; align-items: center; gap: 11px; margin-top: auto; border-top: 1px solid #edf1f6; color: #8b97a9; font-size: 10px; white-space: nowrap; }
.document-card footer button { display: inline-flex; align-items: center; gap: 4px; color: inherit; }
.document-card footer button:hover { color: #2863dd; }
.document-card footer span { margin-left: auto; }
.metadata-page { padding-top: 4px; }
.metadata-intro { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #e3e9f1; padding: 22px 0 16px; }
.metadata-intro h2 { color: #111d32; font-size: 15px; font-weight: 650; }
.metadata-intro p { margin-top: 5px; color: #728198; font-size: 12px; }
.metadata-intro > span { color: #8794a8; font-size: 11px; }
@media (prefers-reduced-motion: reduce) { .document-card { transition: none; } }
</style>
