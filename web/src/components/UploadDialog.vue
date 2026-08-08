<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { AlertCircle, Check, Download, FileSpreadsheet, FileUp, LoaderCircle } from 'lucide-vue-next'
import ModalDialog from '@/components/ModalDialog.vue'
import { api, ApiClientError, getStoredAccessToken, readableError, resolveApiUrl } from '@/lib/api'
import { formatBytes } from '@/lib/format'
import type {
  CompleteUpload,
  CreateUploadIntent,
  MetadataFieldDefinition,
  MetadataManifest,
  MetadataManifestRow,
  MetadataSchema,
  UploadIntent,
} from '@/types/api'

const props = defineProps<{
  open: boolean
  knowledgeBaseId: string
  documentId?: string | null
}>()

const emit = defineEmits<{
  close: []
  uploaded: []
}>()

type UploadMethod = 'MANIFEST' | 'SINGLE'

const queryClient = useQueryClient()
const method = ref<UploadMethod>('MANIFEST')
const files = ref<File[]>([])
const manifestFile = ref<File | null>(null)
const manifest = ref<MetadataManifest | null>(null)
const title = ref('')
const metadataValues = ref<Record<string, string>>({})
const submitting = ref(false)
const parsing = ref(false)
const progress = ref(0)
const errorMessage = ref('')
const completed = ref<CompleteUpload[]>([])

const schemaQuery = useQuery(
  computed(() => ({
    queryKey: ['metadata-schema', 'organization'],
    queryFn: () => api.get<MetadataSchema>('/api/v1/metadata-schema'),
    enabled: props.open,
  })),
)

const manifestRows = computed(() => new Map(
  (manifest.value?.rows ?? []).map((row) => [row.fileName.toLowerCase(), row]),
))

const mappedFiles = computed(() => files.value.map((file) => ({
  file,
  row: manifestRows.value.get(file.name.toLowerCase()) ?? null,
})))

const extraRows = computed(() => (manifest.value?.rows ?? []).filter(
  (row) => !files.value.some((file) => file.name.toLowerCase() === row.fileName.toLowerCase()),
))

const manifestReady = computed(() =>
  Boolean(manifest.value) &&
  files.value.length > 0 &&
  !(manifest.value?.errors.length) &&
  !extraRows.value.length &&
  mappedFiles.value.every((item) => item.row && !item.row.errors.length),
)

watch(
  () => props.open,
  (open) => {
    if (open) {
      method.value = props.documentId ? 'SINGLE' : 'MANIFEST'
      return
    }
    files.value = []
    manifestFile.value = null
    manifest.value = null
    title.value = ''
    metadataValues.value = {}
    errorMessage.value = ''
    completed.value = []
    progress.value = 0
  },
)

function extension(fileName: string) {
  return fileName.includes('.') ? fileName.split('.').pop()?.toUpperCase() || 'UNKNOWN' : 'UNKNOWN'
}

function documentKey(fileName: string) {
  return fileName.replace(/\.[^.]+$/, '').replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-|-$/g, '').toUpperCase() || 'DOCUMENT'
}

function initializeManualFile(file: File) {
  title.value ||= file.name.replace(/\.[^.]+$/, '')
  const defaults: Record<string, string> = {
    document_key: documentKey(file.name),
    file_name: file.name,
    upload_time: new Date().toISOString().slice(0, 16),
    file_type: extension(file.name),
    version: props.documentId ? 'next' : 'v1.0',
  }
  for (const [key, value] of Object.entries(defaults)) {
    if (!metadataValues.value[key]) metadataValues.value[key] = value
  }
}

function onFilesChange(event: Event) {
  const selected = Array.from((event.target as HTMLInputElement).files ?? [])
  files.value = method.value === 'SINGLE' ? selected.slice(0, 1) : selected
  if (method.value === 'SINGLE' && files.value[0]) initializeManualFile(files.value[0])
}

