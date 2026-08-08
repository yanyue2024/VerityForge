<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronDown,
  ChevronRight,
  CircleAlert,
  Columns2,
  Download,
  Expand,
  FileSearch,
  FileText,
  Maximize2,
  Minimize2,
  MoreHorizontal,
  Pencil,
  Power,
  RotateCcw,
  Save,
  Search,
  Upload,
  X,
} from 'lucide-vue-next'
import DocumentMarkdown from '@/components/DocumentMarkdown.vue'
import DocumentOriginalPreview from '@/components/DocumentOriginalPreview.vue'
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
} from '@/types/api'

export type DocumentWorkspaceTab = 'original' | 'content' | 'chunks' | 'metadata' | 'processing'

const props = withDefaults(defineProps<{
  open: boolean
  documentId: string | null
  orderedDocumentIds: string[]
  activeTab?: DocumentWorkspaceTab
  initialChunkId?: string | null
  initialPageNumber?: number | null
  initialSourceStart?: number | null
  initialSourceEnd?: number | null
}>(), {
  activeTab: 'original',
  initialChunkId: null,
  initialPageNumber: null,
  initialSourceStart: null,
  initialSourceEnd: null,
})

const emit = defineEmits<{
  close: []
  navigate: [documentId: string]
  changeView: [tab: DocumentWorkspaceTab, chunkId?: string | null]
  updated: []
}>()

interface QualityIssue { code: string; severity: string; message: string; blockIds?: string[] }
interface QualityReport { status: string; score: number; issues: QualityIssue[]; metrics: Record<string, unknown> }

const queryClient = useQueryClient()
const auth = useAuthStore()
const panel = ref<HTMLElement | null>(null)
const fullscreen = ref(false)
const uploadOpen = ref(false)
const menuOpen = ref(false)
const outlineOpen = ref(true)
const compareOpen = ref(false)
const contentSearch = ref('')
const metadataEditing = ref(false)
const metadataValues = ref<Record<string, string>>({})
const validFrom = ref('')
const validTo = ref('')
const metadataError = ref('')
const processingError = ref('')
const expandedChunkIds = ref<string[]>([])
const expandedStage = ref('')
const chunkPage = ref(1)
const retryProfile = ref<'AUTO' | 'LIGHTWEIGHT' | 'DOCLING'>('AUTO')
const forceOcr = ref(false)
const sourcePage = ref<number | null>(null)
const chunkPageSize = 20

const tabs: Array<{ value: DocumentWorkspaceTab; label: string }> = [
  { value: 'original', label: '原文预览' },
  { value: 'content', label: '解析正文' },
  { value: 'chunks', label: '检索分块' },
  { value: 'metadata', label: 'Metadata' },
  { value: 'processing', label: '处理过程' },
]

const detailQuery = useQuery(computed(() => ({
  queryKey: ['document', props.documentId],
  queryFn: () => api.get<DocumentDetail>(`/api/v1/documents/${props.documentId}`),
  enabled: props.open && Boolean(props.documentId),
})))

const selectedVersion = computed<DocumentVersion | undefined>(() => {
  const detail = detailQuery.data.value
  return detail?.versions.find((version) => version.id === detail.currentVersionId) ?? detail?.versions[0]
})
const selectedVersionId = computed(() => selectedVersion.value?.id ?? '')

const assetQuery = useQuery(computed(() => ({
  queryKey: ['document-asset', selectedVersionId.value],
  queryFn: () => api.get<DocumentAsset>(`/api/v1/document-versions/${selectedVersionId.value}/asset`),
  enabled: props.open && Boolean(selectedVersionId.value),
  staleTime: 8 * 60 * 1_000,
})))
const contentQuery = useQuery(computed(() => ({
  queryKey: ['document-content', selectedVersionId.value],
  queryFn: () => api.get<DocumentContent>(`/api/v1/document-versions/${selectedVersionId.value}/content`),
  enabled: props.open && Boolean(selectedVersionId.value)
    && (props.activeTab === 'content' || compareOpen.value || props.activeTab === 'chunks'),
})))
const chunksQuery = useQuery(computed(() => ({
  queryKey: ['chunks', selectedVersionId.value],
  queryFn: () => api.get<ChunkRow[]>(`/api/v1/document-versions/${selectedVersionId.value}/chunks`),
  enabled: props.open && Boolean(selectedVersionId.value) && (props.activeTab === 'chunks' || Boolean(props.initialChunkId)),
})))
const schemaQuery = useQuery(computed(() => ({
  queryKey: ['metadata-schema', 'organization'],
  queryFn: () => api.get<MetadataSchema>('/api/v1/metadata-schema'),
  enabled: props.open && (props.activeTab === 'metadata' || metadataEditing.value),
})))
const revisionsQuery = useQuery(computed(() => ({
  queryKey: ['document-metadata-revisions', selectedVersionId.value],
  queryFn: () => api.get<DocumentMetadataHistory[]>(`/api/v1/document-versions/${selectedVersionId.value}/metadata-revisions`),
  enabled: props.open && Boolean(selectedVersionId.value) && props.activeTab === 'metadata',
})))
const ingestionQuery = useQuery(computed(() => ({
  queryKey: ['ingestion-job', selectedVersion.value?.ingestionJobId],
  queryFn: () => api.get<IngestionJob>(`/api/v1/ingestion-jobs/${selectedVersion.value?.ingestionJobId}`),
  enabled: props.open && Boolean(selectedVersion.value?.ingestionJobId) && props.activeTab === 'processing',
  refetchInterval: (query: { state: { data?: IngestionJob } }) =>
    ['SUCCEEDED', 'FAILED', 'CANCELLED', 'AWAITING_REVIEW'].includes(query.state.data?.status ?? '') ? false : 2_500,
})))

const currentIndex = computed(() => props.documentId ? props.orderedDocumentIds.indexOf(props.documentId) : -1)
const previousId = computed(() => currentIndex.value > 0 ? props.orderedDocumentIds[currentIndex.value - 1] : '')
const nextId = computed(() => currentIndex.value >= 0 && currentIndex.value < props.orderedDocumentIds.length - 1
  ? props.orderedDocumentIds[currentIndex.value + 1]
  : '')

