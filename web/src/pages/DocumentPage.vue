<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowLeft,
  Braces,
  Check,
  ChevronLeft,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Download,
  ExternalLink,
  FileText,
  GitCompare,
  Layers3,
  ListChecks,
  LoaderCircle,
  Pencil,
  Power,
  RotateCcw,
  Save,
  Upload,
} from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import UploadDialog from '@/components/UploadDialog.vue'
import { api, readableError } from '@/lib/api'
import { formatBytes, formatDate } from '@/lib/format'
import { useAuthStore } from '@/stores/auth'
import type {
  ChunkRow,
  DocumentAsset,
  DocumentContent,
  DocumentDetail,
  DocumentMetadataHistory,
  DocumentVersion,
  IngestionJob,
  IngestionStage,
  MetadataSchema,
  VersionDiff,
} from '@/types/api'

type DocumentTab = 'original' | 'content' | 'chunks' | 'metadata' | 'processing' | 'versions'
interface QualityIssue { code: string; severity: string; message: string; blockIds?: string[] }
interface QualityReport { status: string; score: number; issues: QualityIssue[]; metrics: Record<string, unknown> }

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const auth = useAuthStore()
const documentId = computed(() => String(route.params.documentId))
const selectedVersionId = ref(typeof route.query.version === 'string' ? route.query.version : '')
const compareVersionId = ref('')
const uploadOpen = ref(false)
const metadataEditing = ref(false)
const metadataValues = ref<Record<string, string>>({})
const validFrom = ref('')
const validTo = ref('')
const statusError = ref('')
const metadataError = ref('')
const processingError = ref('')
const expandedParentId = ref('')
const chunkPage = ref(1)
const chunkPageSize = 24
const retryProfile = ref<'AUTO' | 'LIGHTWEIGHT' | 'DOCLING'>('AUTO')
const forceOcr = ref(false)
const selectedChunkId = computed(() => typeof route.query.chunk === 'string' ? route.query.chunk : '')
const activeTab = computed<DocumentTab>(() => {
  const value = String(route.query.tab || 'content').toLowerCase()
  return ['original', 'content', 'chunks', 'metadata', 'processing', 'versions'].includes(value)
    ? value as DocumentTab
    : 'content'
})

const tabs: Array<{ value: DocumentTab; label: string }> = [
  { value: 'original', label: '原文件' },
  { value: 'content', label: '解析正文' },
  { value: 'chunks', label: '检索分块' },
  { value: 'metadata', label: 'Metadata' },
  { value: 'processing', label: '处理过程' },
  { value: 'versions', label: '版本' },
]

const detailQuery = useQuery({
  queryKey: ['document', documentId],
  queryFn: () => api.get<DocumentDetail>(`/api/v1/documents/${documentId.value}`),
})
const selectedVersion = computed<DocumentVersion | undefined>(() =>
  detailQuery.data.value?.versions.find((version) => version.id === selectedVersionId.value),
)
const schemaQuery = useQuery(computed(() => ({
  queryKey: ['metadata-schema', 'organization'],
  queryFn: () => api.get<MetadataSchema>('/api/v1/metadata-schema'),
  enabled: activeTab.value === 'metadata' || metadataEditing.value,
})))
const contentQuery = useQuery(computed(() => ({
  queryKey: ['document-content', selectedVersionId.value],
  queryFn: () => api.get<DocumentContent>(`/api/v1/document-versions/${selectedVersionId.value}/content`),
  enabled: Boolean(selectedVersionId.value) && activeTab.value === 'content',
})))
const chunksQuery = useQuery(computed(() => ({
  queryKey: ['chunks', selectedVersionId.value],
  queryFn: () => api.get<ChunkRow[]>(`/api/v1/document-versions/${selectedVersionId.value}/chunks`),
  enabled: Boolean(selectedVersionId.value) && (activeTab.value === 'chunks' || Boolean(selectedChunkId.value)),
})))
const assetQuery = useQuery(computed(() => ({
  queryKey: ['document-asset', selectedVersionId.value],
  queryFn: () => api.get<DocumentAsset>(`/api/v1/document-versions/${selectedVersionId.value}/asset`),
  enabled: Boolean(selectedVersionId.value),
  staleTime: 8 * 60 * 1_000,
})))
const revisionsQuery = useQuery(computed(() => ({
  queryKey: ['document-metadata-revisions', selectedVersionId.value],
  queryFn: () => api.get<DocumentMetadataHistory[]>(`/api/v1/document-versions/${selectedVersionId.value}/metadata-revisions`),
  enabled: Boolean(selectedVersionId.value) && activeTab.value === 'metadata',
})))
const ingestionQuery = useQuery(computed(() => ({
  queryKey: ['ingestion-job', selectedVersion.value?.ingestionJobId],
  queryFn: () => api.get<IngestionJob>(`/api/v1/ingestion-jobs/${selectedVersion.value?.ingestionJobId}`),
  enabled: Boolean(selectedVersion.value?.ingestionJobId) && activeTab.value === 'processing',
  refetchInterval: (query: { state: { data?: IngestionJob } }) =>
    ['SUCCEEDED', 'FAILED', 'CANCELLED', 'AWAITING_REVIEW'].includes(query.state.data?.status ?? '') ? false : 2_500,
})))
const diffQuery = useQuery(computed(() => ({
  queryKey: ['version-diff', documentId.value, compareVersionId.value, selectedVersionId.value],
  queryFn: () => api.get<VersionDiff>(`/api/v1/documents/${documentId.value}/version-diff?fromVersionId=${compareVersionId.value}&toVersionId=${selectedVersionId.value}`),
  enabled: activeTab.value === 'versions' && Boolean(compareVersionId.value)
    && Boolean(selectedVersionId.value) && compareVersionId.value !== selectedVersionId.value,
})))