async function onManifestChange(event: Event) {
  manifestFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  manifest.value = null
  if (!manifestFile.value) return
  parsing.value = true
  errorMessage.value = ''
  try {
    const body = new FormData()
    body.append('file', manifestFile.value)
    manifest.value = await api.post<MetadataManifest>('/api/v1/metadata-manifests/parse', body)
  } catch (error) {
    errorMessage.value = readableError(error)
  } finally {
    parsing.value = false
  }
}

function metadataValue(field: MetadataFieldDefinition) {
  const raw = metadataValues.value[field.key]?.trim() ?? ''
  if (!raw) return undefined
  if (field.type === 'NUMBER') {
    const value = Number(raw)
    if (!Number.isFinite(value)) throw new Error(`${field.label} 必须是数字`)
    return value
  }
  if (field.type === 'BOOLEAN') return raw === 'true'
  if (field.type === 'TEXT_LIST') return raw.split(',').map((value) => value.trim()).filter(Boolean)
  if (field.type === 'DATETIME') return new Date(raw).toISOString()
  return raw
}

function manualMetadata() {
  const result: Record<string, unknown> = {}
  for (const field of schemaQuery.data.value?.fields ?? []) {
    const value = metadataValue(field)
    if (value === undefined) {
      if (field.required) throw new Error(`${field.label} 为必填字段`)
      continue
    }
    result[field.key] = value
  }
  return result
}

async function fileSha256(value: File) {
  const digest = await crypto.subtle.digest('SHA-256', await value.arrayBuffer())
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

function uploadContentType(value: File) {
  const suffix = value.name.toLowerCase().split('.').pop()
  if (suffix === 'md' || suffix === 'markdown') return 'text/markdown'
  if (suffix === 'html' || suffix === 'htm') return 'text/html'
  return value.type || 'application/octet-stream'
}

async function uploadOne(file: File, row?: MetadataManifestRow | null) {
  const metadata = row?.metadata ?? manualMetadata()
  const validToValue = row?.validTo ?? (typeof metadata.valid_to === 'string' ? metadata.valid_to : undefined)
  const intent = await api.post<UploadIntent>(
    `/api/v1/knowledge-bases/${props.knowledgeBaseId}/documents/upload-intents`,
    {
      title: row?.title || title.value.trim(),
      fileName: file.name,
      contentType: uploadContentType(file),
      byteSize: file.size,
      sha256: await fileSha256(file),
      metadata,
      validTo: validToValue,
      documentId: props.documentId || undefined,
    } satisfies CreateUploadIntent,
  )
  const response = await fetch(intent.uploadUrl, { method: intent.method || 'PUT', headers: intent.headers, body: file })
  if (!response.ok) throw new ApiClientError(`对象存储上传失败（${response.status}）`, response.status)
  return api.post<CompleteUpload>(`/api/v1/uploads/${intent.uploadId}/complete`)
}

async function submit() {
  if (!files.value.length) return
  errorMessage.value = ''
  submitting.value = true
  completed.value = []
  progress.value = 0
  try {
    const queue = method.value === 'MANIFEST' ? mappedFiles.value : [{ file: files.value[0], row: null }]
    for (const item of queue) {
      completed.value.push(await uploadOne(item.file, item.row))
      progress.value += 1
      emit('uploaded')
    }
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['documents', props.knowledgeBaseId] }),
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases'] }),
      props.documentId ? queryClient.invalidateQueries({ queryKey: ['document', props.documentId] }) : Promise.resolve(),
    ])
  } catch (error) {
    errorMessage.value = readableError(error)
  } finally {
    submitting.value = false
  }
}

async function downloadTemplate() {
  errorMessage.value = ''
  try {
    const headers = new Headers()
    const token = getStoredAccessToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const response = await fetch(resolveApiUrl('/api/v1/metadata-manifests/template'), { headers })
    if (!response.ok) throw new ApiClientError('模板下载失败', response.status)
    const url = URL.createObjectURL(await response.blob())
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = 'metadata.xlsx'
    anchor.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    errorMessage.value = readableError(error)
  }
}
</script>