const parentChunks = computed(() => new Map(
  (chunksQuery.data.value ?? []).filter((chunk) => chunk.type === 'PARENT').map((chunk) => [chunk.id, chunk]),
))
const childChunks = computed(() => (chunksQuery.data.value ?? []).filter((chunk) => chunk.type === 'CHILD'))
const chunkPageCount = computed(() => Math.max(1, Math.ceil(childChunks.value.length / chunkPageSize)))
const visibleChildChunks = computed(() => {
  const start = (chunkPage.value - 1) * chunkPageSize
  return childChunks.value.slice(start, start + chunkPageSize)
})
function safeMarkdown(markdown: string | null | undefined) {
  if (!markdown?.trim()) return ''
  const html = marked.parse(markdown, { gfm: true, breaks: false, async: false }) as string
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}

const renderedContent = computed(() => {
  const html = safeMarkdown(contentQuery.data.value?.normalizedMarkdown)
  if (!html) return { html: '', headings: [] as Array<{ id: string; label: string; level: number }>, matches: 0 }
  const documentNode = new DOMParser().parseFromString(html, 'text/html')
  const headings = Array.from(documentNode.body.querySelectorAll('h1, h2, h3')).map((heading, index) => {
    const id = `document-heading-${index}`
    heading.id = id
    return { id, label: heading.textContent?.trim() || `章节 ${index + 1}`, level: Number(heading.tagName.slice(1)) }
  })
  const term = contentSearch.value.trim()
  let matches = 0
  if (term) {
    const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const nodes: Text[] = []
    const walker = documentNode.createTreeWalker(documentNode.body, NodeFilter.SHOW_TEXT)
    while (walker.nextNode()) {
      const node = walker.currentNode as Text
      if (!node.parentElement?.closest('script, style, mark') && node.data.toLowerCase().includes(term.toLowerCase())) nodes.push(node)
    }
    nodes.forEach((node) => {
      const expression = new RegExp(escaped, 'gi')
      const fragment = document.createDocumentFragment()
      let cursor = 0
      node.data.replace(expression, (match, offset: number) => {
        fragment.append(node.data.slice(cursor, offset))
        const mark = document.createElement('mark')
        mark.className = 'document-search-hit'
        mark.textContent = match
        fragment.append(mark)
        cursor = offset + match.length
        matches += 1
        return match
      })
      fragment.append(node.data.slice(cursor))
      node.parentNode?.replaceChild(fragment, node)
    })
  }
  return { html: DOMPurify.sanitize(documentNode.body.innerHTML), headings, matches }
})

const qualityReport = computed<QualityReport | null>(() => {
  const raw = contentQuery.data.value?.parseQualityReport || selectedVersion.value?.parseQualityReport
  if (!raw) return null
  try { return JSON.parse(raw) as QualityReport } catch { return null }
})

const parsedMetadata = computed<Record<string, unknown>>(() => {
  if (!selectedVersion.value?.metadata) return {}
  try { return JSON.parse(selectedVersion.value.metadata) as Record<string, unknown> } catch { return {} }
})

const metadataRows = computed(() => {
  const definitions = schemaQuery.data.value?.fields ?? []
  const known = new Set(definitions.map((field) => field.key))
  return [
    ...definitions.map((field) => ({ key: field.key, label: field.label, type: field.type, value: parsedMetadata.value[field.key], managed: true })),
    ...Object.entries(parsedMetadata.value)
      .filter(([key]) => !known.has(key))
      .map(([key, value]) => ({ key, label: key, type: 'TEXT', value, managed: false })),
  ]
})

const metadataMutation = useMutation({
  mutationFn: () => api.patch(`/api/v1/document-versions/${selectedVersionId.value}/metadata`, {
    metadata: buildMetadata(),
    validFrom: validFrom.value ? new Date(validFrom.value).toISOString() : null,
    validTo: validTo.value ? new Date(validTo.value).toISOString() : null,
  }),
  onSuccess: async () => {
    metadataEditing.value = false
    metadataError.value = ''
    await Promise.all([
      detailQuery.refetch(),
      revisionsQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: ['documents'] }),
    ])
    emit('updated')
  },
  onError: (error) => { metadataError.value = readableError(error) },
})

const statusMutation = useMutation({
  mutationFn: () => api.patch(`/api/v1/documents/${props.documentId}/status`, {
    status: detailQuery.data.value?.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
  }),
  onSuccess: async () => {
    menuOpen.value = false
    await Promise.all([detailQuery.refetch(), queryClient.invalidateQueries({ queryKey: ['documents'] })])
    emit('updated')
  },
})

const retryMutation = useMutation({
  mutationFn: () => api.post(`/api/v1/ingestion-jobs/${selectedVersion.value?.ingestionJobId}/retry-with-parser`, {
    parserProfile: retryProfile.value,
    options: { forceOcr: forceOcr.value },
  }),
  onSuccess: async () => {
    processingError.value = ''
    await Promise.all([detailQuery.refetch(), ingestionQuery.refetch()])
    emit('updated')
  },
  onError: (error) => { processingError.value = readableError(error) },
})

const approveMutation = useMutation({
  mutationFn: () => api.post(`/api/v1/ingestion-jobs/${selectedVersion.value?.ingestionJobId}/approve-quality`),
  onSuccess: async () => {
    processingError.value = ''
    await Promise.all([detailQuery.refetch(), ingestionQuery.refetch()])
    emit('updated')
  },
  onError: (error) => { processingError.value = readableError(error) },
})

function toInputValue(value: unknown, type?: string) {
  if (Array.isArray(value)) return value.join(', ')
  if (value == null) return ''
  if (type === 'DATETIME' && typeof value === 'string') return value.slice(0, 16)
  return String(value)
}

function startMetadataEdit() {
  metadataValues.value = Object.fromEntries(
    (schemaQuery.data.value?.fields ?? []).map((field) => [field.key, toInputValue(parsedMetadata.value[field.key], field.type)]),
  )
  validFrom.value = selectedVersion.value?.validFrom?.slice(0, 16) ?? ''
  validTo.value = selectedVersion.value?.validTo?.slice(0, 16) ?? ''
  metadataError.value = ''
  metadataEditing.value = true
}