const parentChunks = computed(() => new Map(
  (chunksQuery.data.value ?? []).filter((chunk) => chunk.type === 'PARENT').map((chunk) => [chunk.id, chunk]),
))
const childChunks = computed(() => (chunksQuery.data.value ?? []).filter((chunk) => chunk.type === 'CHILD'))
const chunkPageCount = computed(() => Math.max(1, Math.ceil(childChunks.value.length / chunkPageSize)))
const visibleChildChunks = computed(() => {
  const start = (chunkPage.value - 1) * chunkPageSize
  return childChunks.value.slice(start, start + chunkPageSize)
})
const chunkRangeStart = computed(() => childChunks.value.length ? (chunkPage.value - 1) * chunkPageSize + 1 : 0)
const chunkRangeEnd = computed(() => Math.min(chunkPage.value * chunkPageSize, childChunks.value.length))
const selectedSourceBlockIds = computed(() => new Set(
  (chunksQuery.data.value ?? []).find((chunk) => chunk.id === selectedChunkId.value)?.sourceBlockIds ?? [],
))
function safeMarkdown(markdown: string | null | undefined) {
  if (!markdown?.trim()) return ''
  const html = marked.parse(markdown, { gfm: true, breaks: false, async: false }) as string
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}
const renderedMarkdown = computed(() => {
  const markdown = contentQuery.data.value?.normalizedMarkdown?.trim()
  return safeMarkdown(markdown)
})
const qualityReport = computed<QualityReport | null>(() => {
  const raw = contentQuery.data.value?.parseQualityReport || selectedVersion.value?.parseQualityReport
  if (!raw) return null
  try { return JSON.parse(raw) as QualityReport } catch { return null }
})
const isPdfPreview = computed(() => assetQuery.data.value?.contentType === 'application/pdf')
const isTextPreview = computed(() => assetQuery.data.value?.contentType?.startsWith('text/') ?? false)

watch(() => detailQuery.data.value, (detail) => {
  if (!detail) return
  if (!detail.versions.some((version) => version.id === selectedVersionId.value)) {
    selectedVersionId.value = detail.currentVersionId || detail.versions[0]?.id || ''
  }
  compareVersionId.value = detail.versions.find((version) => version.id !== selectedVersionId.value)?.id ?? ''
}, { immediate: true })
watch(selectedVersionId, async (versionId) => {
  if (!versionId) return
  expandedParentId.value = ''
  chunkPage.value = 1
  await router.replace({ path: route.path, query: { ...route.query, version: versionId } })
})
watch(() => [activeTab.value, selectedChunkId.value, chunksQuery.data.value] as const, async ([tab, chunkId, chunks]) => {
  if (tab !== 'chunks' || !chunkId || !chunks?.some((chunk) => chunk.id === chunkId)) return
  const selected = chunks.find((chunk) => chunk.id === chunkId)
  if (selected?.type === 'CHILD') {
    const index = childChunks.value.findIndex((chunk) => chunk.id === selected.id)
    if (index >= 0) chunkPage.value = Math.floor(index / chunkPageSize) + 1
  }
  const scrollTarget = selected?.type === 'PARENT'
    ? chunks.find((chunk) => chunk.type === 'CHILD' && chunk.parentChunkId === selected.id)?.id
    : selected?.id
  if (selected?.type === 'PARENT') expandedParentId.value = selected.id
  await nextTick()
  if (scrollTarget) document.getElementById(`chunk-${scrollTarget}`)?.scrollIntoView({ block: 'center' })
})