<template>
  <ModalDialog
    :open="open"
    :title="documentId ? '上传新版本' : '上传文档'"
    :description="documentId ? '新版本处理完成后会替换当前检索版本。' : '文档级 Metadata 在处理前写入，不从正文解析。'"
    width-class="max-w-3xl"
    @close="emit('close')"
  >
    <div v-if="completed.length && completed.length === files.length" class="py-4 text-center">
      <span class="mx-auto flex size-11 items-center justify-center rounded-full bg-evidence-50 text-evidence-700"><Check :size="21" aria-hidden="true" /></span>
      <h3 class="mt-4 text-base font-semibold text-ink-950">{{ completed.length }} 份文档已进入处理队列</h3>
      <p class="mt-2 text-sm text-ink-500">可在文档详情的“处理过程”中查看解析、分块与向量化进度。</p>
      <button type="button" class="button-primary mt-6" @click="emit('close')">完成</button>
    </div>

    <form v-else @submit.prevent="submit">
      <div v-if="!documentId" class="mb-6 inline-flex rounded-md bg-paper-100 p-0.5">
        <button type="button" class="h-9 rounded-md px-4 text-xs font-semibold" :class="method === 'MANIFEST' ? 'bg-white text-ink-950 shadow-sm' : 'text-ink-500'" @click="method = 'MANIFEST'; files = []">批量清单</button>
        <button type="button" class="h-9 rounded-md px-4 text-xs font-semibold" :class="method === 'SINGLE' ? 'bg-white text-ink-950 shadow-sm' : 'text-ink-500'" @click="method = 'SINGLE'; files = []; manifest = null">单份录入</button>
      </div>

      <template v-if="method === 'MANIFEST'">
        <div class="grid grid-cols-2 gap-4">
          <label class="upload-dropzone">
            <FileUp :size="20" class="text-ink-400" aria-hidden="true" />
            <span class="mt-2 text-sm font-semibold text-ink-800">选择文档</span>
            <span class="mt-1 text-xs text-ink-500">可多选 PDF、DOCX、XLSX、HTML、Markdown</span>
            <input class="sr-only" type="file" multiple accept=".pdf,.docx,.xlsx,.html,.htm,.md,.markdown" @change="onFilesChange" />
          </label>
          <label class="upload-dropzone">
            <FileSpreadsheet :size="20" class="text-evidence-700" aria-hidden="true" />
            <span class="mt-2 text-sm font-semibold text-ink-800">选择 metadata.xlsx</span>
            <span class="mt-1 text-xs text-ink-500">按 file_name 与文档一一匹配</span>
            <input class="sr-only" type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="onManifestChange" />
          </label>
        </div>
        <button type="button" class="mt-3 inline-flex h-8 items-center gap-1.5 text-xs font-semibold text-brand-700 hover:text-brand-800" @click="downloadTemplate"><Download :size="14" aria-hidden="true" />下载当前 Metadata 模板</button>

        <div v-if="parsing" class="mt-5 flex items-center gap-2 text-sm text-ink-500"><LoaderCircle :size="15" class="animate-spin" />正在校验清单</div>
        <div v-if="manifest?.errors.length" class="mt-5 rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700"><p v-for="error in manifest.errors" :key="error">{{ error }}</p></div>

        <div v-if="files.length || manifest" class="mt-5 overflow-hidden rounded-lg border border-paper-200">
          <div class="grid grid-cols-[1fr_100px_1fr] bg-paper-100 px-4 py-2.5 text-xs font-medium text-ink-500"><span>文件</span><span>映射</span><span>文档标识 / 问题</span></div>
          <div v-for="item in mappedFiles" :key="item.file.name" class="grid min-h-12 grid-cols-[1fr_100px_1fr] items-center border-t border-paper-200 px-4 text-xs">
            <span class="truncate font-medium text-ink-800">{{ item.file.name }}</span>
            <span :class="item.row && !item.row.errors.length ? 'text-evidence-700' : 'text-coral-700'">{{ item.row ? (item.row.errors.length ? '有错误' : '已匹配') : '未匹配' }}</span>
            <span class="truncate text-ink-500">{{ item.row?.errors.join('；') || item.row?.metadata.document_key || 'metadata.xlsx 中没有对应行' }}</span>
          </div>
          <div v-for="row in extraRows" :key="row.rowNumber" class="grid min-h-12 grid-cols-[1fr_100px_1fr] items-center border-t border-paper-200 bg-coral-50 px-4 text-xs text-coral-700"><span>{{ row.fileName }}</span><span>缺少文件</span><span>清单第 {{ row.rowNumber }} 行没有对应上传文件</span></div>
        </div>
      </template>

      <template v-else>
        <label class="upload-dropzone min-h-28">
          <FileUp :size="21" class="text-ink-400" aria-hidden="true" />
          <span class="mt-2 text-sm font-semibold text-ink-800">{{ files[0]?.name || '选择本地文件' }}</span>
          <span class="mt-1 text-xs text-ink-500">{{ files[0] ? formatBytes(files[0].size) : '最大 512 MB' }}</span>
          <input class="sr-only" type="file" accept=".pdf,.docx,.xlsx,.html,.htm,.md,.markdown" @change="onFilesChange" />
        </label>
        <label class="mt-5 block text-sm font-medium text-ink-800">文档标题<input v-model="title" class="control mt-2" maxlength="500" required /></label>
        <div v-if="schemaQuery.isPending.value" class="mt-5 h-20 animate-pulse bg-paper-100" />
        <div v-else class="mt-6 grid grid-cols-2 gap-4 border-t border-paper-200 pt-5">
          <label v-for="field in schemaQuery.data.value?.fields" :key="field.key" class="field-label">
            {{ field.label }}<span v-if="field.required" class="text-coral-700"> *</span>
            <select v-if="field.type === 'BOOLEAN'" v-model="metadataValues[field.key]" class="field-input"><option value="">未设置</option><option value="true">是</option><option value="false">否</option></select>
            <select v-else-if="field.allowedValues.length && field.type !== 'TEXT_LIST'" v-model="metadataValues[field.key]" class="field-input"><option value="">请选择</option><option v-for="value in field.allowedValues" :key="value" :value="value">{{ value }}</option></select>
            <input v-else v-model="metadataValues[field.key]" class="field-input" :type="field.type === 'NUMBER' ? 'number' : field.type === 'DATE' ? 'date' : field.type === 'DATETIME' ? 'datetime-local' : 'text'" :placeholder="field.type === 'TEXT_LIST' ? '多个值用逗号分隔' : undefined" />
          </label>
        </div>
      </template>

      <div v-if="submitting" class="mt-5 rounded-md bg-brand-50 px-4 py-3 text-sm text-brand-700">正在上传第 {{ progress + 1 }} / {{ files.length }} 份文档，请保持窗口打开。</div>
      <div v-if="errorMessage" class="mt-5 flex items-start gap-2 rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700"><AlertCircle :size="16" class="mt-0.5 shrink-0" aria-hidden="true" />{{ errorMessage }}</div>

      <div class="mt-6 flex justify-end gap-3 border-t border-paper-200 pt-5">
        <button type="button" class="button-secondary" :disabled="submitting" @click="emit('close')">取消</button>
        <button type="submit" class="button-primary" :disabled="submitting || !files.length || (method === 'MANIFEST' ? !manifestReady : !title.trim())">
          <LoaderCircle v-if="submitting" :size="17" class="animate-spin" aria-hidden="true" />
          {{ method === 'MANIFEST' ? `上传 ${files.length || ''} 份文档` : '上传并入库' }}
        </button>
      </div>
    </form>
  </ModalDialog>
</template>