function buildMetadata() {
  const result: Record<string, unknown> = { ...parsedMetadata.value }
  for (const field of schemaQuery.data.value?.fields ?? []) {
    const raw = metadataValues.value[field.key]?.trim() ?? ''
    if (!raw) {
      if (field.required) throw new Error(`${field.label} 为必填字段`)
      delete result[field.key]
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

function displayValue(value: unknown) {
  if (value == null || value === '') return '未填写'
  if (Array.isArray(value)) return value.join('、') || '未填写'
  if (typeof value === 'boolean') return value ? '是' : '否'
  return String(value)
}

function stageLabel(stage: string) {
  return ({ PARSE: '文档解析', NORMALIZE: '正文规范化', QUALITY: '质量检查', CHUNK: '父子分块',
    EMBED: '生成向量', PUBLISH: '发布版本' } as Record<string, string>)[stage] || stage
}

function duration(start: string | null, end: string | null) {
  if (!start) return '—'
  const milliseconds = (end ? new Date(end).getTime() : Date.now()) - new Date(start).getTime()
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '—'
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(1)} s`
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
  if (stage.stage === 'PUBLISH' && stage.status === 'SUCCEEDED') return '当前版本已发布并可用于检索'
  return stage.status === 'PENDING' ? '等待前序阶段' : stage.status === 'RUNNING' ? '正在处理' : '阶段已完成'
}

function metricLabel(key: string) {
  return ({
    blocks: '结构块',
    parser: '解析器',
    score: '质量得分',
    issues: '质量提示',
    children: '子块',
    parents: '父块',
    created: '新建向量',
    reused: '复用向量',
    skipped: '跳过',
    durationMs: '阶段耗时',
  } as Record<string, string>)[key] || key
}

function parentParts(chunk: ChunkRow) {
  const parent = chunk.parentChunkId ? parentChunks.value.get(chunk.parentChunkId) : undefined
  if (!parent) return null
  const childText = chunk.text.trim()
  const offset = parent.text.indexOf(childText)
  if (offset < 0) return { before: '', match: '', after: parent.text, matched: false }
  return {
    before: parent.text.slice(0, offset),
    match: parent.text.slice(offset, offset + childText.length),
    after: parent.text.slice(offset + childText.length),
    matched: true,
  }
}

function isChunkExpanded(chunkId: string) {
  return expandedChunkIds.value.includes(chunkId)
}

function setTab(tab: DocumentWorkspaceTab, chunkId?: string | null) {
  compareOpen.value = false
  emit('changeView', tab, chunkId)
}

async function toggleChunk(chunk: ChunkRow) {
  const next = new Set(expandedChunkIds.value)
  const opening = !next.has(chunk.id)
  if (opening) next.add(chunk.id)
  else next.delete(chunk.id)
  expandedChunkIds.value = Array.from(next)
  if (opening) {
    sourcePage.value = Number.parseInt(chunk.sourceLocation.match(/(?:page|页)[^0-9]*(\d+)/i)?.[1] ?? '', 10) || null
    await nextTick()
  }
}

function locateHeading(id: string) {
  panel.value?.querySelector(`#${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function locateChunkInContent(chunk: ChunkRow) {
  if (!isChunkExpanded(chunk.id)) expandedChunkIds.value = [...expandedChunkIds.value, chunk.id]
  emit('changeView', 'content', chunk.id)
}

function locateChunkInOriginal(chunk: ChunkRow) {
  sourcePage.value = Number.parseInt(chunk.sourceLocation.match(/(?:page|页)[^0-9]*(\d+)/i)?.[1] ?? '', 10) || null
  emit('changeView', 'original', chunk.id)
}

function close() {
  if (metadataEditing.value && !window.confirm('Metadata 尚未保存，确定关闭吗？')) return
  emit('close')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && props.open && !uploadOpen.value) close()
}

let previousOverflow = ''
watch(() => props.open, (open) => {
  if (open) {
    previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    void nextTick(() => panel.value?.focus())
  } else {
    document.body.style.overflow = previousOverflow
    fullscreen.value = false
    compareOpen.value = false
    metadataEditing.value = false
  }
}, { immediate: true })
watch(() => props.documentId, () => {
  menuOpen.value = false
  contentSearch.value = ''
  chunkPage.value = 1
  expandedChunkIds.value = props.initialChunkId ? [props.initialChunkId] : []
  expandedStage.value = ''
  sourcePage.value = props.initialPageNumber ?? null
})
watch(() => props.initialPageNumber, (page) => { sourcePage.value = page ?? null })
watch(() => props.initialChunkId, (chunkId) => {
  if (chunkId && !isChunkExpanded(chunkId)) expandedChunkIds.value = [...expandedChunkIds.value, chunkId]
})
watch(() => chunksQuery.data.value, async (chunks) => {
  const chunkId = props.initialChunkId
  if (!chunkId || !chunks?.length) return
  const index = childChunks.value.findIndex((chunk) => chunk.id === chunkId)
  if (index >= 0) {
    chunkPage.value = Math.floor(index / chunkPageSize) + 1
    const chunk = childChunks.value[index]
    sourcePage.value = Number.parseInt(chunk.sourceLocation.match(/(?:page|页)[^0-9]*(\d+)/i)?.[1] ?? '', 10) || null
  }
  await nextTick()
  panel.value?.querySelector(`#workspace-chunk-${chunkId}`)?.scrollIntoView({ block: 'center' })
})

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = previousOverflow
})
</script>