function setTab(tab: DocumentTab) {
  void router.replace({ path: route.path, query: { ...route.query, tab: tab === 'content' ? undefined : tab } })
}
function parseMetadata(version: DocumentVersion | undefined) {
  if (!version?.metadata) return {}
  try { return JSON.parse(version.metadata) as Record<string, unknown> } catch { return {} }
}
function toInputValue(value: unknown, type?: string) {
  if (Array.isArray(value)) return value.join(', ')
  if (value == null) return ''
  if (type === 'DATETIME' && typeof value === 'string') return value.slice(0, 16)
  return String(value)
}
function startMetadataEdit() {
  const metadata = parseMetadata(selectedVersion.value)
  metadataValues.value = Object.fromEntries(
    (schemaQuery.data.value?.fields ?? []).map((field) => [field.key, toInputValue(metadata[field.key], field.type)]),
  )
  validFrom.value = selectedVersion.value?.validFrom?.slice(0, 16) ?? ''
  validTo.value = selectedVersion.value?.validTo?.slice(0, 16) ?? ''
  metadataError.value = ''
  metadataEditing.value = true
}
function buildMetadata() {
  const result: Record<string, unknown> = {}
  for (const field of schemaQuery.data.value?.fields ?? []) {
    const raw = metadataValues.value[field.key]?.trim() ?? ''
    if (!raw) {
      if (field.required) throw new Error(`${field.label} 为必填字段`)
      continue
    }
    if (field.type === 'NUMBER') result[field.key] = Number(raw)
    else if (field.type === 'BOOLEAN') result[field.key] = raw === 'true'
    else if (field.type === 'TEXT_LIST') result[field.key] = raw.split(',').map((value) => value.trim()).filter(Boolean)
    else if (field.type === 'DATETIME') result[field.key] = new Date(raw).toISOString()
    else result[field.key] = raw
  }
  return result
}
const metadataMutation = useMutation({
  mutationFn: () => api.patch(`/api/v1/document-versions/${selectedVersionId.value}/metadata`, {
    metadata: buildMetadata(),
    validFrom: validFrom.value ? new Date(validFrom.value).toISOString() : null,
    validTo: validTo.value ? new Date(validTo.value).toISOString() : null,
  }),
  onSuccess: async () => {
    metadataEditing.value = false
    await Promise.all([detailQuery.refetch(), revisionsQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: ['documents'] }),
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases'] })])
  },
  onError: (error) => { metadataError.value = readableError(error) },
})
const statusMutation = useMutation({
  mutationFn: () => api.patch(`/api/v1/documents/${documentId.value}/status`, {
    status: detailQuery.data.value?.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
  }),
  onSuccess: async () => {
    statusError.value = ''
    await Promise.all([detailQuery.refetch(), queryClient.invalidateQueries({ queryKey: ['documents'] }),
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases'] })])
  },
  onError: (error) => { statusError.value = readableError(error) },
})
const approveMutation = useMutation({
  mutationFn: () => api.post(`/api/v1/ingestion-jobs/${selectedVersion.value?.ingestionJobId}/approve-quality`),
  onSuccess: async () => {
    processingError.value = ''
    await Promise.all([detailQuery.refetch(), ingestionQuery.refetch()])
  },
  onError: (error) => { processingError.value = readableError(error) },
})
const retryMutation = useMutation({
  mutationFn: () => api.post(`/api/v1/ingestion-jobs/${selectedVersion.value?.ingestionJobId}/retry-with-parser`, {
    parserProfile: retryProfile.value,
    options: { forceOcr: forceOcr.value },
  }),
  onSuccess: async () => {
    processingError.value = ''
    await Promise.all([detailQuery.refetch(), ingestionQuery.refetch()])
  },
  onError: (error) => { processingError.value = readableError(error) },
})

function stageLabel(stage: string) {
  return ({ PARSE: '文档解析', NORMALIZE: '正文规范化', QUALITY: '质量检查', CHUNK: '父子分块',
    EMBED: '生成向量', PUBLISH: '发布版本' } as Record<string, string>)[stage] || stage
}
function duration(start: string | null, end: string | null) {
  if (!start) return '—'
  const ms = (end ? new Date(end).getTime() : Date.now()) - new Date(start).getTime()
  if (!Number.isFinite(ms) || ms < 0) return '—'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}
function stageMetrics(stage: IngestionStage) {
  try { return JSON.parse(stage.metrics) as Record<string, unknown> } catch { return {} }
}
function stageSummary(stage: IngestionStage) {
  const metrics = stageMetrics(stage)
  if (stage.stage === 'PARSE' && metrics.blocks != null) return `${metrics.blocks} 个结构块 · ${metrics.parser ?? '自动解析'}`
  if (stage.stage === 'QUALITY' && metrics.score != null) return `${metrics.score} 分 · ${metrics.issues ?? 0} 个提示`
  if (stage.stage === 'CHUNK') return `${metrics.children ?? 0} 个子块 · ${metrics.parents ?? 0} 个父块`
  if (stage.stage === 'EMBED') return `${metrics.created ?? 0} 个新向量 · 复用 ${metrics.reused ?? 0}`
  if (stage.stage === 'PUBLISH' && stage.status === 'SUCCEEDED') return '当前版本已原子发布'
  return stage.status === 'PENDING' ? '等待前序阶段' : stage.status === 'RUNNING' ? '正在处理' : '—'
}
function isSelectedBlock(blockId: string) { return selectedSourceBlockIds.value.has(blockId) }
function toggleParent(parentId: string | null) {
  if (!parentId) return
  expandedParentId.value = expandedParentId.value === parentId ? '' : parentId
}
function renderChunk(chunk: ChunkRow | undefined) {
  return safeMarkdown(chunk?.renderedMarkdown || chunk?.text)
}
function parentAddsContext(chunk: ChunkRow) {
  if (!chunk.parentChunkId) return false
  const parent = parentChunks.value.get(chunk.parentChunkId)
  return Boolean(parent && parent.text.trim() !== chunk.text.trim())
}
function setChunkPage(page: number) {
  chunkPage.value = Math.max(1, Math.min(chunkPageCount.value, page))
  expandedParentId.value = ''
  void nextTick(() => document.getElementById('chunk-list-start')?.scrollIntoView({ block: 'start' }))
}
</script>

<template>
  <div class="mx-auto w-full max-w-[1440px] px-10 py-8">
    <RouterLink :to="`/knowledge/${route.params.id}`" class="inline-flex h-8 items-center gap-2 text-sm font-medium text-ink-500 hover:text-ink-950">
      <ArrowLeft :size="16" aria-hidden="true" />返回知识库
    </RouterLink>

    <div v-if="detailQuery.isPending.value" class="mt-8 h-32 animate-pulse bg-paper-100" />
    <ErrorState v-else-if="detailQuery.isError.value" class="mt-8" :message="readableError(detailQuery.error.value)" @retry="detailQuery.refetch()" />

    <template v-else-if="detailQuery.data.value">
      <header class="mt-5 flex items-start justify-between gap-8">
        <div class="min-w-0">
          <div class="flex items-center gap-3">
            <h1 class="truncate text-[26px] font-semibold leading-tight text-ink-950">{{ detailQuery.data.value.title }}</h1>
            <StatusPill :status="selectedVersion?.status || detailQuery.data.value.status" />
          </div>
          <div class="mt-3 flex items-center gap-4 text-xs text-ink-500">
            <span>v{{ selectedVersion?.versionNumber ?? '—' }}</span>
            <span>{{ assetQuery.data.value?.fileName || selectedVersion?.sourceName }}</span>
            <span v-if="assetQuery.data.value">{{ formatBytes(assetQuery.data.value.byteSize) }}</span>
            <span>更新于 {{ formatDate(detailQuery.data.value.updatedAt) }}</span>
          </div>
        </div>
        <div class="flex shrink-0 items-center gap-2">
          <button v-if="auth.canEdit" type="button" class="button-secondary" @click="uploadOpen = true"><Upload :size="16" aria-hidden="true" />新版本</button>
          <button v-if="auth.canEdit" type="button" class="button-secondary" :disabled="statusMutation.isPending.value" @click="statusMutation.mutate()"><Power :size="16" aria-hidden="true" />{{ detailQuery.data.value.status === 'ACTIVE' ? '暂停检索' : '恢复检索' }}</button>
        </div>
      </header>

      <div class="mt-8 flex items-end justify-between border-b border-paper-200">
        <nav class="flex h-11 items-end gap-7" aria-label="文档详情">
          <button v-for="tab in tabs" :key="tab.value" type="button" class="relative h-11 text-sm font-semibold" :class="activeTab === tab.value ? 'text-ink-950' : 'text-ink-500 hover:text-ink-900'" @click="setTab(tab.value)">
            {{ tab.label }}<span v-if="activeTab === tab.value" class="absolute inset-x-0 bottom-0 h-0.5 bg-ink-950" />
          </button>
        </nav>
        <label class="mb-2 flex items-center gap-2 text-xs text-ink-500">当前版本
          <select v-model="selectedVersionId" class="control h-8 w-44 py-1 text-xs"><option v-for="version in detailQuery.data.value.versions" :key="version.id" :value="version.id">v{{ version.versionNumber }} · {{ version.status }}</option></select>
          <ChevronDown :size="13" class="-ml-7 pointer-events-none text-ink-400" aria-hidden="true" />
        </label>
      </div>

      <section v-if="activeTab === 'original'" class="pt-6">
        <div class="flex min-h-12 items-center justify-between border-b border-paper-200 pb-4">
          <div class="flex min-w-0 items-center gap-3"><FileText :size="18" class="text-ink-400" aria-hidden="true" /><div class="min-w-0"><p class="truncate text-sm font-semibold text-ink-900">{{ assetQuery.data.value?.fileName }}</p><p class="mt-0.5 text-xs text-ink-400">{{ assetQuery.data.value?.contentType }} · 原始上传文件</p></div></div>
          <a v-if="assetQuery.data.value" :href="assetQuery.data.value.previewUrl" target="_blank" rel="noopener" class="button-secondary"><ExternalLink :size="15" aria-hidden="true" />在新窗口打开</a>
        </div>
        <ErrorState v-if="assetQuery.isError.value" class="mt-6" :message="readableError(assetQuery.error.value)" @retry="assetQuery.refetch()" />
        <!-- Chrome's built-in PDF viewer cannot run inside a sandboxed frame. The URL is a
             short-lived, same-origin MinIO signature and the response is forced to application/pdf. -->
        <iframe v-else-if="assetQuery.data.value && isPdfPreview" :src="assetQuery.data.value.previewUrl" :title="`${assetQuery.data.value.fileName} 原文件预览`" class="mt-5 h-[calc(100vh-310px)] min-h-[620px] w-full border border-paper-200 bg-white" />
        <iframe v-else-if="assetQuery.data.value && isTextPreview" :src="assetQuery.data.value.previewUrl" :title="`${assetQuery.data.value.fileName} 原文件预览`" sandbox="allow-same-origin allow-downloads" class="mt-5 h-[calc(100vh-310px)] min-h-[620px] w-full border border-paper-200 bg-white" />
        <div v-else-if="assetQuery.data.value" class="mx-auto flex min-h-[480px] max-w-xl flex-col items-center justify-center text-center"><FileText :size="36" class="text-ink-300" aria-hidden="true" /><h2 class="mt-5 text-lg font-semibold text-ink-950">该格式请使用本地应用查看</h2><p class="mt-2 text-sm text-ink-500">原文件未经过转换，下载后可完整保留排版和内容。</p><a :href="assetQuery.data.value.previewUrl" class="button-primary mt-5"><Download :size="16" aria-hidden="true" />打开原文件</a></div>
      </section>

      <section v-else-if="activeTab === 'content'" class="pt-6">
        <div class="flex items-center gap-5 border-b border-paper-200 pb-4 text-xs text-ink-500">
          <span>{{ contentQuery.data.value?.totalBlocks ?? '—' }} 个结构块</span><span>{{ selectedVersion?.parserName || '等待解析' }}<template v-if="selectedVersion?.parserVersion"> · {{ selectedVersion.parserVersion }}</template></span><span>质量 {{ selectedVersion?.parseQualityScore ?? '—' }} 分</span><span class="ml-auto">只读解析工件</span>
        </div>
        <div v-if="selectedVersion?.parseQualityStatus && selectedVersion.parseQualityStatus !== 'PASS'" class="mt-5 flex items-start gap-3 border-l-2 px-4 py-3" :class="selectedVersion.parseQualityStatus === 'FAIL' ? 'border-coral-600 bg-coral-50 text-coral-800' : 'border-amber-500 bg-amber-50 text-amber-900'"><CircleAlert :size="18" class="mt-0.5 shrink-0" aria-hidden="true" /><div><p class="text-sm font-semibold">{{ selectedVersion.parseQualityStatus === 'FAIL' ? '解析质量未通过' : '解析结果需要确认' }}</p><p class="mt-1 text-xs opacity-80">{{ qualityReport?.issues?.[0]?.message || '请检查正文预览和处理过程。' }}</p></div><button type="button" class="ml-auto text-xs font-semibold" @click="setTab('processing')">查看处理过程</button></div>
        <div v-if="contentQuery.isPending.value" class="space-y-4 pt-6"><div v-for="item in 6" :key="item" class="h-12 animate-pulse bg-paper-100" /></div>
        <ErrorState v-else-if="contentQuery.isError.value" class="mt-6" :message="readableError(contentQuery.error.value)" @retry="contentQuery.refetch()" />
        <div v-else-if="!renderedMarkdown" class="py-20 text-center text-sm text-ink-500">规范化正文尚未生成。</div>
        <article v-else class="normalized-markdown mx-auto max-w-[900px] py-10" v-html="renderedMarkdown" />
        <div v-if="selectedChunkId && selectedSourceBlockIds.size" class="sr-only"><span v-for="block in contentQuery.data.value?.blocks.filter((item) => isSelectedBlock(item.id))" :key="block.id">{{ block.text }}</span></div>
      </section>

      <section v-else-if="activeTab === 'chunks'" class="pt-6">
        <div class="flex items-center justify-between border-b border-paper-200 pb-4"><div class="flex items-center gap-2"><Layers3 :size="17" class="text-ink-400" aria-hidden="true" /><h2 class="text-base font-semibold">检索分块</h2><span class="ml-2 text-xs text-ink-400">{{ childChunks.length }} 个子块 · {{ parentChunks.size }} 个父块</span></div><span class="text-xs text-ink-500">250 / 1000 Token · 只读</span></div>
        <div v-if="chunksQuery.isPending.value" class="space-y-3 pt-5"><div v-for="item in 6" :key="item" class="h-24 animate-pulse bg-paper-100" /></div>
        <ErrorState v-else-if="chunksQuery.isError.value" class="mt-5" :message="readableError(chunksQuery.error.value)" @retry="chunksQuery.refetch()" />
        <div v-else-if="!childChunks.length" class="py-20 text-center text-sm text-ink-500">该版本还没有可检索子块。</div>
        <div v-else id="chunk-list-start" class="border-b border-paper-200 scroll-mt-6">
          <article v-for="chunk in visibleChildChunks" :id="`chunk-${chunk.id}`" :key="chunk.id" class="chunk-row relative border-b border-paper-200 py-6 pl-10 pr-2 last:border-b-0" :class="chunk.id === selectedChunkId ? 'bg-brand-50/60' : ''">
            <span class="source-rail absolute bottom-6 left-3 top-6 w-px bg-paper-300"><span class="absolute -left-[3px] top-1 size-[7px] rounded-full bg-brand-600" /></span>
            <div class="flex items-start justify-between gap-6"><div class="min-w-0 flex-1"><p class="truncate text-xs font-semibold text-brand-700">{{ chunk.contextHeader || '未命名章节' }}</p><div class="chunk-markdown mt-3" v-html="renderChunk(chunk)" /></div><div class="w-32 shrink-0 text-right text-xs tabular-nums text-ink-400"><p>#{{ chunk.orderIndex + 1 }}</p><p class="mt-1">{{ chunk.tokenCountMethod === 'EXACT' ? '' : '约 ' }}{{ chunk.estimatedTokens }} tokens</p></div></div>
            <div class="mt-4 flex items-center gap-4 text-xs text-ink-500"><span>{{ chunk.sourceLocation }}</span><span :class="chunk.sourceMappingStatus === 'MAPPED' ? 'text-evidence-700' : 'text-coral-700'">{{ chunk.sourceMappingStatus === 'MAPPED' ? '来源已定位' : '来源未定位' }}</span><button v-if="parentAddsContext(chunk)" type="button" class="ml-auto inline-flex items-center gap-1 font-semibold text-ink-600 hover:text-ink-950" @click="toggleParent(chunk.parentChunkId)"><ChevronRight :size="14" class="transition-transform" :class="expandedParentId === chunk.parentChunkId ? 'rotate-90' : ''" aria-hidden="true" />{{ expandedParentId === chunk.parentChunkId ? '收起父块' : '查看父块上下文' }}</button></div>
            <div v-if="expandedParentId === chunk.parentChunkId && parentAddsContext(chunk)" class="mt-5 border-l-2 border-evidence-500 bg-[#f4faf7] px-5 py-4"><div class="flex items-center justify-between text-xs"><span class="font-semibold text-evidence-800">父块上下文</span><span class="tabular-nums text-ink-400">{{ parentChunks.get(chunk.parentChunkId)?.estimatedTokens ?? '—' }} tokens</span></div><div class="chunk-markdown parent-markdown mt-3" v-html="renderChunk(parentChunks.get(chunk.parentChunkId))" /></div>
          </article>
        </div>
        <footer v-if="childChunks.length > chunkPageSize" class="flex h-16 items-center justify-between border-b border-paper-200 text-xs text-ink-500"><span>显示 {{ chunkRangeStart }}–{{ chunkRangeEnd }}，共 {{ childChunks.length }} 个子块</span><div class="flex items-center gap-2"><button type="button" class="icon-button size-8" title="上一页" :disabled="chunkPage <= 1" @click="setChunkPage(chunkPage - 1)"><ChevronLeft :size="16" aria-hidden="true" /></button><span class="min-w-16 text-center tabular-nums">{{ chunkPage }} / {{ chunkPageCount }}</span><button type="button" class="icon-button size-8" title="下一页" :disabled="chunkPage >= chunkPageCount" @click="setChunkPage(chunkPage + 1)"><ChevronRight :size="16" aria-hidden="true" /></button></div></footer>
      </section>

      <section v-else-if="activeTab === 'metadata'" class="pt-6">
        <div class="flex items-center justify-between border-b border-paper-200 pb-4"><div class="flex items-center gap-2"><Braces :size="17" class="text-ink-400" aria-hidden="true" /><h2 class="text-base font-semibold">文档 Metadata</h2></div><button v-if="auth.canEdit && !metadataEditing && selectedVersion?.status === 'PUBLISHED' && selectedVersion.id === detailQuery.data.value.currentVersionId" type="button" class="button-secondary min-h-9 px-3" @click="startMetadataEdit"><Pencil :size="15" aria-hidden="true" />编辑字段</button></div>
        <div v-if="metadataEditing" class="mt-6 max-w-4xl"><div class="grid grid-cols-2 gap-4"><label class="field-label">生效时间<input v-model="validFrom" type="datetime-local" class="field-input" /></label><label class="field-label">失效时间<input v-model="validTo" type="datetime-local" class="field-input" /></label><label v-for="field in schemaQuery.data.value?.fields" :key="field.key" class="field-label">{{ field.label }}<span v-if="field.required" class="text-coral-700"> *</span><select v-if="field.type === 'BOOLEAN'" v-model="metadataValues[field.key]" class="field-input"><option value="">未设置</option><option value="true">是</option><option value="false">否</option></select><select v-else-if="field.allowedValues.length && field.type !== 'TEXT_LIST'" v-model="metadataValues[field.key]" class="field-input"><option value="">请选择</option><option v-for="value in field.allowedValues" :key="value" :value="value">{{ value }}</option></select><input v-else v-model="metadataValues[field.key]" class="field-input" :type="field.type === 'NUMBER' ? 'number' : field.type === 'DATE' ? 'date' : field.type === 'DATETIME' ? 'datetime-local' : 'text'" /></label></div><p v-if="metadataError" class="mt-4 bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ metadataError }}</p><div class="mt-5 flex justify-end gap-2"><button type="button" class="button-secondary" @click="metadataEditing = false">取消</button><button type="button" class="button-primary" :disabled="metadataMutation.isPending.value" @click="metadataMutation.mutate()"><LoaderCircle v-if="metadataMutation.isPending.value" :size="15" class="animate-spin" aria-hidden="true" /><Save v-else :size="15" aria-hidden="true" />保存字段</button></div></div>
        <dl v-else class="mt-5 max-w-4xl divide-y divide-paper-200 border-y border-paper-200"><div v-for="(value, key) in parseMetadata(selectedVersion)" :key="key" class="grid min-h-14 grid-cols-[180px_1fr] items-center gap-3 text-sm"><dt class="text-ink-500">{{ key }}</dt><dd class="break-words text-ink-800">{{ Array.isArray(value) ? value.join('、') : String(value) }}</dd></div><div class="grid min-h-14 grid-cols-[180px_1fr] items-center gap-3 text-sm"><dt class="text-ink-500">生效时间</dt><dd>{{ formatDate(selectedVersion?.validFrom) }}</dd></div><div class="grid min-h-14 grid-cols-[180px_1fr] items-center gap-3 text-sm"><dt class="text-ink-500">失效时间</dt><dd>{{ formatDate(selectedVersion?.validTo) }}</dd></div></dl>
        <div class="mt-10 max-w-4xl"><h3 class="text-sm font-semibold text-ink-950">修改历史</h3><p v-if="!revisionsQuery.data.value?.length" class="mt-3 text-sm text-ink-500">还没有 Metadata 修改记录。</p><div v-else class="mt-3 divide-y divide-paper-200 border-y border-paper-200"><div v-for="revision in revisionsQuery.data.value" :key="revision.revisionId" class="flex items-center gap-4 py-3 text-xs"><span class="text-ink-500">{{ formatDate(revision.createdAt) }}</span><span class="font-medium text-ink-700">{{ revision.changedBy || '管理员' }}</span><span class="ml-auto text-ink-400">字段修订</span></div></div></div>
      </section>

      <section v-else-if="activeTab === 'processing'" class="pt-6 max-w-5xl">
        <div class="flex items-center justify-between border-b border-paper-200 pb-4"><div class="flex items-center gap-2"><ListChecks :size="17" class="text-ink-400" aria-hidden="true" /><h2 class="text-base font-semibold">处理过程</h2></div><StatusPill :status="ingestionQuery.data.value?.status || selectedVersion?.ingestionStatus" /></div>
        <div v-if="selectedVersion?.parseQualityStatus === 'WARNING' || selectedVersion?.parseQualityStatus === 'FAIL'" class="mt-5 border-l-2 px-5 py-4" :class="selectedVersion.parseQualityStatus === 'FAIL' ? 'border-coral-600 bg-coral-50' : 'border-amber-500 bg-amber-50'"><div class="flex items-start gap-3"><CircleAlert :size="18" class="mt-0.5 shrink-0" aria-hidden="true" /><div><p class="text-sm font-semibold text-ink-950">质量检查 {{ selectedVersion.parseQualityScore }} 分</p><p class="mt-1 text-xs leading-5 text-ink-600">{{ qualityReport?.issues?.[0]?.message || '解析结果需要人工确认。' }}</p></div><button v-if="selectedVersion.parseQualityStatus === 'WARNING' && ingestionQuery.data.value?.status === 'AWAITING_REVIEW'" type="button" class="button-primary ml-auto" :disabled="approveMutation.isPending.value" @click="approveMutation.mutate()"><Check :size="15" aria-hidden="true" />确认并继续</button></div><ul v-if="qualityReport?.issues?.length" class="mt-3 space-y-1 pl-8 text-xs text-ink-600"><li v-for="issue in qualityReport.issues" :key="issue.code">{{ issue.message }}</li></ul></div>
        <div v-if="!selectedVersion?.ingestionJobId" class="py-16 text-center text-sm text-ink-500">该版本还没有处理任务。</div>
        <div v-else-if="ingestionQuery.isPending.value" class="space-y-3 pt-5"><div v-for="item in 6" :key="item" class="h-14 animate-pulse bg-paper-100" /></div>
        <ErrorState v-else-if="ingestionQuery.isError.value" class="mt-5" :message="readableError(ingestionQuery.error.value)" @retry="ingestionQuery.refetch()" />
        <div v-else class="mt-5 border-y border-paper-200"><div v-for="(stage, index) in ingestionQuery.data.value?.stages" :key="stage.stage" class="grid min-h-20 grid-cols-[44px_170px_120px_1fr_90px] items-center gap-3 border-b border-paper-200 last:border-b-0"><span class="flex size-7 items-center justify-center rounded-full text-xs font-semibold" :class="stage.status === 'SUCCEEDED' ? 'bg-evidence-50 text-evidence-700' : stage.status === 'FAILED' ? 'bg-coral-50 text-coral-700' : stage.status === 'RUNNING' ? 'bg-brand-50 text-brand-700' : 'bg-paper-100 text-ink-500'">{{ index + 1 }}</span><span class="text-sm font-semibold text-ink-900">{{ stageLabel(stage.stage) }}</span><StatusPill :status="stage.status" /><div class="min-w-0"><p class="truncate text-xs text-ink-600">{{ stageSummary(stage) }}</p><details v-if="Object.keys(stageMetrics(stage)).length" class="mt-1 text-[11px] text-ink-400"><summary class="cursor-pointer select-none">查看阶段数据</summary><pre class="mt-2 max-h-44 overflow-auto whitespace-pre-wrap bg-paper-50 p-3">{{ JSON.stringify(stageMetrics(stage), null, 2) }}</pre></details></div><span class="text-right text-xs tabular-nums text-ink-500">{{ duration(stage.startedAt, stage.completedAt) }}</span></div></div>
        <div v-if="['FAILED', 'AWAITING_REVIEW'].includes(ingestionQuery.data.value?.status ?? '')" class="mt-7 border-t border-paper-200 pt-5"><div class="flex items-end gap-3"><label class="field-label w-48">解析方式<select v-model="retryProfile" class="field-input"><option value="AUTO">自动选择</option><option value="LIGHTWEIGHT">轻量解析</option><option value="DOCLING">版面解析</option></select></label><label class="mb-2 flex h-10 items-center gap-2 text-sm text-ink-700"><input v-model="forceOcr" type="checkbox" class="size-4 accent-brand-600" />强制 OCR</label><button type="button" class="button-secondary mb-1 ml-auto" :disabled="retryMutation.isPending.value" @click="retryMutation.mutate()"><RotateCcw :size="15" aria-hidden="true" />按此方式重新处理</button></div></div>
        <p v-if="processingError || ingestionQuery.data.value?.errorMessage" class="mt-4 bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ processingError || ingestionQuery.data.value?.errorMessage }}</p>
      </section>

      <section v-else class="pt-6 max-w-5xl">
        <div class="flex items-center justify-between border-b border-paper-200 pb-4"><div class="flex items-center gap-2"><GitCompare :size="17" class="text-ink-400" aria-hidden="true" /><h2 class="text-base font-semibold">版本历史</h2></div><button v-if="auth.canEdit" type="button" class="button-secondary" @click="uploadOpen = true"><Upload :size="15" aria-hidden="true" />上传新版本</button></div>
        <div class="divide-y divide-paper-200 border-b border-paper-200"><button v-for="version in detailQuery.data.value.versions" :key="version.id" type="button" class="grid w-full grid-cols-[80px_1fr_140px_160px] items-center gap-4 py-4 text-left hover:bg-paper-50" @click="selectedVersionId = version.id"><span class="text-sm font-semibold text-ink-950">v{{ version.versionNumber }}</span><span class="truncate text-sm text-ink-700">{{ version.sourceName }}</span><StatusPill :status="version.status" /><span class="text-right text-xs text-ink-500">{{ formatDate(version.createdAt) }}</span></button></div>
        <div v-if="detailQuery.data.value.versions.length > 1" class="mt-8"><label class="flex items-center gap-3 text-sm font-medium text-ink-800">对比当前版本<select v-model="compareVersionId" class="control h-9 w-56 py-1.5 text-xs"><option v-for="version in detailQuery.data.value.versions.filter((item) => item.id !== selectedVersionId)" :key="version.id" :value="version.id">v{{ version.versionNumber }} · {{ version.status }}</option></select></label><div v-if="diffQuery.data.value" class="mt-4 grid grid-cols-4 divide-x border-y border-paper-200"><div class="p-4 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.addedBlocks }}</p><p class="mt-1 text-xs text-brand-700">新增</p></div><div class="p-4 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.modifiedBlocks }}</p><p class="mt-1 text-xs text-amber-700">修改</p></div><div class="p-4 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.removedBlocks }}</p><p class="mt-1 text-xs text-coral-700">删除</p></div><div class="p-4 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.unchangedBlocks }}</p><p class="mt-1 text-xs text-ink-400">未变化</p></div></div></div>
      </section>

      <p v-if="statusError" class="mt-5 bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ statusError }}</p>
    </template>
    <UploadDialog :open="uploadOpen" :knowledge-base-id="String(route.params.id)" :document-id="documentId" @close="uploadOpen = false" @uploaded="detailQuery.refetch" />
  </div>
</template>

<style scoped>
.normalized-markdown { color: #25334a; font-size: 15px; line-height: 1.9; }
.normalized-markdown :deep(h1) { margin: 0 0 30px; color: #0d172a; font-size: 30px; font-weight: 700; line-height: 1.3; }
.normalized-markdown :deep(h2) { margin: 46px 0 18px; border-bottom: 1px solid #e3e9f1; padding-bottom: 10px; color: #111d32; font-size: 22px; font-weight: 650; line-height: 1.4; }
.normalized-markdown :deep(h3) { margin: 34px 0 14px; color: #15233a; font-size: 18px; font-weight: 650; }
.normalized-markdown :deep(h4), .normalized-markdown :deep(h5), .normalized-markdown :deep(h6) { margin: 28px 0 12px; color: #1d2b42; font-size: 16px; font-weight: 650; }
.normalized-markdown :deep(p) { margin: 0 0 18px; }
.normalized-markdown :deep(ul), .normalized-markdown :deep(ol) { margin: 0 0 20px; padding-left: 26px; }
.normalized-markdown :deep(li) { margin: 5px 0; }
.normalized-markdown :deep(table) { margin: 24px 0; width: 100%; border-collapse: collapse; font-size: 14px; }
.normalized-markdown :deep(th), .normalized-markdown :deep(td) { border: 1px solid #dfe6ef; padding: 10px 12px; text-align: left; vertical-align: top; }
.normalized-markdown :deep(th) { background: #f6f8fb; color: #17243a; font-weight: 650; }
.normalized-markdown :deep(pre) { margin: 24px 0; overflow: auto; border: 1px solid #dfe6ef; background: #f7f9fc; padding: 18px; font-size: 13px; line-height: 1.65; }
.normalized-markdown :deep(code) { border-radius: 3px; background: #edf1f6; padding: 2px 5px; color: #20324e; font-size: 0.9em; }
.normalized-markdown :deep(pre code) { background: transparent; padding: 0; }
.normalized-markdown :deep(blockquote) { margin: 22px 0; border-left: 3px solid #2d68e8; padding: 4px 0 4px 18px; color: #52627a; }
.chunk-markdown { min-width: 0; overflow-wrap: anywhere; color: #25334a; font-size: 15px; line-height: 1.8; }
.chunk-markdown :deep(p) { margin: 0 0 12px; }
.chunk-markdown :deep(p:last-child) { margin-bottom: 0; }
.chunk-markdown :deep(h1), .chunk-markdown :deep(h2), .chunk-markdown :deep(h3), .chunk-markdown :deep(h4), .chunk-markdown :deep(h5), .chunk-markdown :deep(h6) { margin: 16px 0 8px; color: #142139; font-size: 15px; font-weight: 650; line-height: 1.55; }
.chunk-markdown :deep(h1:first-child), .chunk-markdown :deep(h2:first-child), .chunk-markdown :deep(h3:first-child) { margin-top: 0; }
.chunk-markdown :deep(ul), .chunk-markdown :deep(ol) { margin: 8px 0 12px; padding-left: 22px; }
.chunk-markdown :deep(li) { margin: 3px 0; }
.chunk-markdown :deep(pre) { margin: 12px 0; overflow: auto; border: 1px solid #dce4ed; background: #f6f8fb; padding: 14px 16px; color: #17243a; font-size: 12.5px; line-height: 1.65; }
.chunk-markdown :deep(code) { border-radius: 3px; background: #edf1f6; padding: 2px 4px; font-size: 0.9em; }
.chunk-markdown :deep(pre code) { background: transparent; padding: 0; }
.chunk-markdown :deep(table) { display: block; margin: 12px 0; max-width: 100%; overflow-x: auto; border-collapse: collapse; font-size: 13px; }
.chunk-markdown :deep(th), .chunk-markdown :deep(td) { min-width: 120px; border: 1px solid #dce4ed; padding: 8px 10px; text-align: left; vertical-align: top; }
.chunk-markdown :deep(th) { background: #f3f6f9; font-weight: 650; }
.chunk-markdown :deep(blockquote) { margin: 12px 0; border-left: 3px solid #2d68e8; background: #f4f7fd; padding: 10px 14px; color: #43546d; }
.parent-markdown { color: #3f5068; font-size: 14px; line-height: 1.75; }
.chunk-row { transition: background-color 160ms ease; }
@media (prefers-reduced-motion: reduce) { .chunk-row, .chunk-row * { transition: none !important; } }
</style>