<template>
  <Teleport to="body">
    <Transition name="document-workspace">
      <div v-if="open" class="document-workspace-layer" role="presentation" @mousedown.self="close">
        <section
          ref="panel"
          class="document-workspace"
          :class="{ 'is-fullscreen': fullscreen }"
          role="dialog"
          aria-modal="true"
          aria-label="文档工作区"
          tabindex="-1"
        >
          <header class="workspace-header">
            <div class="min-w-0 flex-1">
              <div class="flex min-w-0 items-center gap-2.5">
                <FileText :size="18" class="shrink-0 text-brand-700" aria-hidden="true" />
                <h2 class="truncate text-base font-semibold text-ink-950">
                  {{ detailQuery.data.value?.title || '正在载入文档' }}
                </h2>
                <StatusPill v-if="selectedVersion" :status="selectedVersion.status" />
              </div>
              <p class="mt-1.5 truncate pl-7 text-xs text-ink-400">
                {{ assetQuery.data.value?.fileName || selectedVersion?.sourceName || '—' }}
                <template v-if="assetQuery.data.value"> · {{ formatBytes(assetQuery.data.value.byteSize) }}</template>
                <template v-if="selectedVersion"> · v{{ selectedVersion.versionNumber }}</template>
              </p>
            </div>

            <div class="flex shrink-0 items-center gap-1">
              <button type="button" class="icon-button size-9" title="上一份文档" :disabled="!previousId" @click="previousId && emit('navigate', previousId)">
                <ArrowLeft :size="17" aria-hidden="true" />
              </button>
              <span class="min-w-12 text-center text-[11px] tabular-nums text-ink-400">
                {{ currentIndex >= 0 ? currentIndex + 1 : '—' }} / {{ orderedDocumentIds.length }}
              </span>
              <button type="button" class="icon-button size-9" title="下一份文档" :disabled="!nextId" @click="nextId && emit('navigate', nextId)">
                <ArrowRight :size="17" aria-hidden="true" />
              </button>
              <span class="mx-1 h-5 w-px bg-paper-200" />
              <a v-if="assetQuery.data.value" :href="assetQuery.data.value.previewUrl" class="icon-button size-9" title="下载原文件">
                <Download :size="17" aria-hidden="true" />
              </a>
              <button type="button" class="icon-button size-9" :title="fullscreen ? '退出全屏' : '全屏查看'" @click="fullscreen = !fullscreen">
                <Minimize2 v-if="fullscreen" :size="17" aria-hidden="true" />
                <Maximize2 v-else :size="17" aria-hidden="true" />
              </button>
              <div v-if="auth.canEdit" class="relative">
                <button type="button" class="icon-button size-9" title="更多操作" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">
                  <MoreHorizontal :size="18" aria-hidden="true" />
                </button>
                <div v-if="menuOpen" class="workspace-menu">
                  <button type="button" @click="uploadOpen = true; menuOpen = false"><Upload :size="15" />上传新版本</button>
                  <button type="button" :disabled="statusMutation.isPending.value" @click="statusMutation.mutate()">
                    <Power :size="15" />{{ detailQuery.data.value?.status === 'ACTIVE' ? '暂停检索' : '恢复检索' }}
                  </button>
                </div>
              </div>
              <button type="button" class="icon-button size-9" title="关闭文档" @click="close">
                <X :size="19" aria-hidden="true" />
              </button>
            </div>
          </header>

          <nav class="workspace-tabs" aria-label="文档视图">
            <button
              v-for="tab in tabs"
              :key="tab.value"
              type="button"
              :class="{ active: activeTab === tab.value }"
              @click="setTab(tab.value)"
            >
              {{ tab.label }}
            </button>
          </nav>

          <div v-if="detailQuery.isPending.value" class="flex min-h-0 flex-1 flex-col gap-4 p-8">
            <div v-for="index in 7" :key="index" class="h-14 animate-pulse rounded-md bg-paper-100" />
          </div>
          <ErrorState v-else-if="detailQuery.isError.value" class="m-8" :message="readableError(detailQuery.error.value)" @retry="detailQuery.refetch()" />

          <template v-else>
            <section v-if="activeTab === 'original'" class="workspace-view workspace-original">
              <div v-if="initialChunkId" class="source-notice">
                <FileSearch :size="15" aria-hidden="true" />
                已从引用位置打开原文<template v-if="sourcePage"> · 第 {{ sourcePage }} 页</template>
                <button type="button" @click="setTab('chunks', initialChunkId)">查看对应分块</button>
              </div>
              <DocumentOriginalPreview
                :asset="assetQuery.data.value"
                :loading="assetQuery.isPending.value"
                :error="assetQuery.isError.value ? readableError(assetQuery.error.value) : ''"
                :page-number="sourcePage"
              />
            </section>

            <section v-else-if="activeTab === 'content'" class="workspace-view">
              <div class="content-toolbar">
                <button type="button" class="button-secondary min-h-9 px-3 text-xs" @click="outlineOpen = !outlineOpen">
                  <Columns2 :size="15" aria-hidden="true" />{{ outlineOpen ? '收起目录' : '显示目录' }}
                </button>
                <div class="relative w-64">
                  <Search :size="15" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400" aria-hidden="true" />
                  <input v-model="contentSearch" class="control h-9 pl-9 text-xs" placeholder="在解析正文中查找" />
                </div>
                <span v-if="contentSearch" class="text-xs text-ink-400">{{ renderedContent.matches }} 处</span>
                <button type="button" class="button-secondary ml-auto min-h-9 px-3 text-xs" @click="compareOpen = !compareOpen; fullscreen = compareOpen || fullscreen">
                  <Expand :size="15" aria-hidden="true" />{{ compareOpen ? '退出对照' : '与原文对照' }}
                </button>
              </div>
              <div class="min-h-0 flex-1" :class="compareOpen ? 'grid grid-cols-2' : 'flex'">
                <DocumentOriginalPreview
                  v-if="compareOpen"
                  :asset="assetQuery.data.value"
                  :loading="assetQuery.isPending.value"
                  :error="assetQuery.isError.value ? readableError(assetQuery.error.value) : ''"
                />
                <aside v-if="outlineOpen && !compareOpen" class="content-outline">
                  <p class="mb-3 text-[11px] font-semibold uppercase text-ink-400">正文目录</p>
                  <button
                    v-for="heading in renderedContent.headings"
                    :key="heading.id"
                    type="button"
                    :style="{ paddingLeft: `${(heading.level - 1) * 12 + 8}px` }"
                    @click="locateHeading(heading.id)"
                  >{{ heading.label }}</button>
                  <p v-if="!renderedContent.headings.length" class="px-2 text-xs leading-5 text-ink-400">该文档没有可识别的标题层级。</p>
                </aside>
                <div class="content-canvas">
                  <div v-if="contentQuery.isPending.value" class="space-y-4 p-10"><div v-for="index in 8" :key="index" class="h-12 animate-pulse bg-paper-100" /></div>
                  <ErrorState v-else-if="contentQuery.isError.value" class="m-8" :message="readableError(contentQuery.error.value)" @retry="contentQuery.refetch()" />
                  <div v-else-if="!renderedContent.html" class="workspace-empty">规范化正文尚未生成。</div>
                  <article v-else class="document-prose" v-html="renderedContent.html" />
                </div>
              </div>
            </section>

            <section v-else-if="activeTab === 'chunks'" class="workspace-view workspace-scroll">
              <div class="view-heading sticky top-0 z-10 bg-white">
                <div><h3>检索分块</h3><p>子块用于召回，父块补足回答上下文；内容为只读解析工件。</p></div>
                <span>{{ childChunks.length }} 个子块 · {{ parentChunks.size }} 个父块 · 250 / 1000 Token</span>
              </div>
              <div
                v-if="initialChunkId && (initialPageNumber != null || initialSourceStart != null)"
                class="source-notice static mx-auto mt-4 w-fit translate-x-0"
              >
                <FileSearch :size="15" aria-hidden="true" />
                引用定位
                <template v-if="initialPageNumber != null"> · 第 {{ initialPageNumber }} 页</template>
                <template v-if="initialSourceStart != null">
                  · 规范化原文 {{ initialSourceStart }}–{{ initialSourceEnd ?? '?' }}
                </template>
              </div>
              <div v-if="chunksQuery.isPending.value" class="space-y-3 p-6"><div v-for="index in 6" :key="index" class="h-28 animate-pulse rounded-md bg-paper-100" /></div>
              <ErrorState v-else-if="chunksQuery.isError.value" class="m-6" :message="readableError(chunksQuery.error.value)" @retry="chunksQuery.refetch()" />
              <div v-else-if="!childChunks.length" class="workspace-empty">该版本还没有可检索分块。</div>
              <div v-else class="chunk-list">
                <article
                  v-for="chunk in visibleChildChunks"
                  :id="`workspace-chunk-${chunk.id}`"
                  :key="chunk.id"
                  class="chunk-item"
                  :class="{ expanded: isChunkExpanded(chunk.id) }"
                >
                  <div class="chunk-summary">
                    <span class="source-track"><i /></span>
                    <div class="chunk-body">
                      <div class="chunk-heading-row">
                        <strong>{{ chunk.contextHeader || '未命名章节' }}</strong>
                        <span class="chunk-kind">子块</span>
                      </div>
                      <DocumentMarkdown class="chunk-content" :markdown="chunk.renderedMarkdown || chunk.text" />
                      <div class="chunk-footer">
                        <small>{{ chunk.estimatedTokens }} tokens · {{ chunk.sourceLocation || '来源位置未知' }}</small>
                        <button
                          v-if="chunk.parentChunkId && parentChunks.has(chunk.parentChunkId)"
                          type="button"
                          class="parent-toggle"
                          :aria-expanded="isChunkExpanded(chunk.id)"
                          :aria-controls="`workspace-parent-${chunk.id}`"
                          @click="toggleChunk(chunk)"
                        >
                          {{ isChunkExpanded(chunk.id) ? '收起父块上下文' : '查看父块上下文' }}
                          <ChevronDown :size="15" :class="{ 'rotate-180': isChunkExpanded(chunk.id) }" aria-hidden="true" />
                        </button>
                      </div>
                    </div>
                  </div>
                  <div v-if="isChunkExpanded(chunk.id)" :id="`workspace-parent-${chunk.id}`" class="chunk-detail">
                    <div class="flex items-center justify-between gap-4 border-b border-evidence-200 pb-3">
                      <div>
                        <p class="text-xs font-semibold text-evidence-800">父块上下文</p>
                        <p class="mt-1 text-[11px] text-evidence-700">{{ parentParts(chunk)?.matched ? '当前子块在完整上下文中高亮' : '完整父块上下文' }}</p>
                      </div>
                      <div class="flex gap-2">
                        <button type="button" class="text-xs font-semibold text-brand-700" @click="locateChunkInContent(chunk)">定位解析正文</button>
                        <button type="button" class="text-xs font-semibold text-brand-700" @click="locateChunkInOriginal(chunk)">定位原文</button>
                      </div>
                    </div>
                    <div v-if="parentParts(chunk)" class="parent-context-flow">
                      <DocumentMarkdown v-if="parentParts(chunk)?.before" :markdown="parentParts(chunk)?.before" />
                      <div v-if="parentParts(chunk)?.match" class="current-chunk-context">
                        <DocumentMarkdown :markdown="parentParts(chunk)?.match" />
                      </div>
                      <DocumentMarkdown v-if="parentParts(chunk)?.after" :markdown="parentParts(chunk)?.after" />
                    </div>
                  </div>
                </article>
              </div>
              <div v-if="chunkPageCount > 1" class="workspace-pagination">
                <button type="button" class="icon-button size-8" :disabled="chunkPage === 1" @click="chunkPage -= 1"><ArrowLeft :size="15" /></button>
                <span>第 {{ chunkPage }} / {{ chunkPageCount }} 页</span>
                <button type="button" class="icon-button size-8" :disabled="chunkPage === chunkPageCount" @click="chunkPage += 1"><ArrowRight :size="15" /></button>
              </div>
            </section>

            <section v-else-if="activeTab === 'metadata'" class="workspace-view workspace-scroll">
              <div class="view-heading sticky top-0 z-10 bg-white">
                <div><h3>文档 Metadata</h3><p>这些值在上传时随文档写入，可由管理员后续修正。</p></div>
                <div class="flex gap-2">
                  <button v-if="metadataEditing" type="button" class="button-secondary min-h-9 px-3 text-xs" @click="metadataEditing = false">取消</button>
                  <button v-if="auth.canEdit" type="button" class="button-primary min-h-9 px-3 text-xs" :disabled="metadataMutation.isPending.value" @click="metadataEditing ? metadataMutation.mutate() : startMetadataEdit()">
                    <Save v-if="metadataEditing" :size="15" /><Pencil v-else :size="15" />{{ metadataEditing ? '保存修改' : '编辑字段值' }}
                  </button>
                </div>
              </div>
              <div class="metadata-layout">
                <div class="metadata-section">
                  <h4>字段值</h4>
                  <div v-if="schemaQuery.isPending.value" class="mt-4 h-36 animate-pulse rounded-md bg-paper-100" />
                  <dl v-else class="metadata-grid">
                    <div v-for="row in metadataRows" :key="row.key">
                      <dt>{{ row.label }}<code>{{ row.key }}</code></dt>
                      <dd v-if="!metadataEditing || !row.managed" :class="{ muted: row.value == null || row.value === '' }">{{ displayValue(row.value) }}</dd>
                      <dd v-else>
                        <select v-if="row.type === 'BOOLEAN'" v-model="metadataValues[row.key]" class="control h-9 py-1.5 text-sm">
                          <option value="">未填写</option><option value="true">是</option><option value="false">否</option>
                        </select>
                        <select v-else-if="row.type !== 'TEXT_LIST' && schemaQuery.data.value?.fields.find((field) => field.key === row.key)?.allowedValues.length" v-model="metadataValues[row.key]" class="control h-9 py-1.5 text-sm">
                          <option value="">未填写</option>
                          <option v-for="value in schemaQuery.data.value?.fields.find((field) => field.key === row.key)?.allowedValues" :key="value" :value="value">{{ value }}</option>
                        </select>
                        <input v-else v-model="metadataValues[row.key]" :type="row.type === 'NUMBER' ? 'number' : row.type === 'DATE' ? 'date' : row.type === 'DATETIME' ? 'datetime-local' : 'text'" class="control h-9 py-1.5 text-sm" :placeholder="row.type === 'TEXT_LIST' ? '多个值用逗号分隔' : ''" />
                      </dd>
                    </div>
                    <div><dt>生效时间</dt><dd v-if="!metadataEditing">{{ formatDate(selectedVersion?.validFrom) }}</dd><dd v-else><input v-model="validFrom" type="datetime-local" class="control h-9 py-1.5 text-sm" /></dd></div>
                    <div><dt>失效时间</dt><dd v-if="!metadataEditing">{{ formatDate(selectedVersion?.validTo) }}</dd><dd v-else><input v-model="validTo" type="datetime-local" class="control h-9 py-1.5 text-sm" /></dd></div>
                  </dl>
                  <p v-if="metadataError" class="mt-4 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ metadataError }}</p>
                </div>
                <div class="metadata-section">
                  <h4>修改记录</h4>
                  <p v-if="!revisionsQuery.data.value?.length" class="mt-4 text-sm text-ink-400">还没有字段修改记录。</p>
                  <div v-else class="revision-list">
                    <div v-for="revision in revisionsQuery.data.value" :key="revision.revisionId">
                      <span class="source-track"><i /></span>
                      <p>{{ formatDate(revision.createdAt) }}</p>
                      <small>{{ revision.changedBy || '管理员' }} · 字段修订</small>
                    </div>
                  </div>
                </div>
              </div>
            </section>

            <section v-else class="workspace-view workspace-scroll">
              <div class="view-heading sticky top-0 z-10 bg-white">
                <div><h3>处理过程</h3><p>从原文件解析到发布入库的完整链路。</p></div>
                <StatusPill :status="ingestionQuery.data.value?.status || selectedVersion?.ingestionStatus" />
              </div>
              <div v-if="selectedVersion?.parseQualityStatus && selectedVersion.parseQualityStatus !== 'PASS'" class="quality-notice" :class="selectedVersion.parseQualityStatus.toLowerCase()">
                <CircleAlert :size="18" aria-hidden="true" />
                <div><strong>{{ selectedVersion.parseQualityStatus === 'FAIL' ? '解析质量未通过' : '解析结果需要确认' }}</strong><p>{{ qualityReport?.issues?.[0]?.message || '建议检查解析正文和分块后再发布。' }}</p></div>
                <button v-if="selectedVersion.parseQualityStatus === 'WARNING' && ingestionQuery.data.value?.status === 'AWAITING_REVIEW'" type="button" class="button-primary ml-auto min-h-9 px-3 text-xs" :disabled="approveMutation.isPending.value" @click="approveMutation.mutate()"><Check :size="15" />确认并继续</button>
              </div>
              <div v-if="!selectedVersion?.ingestionJobId" class="workspace-empty">该版本还没有处理任务。</div>
              <div v-else-if="ingestionQuery.isPending.value" class="space-y-3 p-6"><div v-for="index in 6" :key="index" class="h-16 animate-pulse rounded-md bg-paper-100" /></div>
              <ErrorState v-else-if="ingestionQuery.isError.value" class="m-6" :message="readableError(ingestionQuery.error.value)" @retry="ingestionQuery.refetch()" />
              <div v-else class="process-list">
                <article v-for="(stage, index) in ingestionQuery.data.value?.stages" :key="stage.stage">
                  <span class="process-rail"><i :class="stage.status.toLowerCase()">{{ index + 1 }}</i></span>
                  <button type="button" class="process-summary" @click="expandedStage = expandedStage === stage.stage ? '' : stage.stage">
                    <span><strong>{{ stageLabel(stage.stage) }}</strong><small>{{ stageSummary(stage) }}</small></span>
                    <StatusPill :status="stage.status" />
                    <time>{{ duration(stage.startedAt, stage.completedAt) }}</time>
                    <ChevronRight :size="16" :class="{ 'rotate-90': expandedStage === stage.stage }" />
                  </button>
                  <div v-if="expandedStage === stage.stage" class="process-detail">
                    <p v-if="stage.errorMessage" class="text-coral-700">{{ stage.errorMessage }}</p>
                    <dl v-if="Object.keys(stageMetrics(stage)).length" class="process-metrics">
                      <div v-for="(value, key) in stageMetrics(stage)" :key="key">
                        <dt>{{ metricLabel(String(key)) }}</dt>
                        <dd>{{ Array.isArray(value) ? value.join('、') : String(value) }}</dd>
                      </div>
                    </dl>
                    <p v-else>该阶段没有更多可展示的数据。</p>
                  </div>
                </article>
              </div>
              <div v-if="['FAILED', 'AWAITING_REVIEW'].includes(ingestionQuery.data.value?.status ?? '')" class="retry-panel">
                <div><strong>重新处理</strong><p>默认自动选择解析方式；扫描件可在高级选项中启用 OCR。</p></div>
                <label>解析方式<select v-model="retryProfile" class="control h-9 py-1.5"><option value="AUTO">自动选择</option><option value="LIGHTWEIGHT">轻量解析</option><option value="DOCLING">版面解析</option></select></label>
                <label class="checkbox"><input v-model="forceOcr" type="checkbox" />强制 OCR</label>
                <button type="button" class="button-primary min-h-9 px-3 text-xs" :disabled="retryMutation.isPending.value" @click="retryMutation.mutate()"><RotateCcw :size="15" />重新处理</button>
              </div>
              <p v-if="processingError || ingestionQuery.data.value?.errorMessage" class="mx-6 mb-6 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ processingError || ingestionQuery.data.value?.errorMessage }}</p>
            </section>
          </template>

          <UploadDialog
            :open="uploadOpen"
            :knowledge-base-id="detailQuery.data.value?.knowledgeBaseId || ''"
            :document-id="documentId || undefined"
            @close="uploadOpen = false"
            @uploaded="detailQuery.refetch(); emit('updated')"
          />
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.document-workspace-layer { position: fixed; inset: 0; z-index: 70; background: rgba(15, 23, 42, 0.38); backdrop-filter: blur(1px); }
.document-workspace { position: absolute; inset-block: 0; right: 0; display: flex; width: min(72vw, 1360px); min-width: 860px; flex-direction: column; overflow: hidden; border-left: 1px solid #dce3ed; background: #fff; box-shadow: -20px 0 52px rgba(15, 23, 42, 0.14); outline: none; }
.document-workspace.is-fullscreen { width: 100vw; min-width: 0; border-left: 0; }
.workspace-header { display: flex; min-height: 76px; flex-shrink: 0; align-items: center; gap: 24px; border-bottom: 1px solid #e3e9f1; padding: 12px 18px 11px 24px; }
.workspace-tabs { display: flex; height: 48px; flex-shrink: 0; align-items: end; gap: 28px; border-bottom: 1px solid #e3e9f1; padding: 0 24px; }
.workspace-tabs button { position: relative; height: 48px; color: #728198; font-size: 13px; font-weight: 600; transition: color 150ms ease; }
.workspace-tabs button:hover, .workspace-tabs button.active { color: #111d32; }
.workspace-tabs button.active::after { position: absolute; inset-inline: 0; bottom: -1px; height: 2px; background: #2d68e8; content: ''; }
.workspace-menu { position: absolute; top: 42px; right: 0; z-index: 20; width: 172px; border: 1px solid #dce3ed; border-radius: 7px; background: #fff; padding: 5px; box-shadow: 0 14px 34px rgba(15, 23, 42, 0.12); }
.workspace-menu button { display: flex; width: 100%; align-items: center; gap: 9px; border-radius: 5px; padding: 9px 10px; color: #334155; font-size: 12px; text-align: left; }
.workspace-menu button:hover { background: #f3f6f9; color: #0d172a; }
.workspace-view { display: flex; min-height: 0; flex: 1; flex-direction: column; background: #fff; }
.workspace-scroll { overflow-y: auto; scrollbar-color: #c4cfdd transparent; scrollbar-width: thin; }
.workspace-original { position: relative; background: #edf1f6; }
.source-notice { position: absolute; top: 12px; left: 50%; z-index: 5; display: flex; transform: translateX(-50%); align-items: center; gap: 8px; border: 1px solid #bed1fa; border-radius: 6px; background: rgba(255, 255, 255, .96); padding: 8px 12px; color: #52627a; box-shadow: 0 8px 20px rgba(15, 23, 42, .08); font-size: 12px; }
.source-notice button { color: #225bd2; font-weight: 650; }
.content-toolbar { display: flex; height: 56px; flex-shrink: 0; align-items: center; gap: 10px; border-bottom: 1px solid #e3e9f1; padding: 0 20px; }
.content-outline { width: 232px; flex-shrink: 0; overflow-y: auto; border-right: 1px solid #e3e9f1; padding: 22px 14px; }
.content-outline button { display: block; width: 100%; overflow: hidden; border-radius: 5px; padding-block: 7px; color: #64748b; font-size: 12px; line-height: 1.45; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.content-outline button:hover { background: #f3f6f9; color: #1e293b; }
.content-canvas { min-width: 0; flex: 1; overflow-y: auto; background: #f6f8fb; padding: 30px; }
.document-prose { width: min(900px, 100%); min-height: 100%; margin: 0 auto; border: 1px solid #e0e7f0; background: #fff; padding: 54px 64px 72px; color: #25334a; font-size: 15px; line-height: 1.86; box-shadow: 0 9px 26px rgba(15, 23, 42, .05); }
.document-prose :deep(h1) { margin: 0 0 30px; color: #0d172a; font-size: 28px; font-weight: 700; line-height: 1.35; }
.document-prose :deep(h2) { margin: 42px 0 18px; border-bottom: 1px solid #e3e9f1; padding-bottom: 10px; color: #111d32; font-size: 21px; font-weight: 650; }
.document-prose :deep(h3) { margin: 30px 0 13px; color: #15233a; font-size: 17px; font-weight: 650; }
.document-prose :deep(p) { margin: 0 0 17px; }
.document-prose :deep(ul), .document-prose :deep(ol) { margin: 0 0 19px; padding-left: 25px; }
.document-prose :deep(table) { margin: 22px 0; width: 100%; border-collapse: collapse; font-size: 13px; }
.document-prose :deep(th), .document-prose :deep(td) { border: 1px solid #dfe6ef; padding: 9px 11px; text-align: left; vertical-align: top; }
.document-prose :deep(th) { background: #f6f8fb; color: #17243a; font-weight: 650; }
.document-prose :deep(pre) { margin: 22px 0; overflow: auto; border: 1px solid #dfe6ef; background: #f7f9fc; padding: 17px; font-size: 12.5px; line-height: 1.65; }
.document-prose :deep(code) { border-radius: 3px; background: #edf1f6; padding: 2px 5px; color: #20324e; font-size: .9em; }
.document-prose :deep(.document-search-hit) { background: #fff0a8; color: inherit; }
.view-heading { display: flex; min-height: 72px; flex-shrink: 0; align-items: center; justify-content: space-between; gap: 24px; border-bottom: 1px solid #e3e9f1; padding: 14px 24px; }
.view-heading h3 { color: #111d32; font-size: 15px; font-weight: 650; }
.view-heading p { margin-top: 4px; color: #7b899e; font-size: 12px; }
.view-heading > span { color: #728198; font-size: 11px; }
.workspace-empty { display: flex; min-height: 360px; align-items: center; justify-content: center; color: #728198; font-size: 13px; }
.chunk-list { padding: 18px 24px 6px; }
.chunk-item { margin-bottom: 10px; overflow: hidden; border: 1px solid #dfe6ef; border-radius: 7px; background: #fff; transition: border-color 150ms ease, box-shadow 150ms ease; scroll-margin: 84px; }
.chunk-item:hover { border-color: #bfccdc; }
.chunk-item.expanded { border-color: #a8c5ff; box-shadow: 0 7px 18px rgba(45, 104, 232, .07); }
.chunk-summary { display: flex; width: 100%; min-height: 114px; align-items: stretch; gap: 14px; padding: 17px 18px 16px 20px; color: #44546a; }
.source-track { position: relative; display: block; width: 9px; align-self: stretch; flex-shrink: 0; }
.source-track::before { position: absolute; top: 1px; bottom: 1px; left: 4px; width: 1px; background: #cfd9e6; content: ''; }
.source-track i { position: absolute; top: 5px; left: 1px; width: 7px; height: 7px; border: 2px solid #fff; border-radius: 999px; background: #2d68e8; box-shadow: 0 0 0 1px #2d68e8; }
.chunk-body { min-width: 0; flex: 1; }
.chunk-heading-row { display: flex; min-width: 0; align-items: flex-start; justify-content: space-between; gap: 16px; }
.chunk-heading-row strong { min-width: 0; color: #15233a; font-size: 13px; font-weight: 680; line-height: 1.5; }
.chunk-content { margin-top: 13px; color: #3a4a61; }
.chunk-footer { display: flex; min-height: 30px; align-items: end; justify-content: space-between; gap: 16px; margin-top: 13px; border-top: 1px solid #edf1f5; padding-top: 10px; }
.chunk-footer small { color: #8794a8; font-size: 11px; }
.chunk-kind { flex-shrink: 0; border-radius: 4px; background: #eaf8f1; padding: 4px 7px; color: #177b55; font-size: 11px; font-weight: 650; }
.parent-toggle { display: inline-flex; min-height: 30px; flex-shrink: 0; align-items: center; gap: 5px; border-radius: 5px; padding: 5px 8px; color: #315f52; font-size: 11.5px; font-weight: 650; transition: background-color 150ms ease, color 150ms ease; }
.parent-toggle:hover { background: #eaf8f1; color: #126745; }
.parent-toggle:focus-visible { outline: 2px solid #3974e8; outline-offset: 2px; }
.parent-toggle svg { transition: transform 150ms ease; }
.chunk-detail { border-top: 1px solid #cde9dc; background: #f1fbf6; padding: 18px 22px 22px 42px; }
.parent-context-flow { margin-top: 17px; color: #334155; }
.parent-context-flow > * + * { margin-top: 13px; }
.current-chunk-context { border-left: 3px solid #49a97c; border-radius: 0 6px 6px 0; background: #dff4e9; padding: 12px 14px; color: #21483b; box-shadow: inset 0 0 0 1px rgba(73, 169, 124, .12); }
.current-chunk-context :deep(.document-table-scroll) { border-color: #a9d8c2; background: rgba(255, 255, 255, .58); }
.current-chunk-context :deep(th) { background: #d4ebdf; color: #173f32; }
.current-chunk-context :deep(td) { background: rgba(250, 255, 252, .76); }
.current-chunk-context :deep(tbody tr:nth-child(even) td) { background: rgba(238, 249, 243, .82); }
.workspace-pagination { display: flex; align-items: center; justify-content: center; gap: 12px; padding: 12px 24px 24px; color: #728198; font-size: 12px; }
.metadata-layout { width: min(960px, calc(100% - 48px)); margin: 0 auto; padding: 28px 0 52px; }
.metadata-section + .metadata-section { margin-top: 38px; }
.metadata-section h4 { border-bottom: 1px solid #e3e9f1; padding-bottom: 11px; color: #15233a; font-size: 13px; font-weight: 650; }
.metadata-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 42px; }
.metadata-grid > div { display: grid; min-height: 62px; grid-template-columns: 150px minmax(0, 1fr); align-items: center; gap: 12px; border-bottom: 1px solid #edf1f6; }
.metadata-grid dt { color: #64748b; font-size: 12px; }
.metadata-grid dt code { display: block; margin-top: 3px; color: #a0aaba; font-size: 10px; }
.metadata-grid dd { overflow-wrap: anywhere; color: #263449; font-size: 13px; }
.metadata-grid dd.muted { color: #a0aaba; }
.revision-list > div { position: relative; display: grid; min-height: 58px; grid-template-columns: 14px 180px 1fr; align-items: center; gap: 10px; border-bottom: 1px solid #edf1f6; color: #44546a; font-size: 12px; }
.revision-list small { color: #8b97a9; }
.quality-notice { display: flex; align-items: flex-start; gap: 11px; margin: 18px 24px 0; border-left: 2px solid #e1a72f; background: #fff9e8; padding: 13px 15px; color: #5d4618; }
.quality-notice.fail { border-color: #cf4a5a; background: #fff0f2; color: #843041; }
.quality-notice strong { font-size: 13px; }
.quality-notice p { margin-top: 3px; font-size: 11px; line-height: 1.55; opacity: .82; }
.process-list { width: min(920px, calc(100% - 48px)); margin: 22px auto; }
.process-list article { position: relative; display: grid; grid-template-columns: 42px minmax(0, 1fr); }
.process-rail { position: relative; display: flex; justify-content: center; }
.process-rail::before { position: absolute; top: 0; bottom: 0; width: 1px; background: #d8e0ea; content: ''; }
.process-rail i { position: relative; z-index: 1; display: flex; width: 26px; height: 26px; align-items: center; justify-content: center; border: 1px solid #cbd5e1; border-radius: 999px; background: #fff; color: #64748b; font-size: 10px; font-style: normal; font-weight: 700; }
.process-rail i.succeeded { border-color: #65bd94; background: #eaf8f1; color: #177b55; }
.process-rail i.running { border-color: #82a9fa; background: #edf4ff; color: #225bd2; }
.process-rail i.failed { border-color: #e398a2; background: #fff0f2; color: #ae3c4c; }
.process-summary { display: grid; min-height: 64px; grid-template-columns: minmax(0, 1fr) 94px 72px 18px; align-items: start; gap: 12px; border-bottom: 1px solid #e8edf3; padding: 2px 8px 15px 2px; color: #425169; text-align: left; }
.process-summary strong, .process-summary small { display: block; }
.process-summary strong { color: #17243a; font-size: 13px; }
.process-summary small { margin-top: 5px; color: #7b899e; font-size: 11px; }
.process-summary time { padding-top: 5px; color: #8794a8; font-size: 11px; text-align: right; }
.process-detail { grid-column: 2; margin: -6px 8px 18px 2px; border: 1px solid #dfe6ef; background: #f7f9fc; padding: 14px 16px; color: #5b6b82; font-size: 11px; line-height: 1.65; }
.process-metrics { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; }
.process-metrics > div { display: grid; min-height: 34px; grid-template-columns: 92px minmax(0, 1fr); align-items: center; border-bottom: 1px solid #e4eaf1; }
.process-metrics dt { color: #8794a8; }
.process-metrics dd { overflow-wrap: anywhere; color: #425169; }
.retry-panel { display: flex; width: min(920px, calc(100% - 48px)); align-items: end; gap: 14px; margin: 12px auto 28px; border-top: 1px solid #e3e9f1; padding-top: 20px; }
.retry-panel > div { min-width: 220px; flex: 1; }
.retry-panel strong { color: #17243a; font-size: 13px; }
.retry-panel p { margin-top: 4px; color: #8794a8; font-size: 11px; }
.retry-panel label { width: 150px; color: #64748b; font-size: 11px; }
.retry-panel .checkbox { display: flex; width: auto; height: 36px; align-items: center; gap: 7px; color: #44546a; font-size: 12px; }
.retry-panel .checkbox input { width: 15px; height: 15px; accent-color: #2d68e8; }
.document-workspace-enter-active, .document-workspace-leave-active { transition: background-color 180ms ease; }
.document-workspace-enter-active .document-workspace, .document-workspace-leave-active .document-workspace { transition: transform 220ms cubic-bezier(.2, .8, .2, 1); }
.document-workspace-enter-from, .document-workspace-leave-to { background: rgba(15, 23, 42, 0); }
.document-workspace-enter-from .document-workspace, .document-workspace-leave-to .document-workspace { transform: translateX(100%); }
@media (prefers-reduced-motion: reduce) { .document-workspace-enter-active, .document-workspace-leave-active, .document-workspace-enter-active .document-workspace, .document-workspace-leave-active .document-workspace { transition: none; } }
</style>
