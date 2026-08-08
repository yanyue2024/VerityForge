<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import { FileDown, FileQuestion, LoaderCircle, RotateCcw } from 'lucide-vue-next'
import type { DocumentAsset } from '@/types/api'
import type { WorkBook } from '@e965/xlsx'

const props = defineProps<{
  asset?: DocumentAsset | null
  loading?: boolean
  error?: string
  pageNumber?: number | null
}>()

const docxHost = ref<HTMLElement | null>(null)
const renderedHtml = ref('')
const textContent = ref('')
const localError = ref('')
const rendering = ref(false)
const workbook = ref<WorkBook | null>(null)
const xlsxModule = shallowRef<typeof import('@e965/xlsx') | null>(null)
const selectedSheet = ref('')

const extension = computed(() => {
  const name = props.asset?.fileName.toLowerCase() ?? ''
  return name.includes('.') ? name.split('.').pop() ?? '' : ''
})

const kind = computed(() => {
  const contentType = props.asset?.contentType.toLowerCase() ?? ''
  if (contentType === 'application/pdf' || extension.value === 'pdf') return 'pdf'
  if (extension.value === 'docx') return 'docx'
  if (extension.value === 'xlsx' || extension.value === 'xls') return 'xlsx'
  if (contentType === 'text/markdown' || ['md', 'markdown'].includes(extension.value)) return 'markdown'
  if (contentType === 'text/html' || ['html', 'htm'].includes(extension.value)) return 'html'
  if (contentType.startsWith('text/') || ['txt', 'log', 'csv', 'json', 'xml'].includes(extension.value)) return 'text'
  return 'unsupported'
})

const pdfUrl = computed(() => {
  const source = props.asset?.previewUrl ?? ''
  if (!source) return ''
  const page = props.pageNumber && props.pageNumber > 0 ? props.pageNumber : 1
  return `${source}#page=${page}&zoom=page-width`
})

const sheetNames = computed(() => workbook.value?.SheetNames ?? [])
const activeSheetHtml = computed(() => {
  const book = workbook.value
  const name = selectedSheet.value
  if (!book || !name || !book.Sheets[name]) return ''
  const sheet = book.Sheets[name]
  if (!sheet || !xlsxModule.value) return ''
  return DOMPurify.sanitize(xlsxModule.value.utils.sheet_to_html(sheet, { id: 'verity-sheet' }))
})

function reset() {
  renderedHtml.value = ''
  textContent.value = ''
  localError.value = ''
  workbook.value = null
  selectedSheet.value = ''
  if (docxHost.value) docxHost.value.innerHTML = ''
}

async function renderAsset() {
  reset()
  const asset = props.asset
  if (!asset || ['pdf', 'html', 'unsupported'].includes(kind.value)) return

  rendering.value = true
  try {
    const response = await fetch(asset.previewUrl)
    if (!response.ok) throw new Error(`原文读取失败（${response.status}）`)

    if (kind.value === 'docx') {
      const buffer = await response.arrayBuffer()
      const { renderAsync } = await import('docx-preview')
      rendering.value = false
      await nextTick()
      if (!docxHost.value) return
      await renderAsync(buffer, docxHost.value, undefined, {
        className: 'verity-docx',
        inWrapper: true,
        ignoreWidth: false,
        ignoreHeight: false,
        ignoreFonts: false,
        breakPages: true,
        renderHeaders: true,
        renderFooters: true,
      })
      return
    }

    if (kind.value === 'xlsx') {
      const XLSX = await import('@e965/xlsx')
      xlsxModule.value = XLSX
      const book = XLSX.read(await response.arrayBuffer(), { type: 'array', cellDates: true })
      workbook.value = book
      selectedSheet.value = book.SheetNames[0] ?? ''
      return
    }

    const value = await response.text()
    if (kind.value === 'markdown') {
      const html = marked.parse(value, { gfm: true, breaks: false, async: false }) as string
      renderedHtml.value = DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
    } else {
      textContent.value = value
    }
  } catch (error) {
    localError.value = error instanceof Error ? error.message : '原文预览暂时不可用'
  } finally {
    rendering.value = false
  }
}

watch(() => [props.asset?.previewUrl, kind.value], () => { void renderAsset() }, { immediate: true })
</script>

<template>
  <div class="document-original-preview">
    <div v-if="loading || rendering" class="preview-centered">
      <LoaderCircle :size="22" class="animate-spin text-brand-600" aria-hidden="true" />
      <span>正在准备全文预览</span>
    </div>

    <div v-else-if="error || localError" class="preview-centered preview-error">
      <RotateCcw :size="22" aria-hidden="true" />
      <strong>原文预览暂时不可用</strong>
      <span>{{ error || localError }}</span>
      <button type="button" class="button-secondary min-h-9 px-3 text-xs" @click="renderAsset">重新加载</button>
    </div>

    <iframe
      v-else-if="asset && kind === 'pdf'"
      :key="pdfUrl"
      :src="pdfUrl"
      :title="`${asset.fileName} 原文预览`"
      class="preview-frame"
    />

    <iframe
      v-else-if="asset && kind === 'html'"
      :src="asset.previewUrl"
      :title="`${asset.fileName} 原文预览`"
      sandbox="allow-same-origin allow-downloads"
      class="preview-frame preview-frame-html"
    />

    <div v-else-if="asset && kind === 'docx'" class="preview-scroll preview-docx-canvas">
      <div ref="docxHost" class="preview-docx-host" />
    </div>

    <div v-else-if="asset && kind === 'xlsx'" class="flex min-h-0 flex-1 flex-col bg-white">
      <div class="flex h-11 shrink-0 items-center gap-1 overflow-x-auto border-b border-paper-200 px-4">
        <button
          v-for="sheet in sheetNames"
          :key="sheet"
          type="button"
          class="h-8 shrink-0 rounded-md px-3 text-xs font-medium transition-colors"
          :class="selectedSheet === sheet ? 'bg-ink-950 text-white' : 'text-ink-600 hover:bg-paper-100 hover:text-ink-950'"
          @click="selectedSheet = sheet"
        >
          {{ sheet }}
        </button>
      </div>
      <div class="preview-scroll preview-sheet-canvas">
        <div class="preview-sheet" v-html="activeSheetHtml" />
      </div>
    </div>

    <div v-else-if="asset && kind === 'markdown'" class="preview-scroll preview-reading-canvas">
      <article class="normalized-markdown preview-reading-page" v-html="renderedHtml" />
    </div>

    <div v-else-if="asset && kind === 'text'" class="preview-scroll preview-reading-canvas">
      <pre class="preview-text-page">{{ textContent }}</pre>
    </div>

    <div v-else-if="asset" class="preview-centered">
      <FileQuestion :size="28" class="text-ink-300" aria-hidden="true" />
      <strong>该格式暂不支持浏览器内预览</strong>
      <span>下载后可使用本地应用完整查看。</span>
      <a :href="asset.previewUrl" class="button-primary min-h-9 px-3 text-xs">
        <FileDown :size="15" aria-hidden="true" />
        下载原文件
      </a>
    </div>
  </div>
</template>

<style scoped>
.document-original-preview {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  background: #edf1f6;
}

.preview-frame {
  width: 100%;
  min-height: 0;
  flex: 1;
  border: 0;
  background: #ffffff;
}

.preview-frame-html {
  margin: 20px auto;
  width: calc(100% - 40px);
  max-width: 1040px;
  border: 1px solid #dce3ed;
}

.preview-scroll {
  min-height: 0;
  flex: 1;
  overflow: auto;
  scrollbar-color: #c4cfdd transparent;
  scrollbar-width: thin;
}

.preview-centered {
  display: flex;
  min-height: 420px;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px;
  color: #64748b;
  font-size: 13px;
  text-align: center;
}

.preview-centered strong {
  color: #273449;
  font-size: 14px;
}

.preview-error {
  color: #b64252;
}

.preview-docx-canvas,
.preview-reading-canvas {
  padding: 28px;
}

.preview-docx-host,
.preview-reading-page,
.preview-text-page {
  width: min(920px, 100%);
  min-height: 100%;
  margin: 0 auto;
  border: 1px solid #dce3ed;
  background: #ffffff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.07);
}

.preview-reading-page {
  padding: 54px 64px 72px;
}

.preview-reading-page :deep(h1) {
  margin: 0 0 28px;
  color: #0d172a;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.35;
}

.preview-reading-page :deep(h2) {
  margin: 40px 0 17px;
  border-bottom: 1px solid #e3e9f1;
  padding-bottom: 9px;
  color: #111d32;
  font-size: 21px;
  font-weight: 650;
}

.preview-reading-page :deep(h3) {
  margin: 28px 0 12px;
  color: #15233a;
  font-size: 17px;
  font-weight: 650;
}

.preview-reading-page :deep(p) {
  margin: 0 0 16px;
  color: #2f3d52;
  font-size: 14px;
  line-height: 1.85;
}

.preview-reading-page :deep(ul),
.preview-reading-page :deep(ol) {
  margin: 0 0 18px;
  padding-left: 24px;
  color: #2f3d52;
  font-size: 14px;
  line-height: 1.8;
}

.preview-reading-page :deep(table) {
  margin: 20px 0;
  width: 100%;
  border-collapse: collapse;
  color: #2f3d52;
  font-size: 13px;
}

.preview-reading-page :deep(th),
.preview-reading-page :deep(td) {
  border: 1px solid #dfe6ef;
  padding: 9px 11px;
  text-align: left;
  vertical-align: top;
}

.preview-reading-page :deep(th) {
  background: #f6f8fb;
  color: #17243a;
  font-weight: 650;
}

.preview-reading-page :deep(pre) {
  margin: 20px 0;
  overflow: auto;
  border: 1px solid #dfe6ef;
  background: #f7f9fc;
  padding: 16px;
  color: #24344b;
  font-size: 12.5px;
  line-height: 1.65;
}

.preview-reading-page :deep(code) {
  border-radius: 3px;
  background: #edf1f6;
  padding: 2px 5px;
  color: #20324e;
  font-size: .9em;
}

.preview-text-page {
  overflow: visible;
  padding: 42px 48px 64px;
  color: #273449;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 13px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.preview-sheet-canvas {
  padding: 22px;
  background: #edf1f6;
}

.preview-sheet {
  width: max-content;
  min-width: 100%;
  overflow: hidden;
  border: 1px solid #dce3ed;
  background: #ffffff;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.06);
}

.preview-sheet :deep(table) {
  border-collapse: collapse;
  color: #273449;
  font-size: 12px;
}

.preview-sheet :deep(td),
.preview-sheet :deep(th) {
  min-width: 96px;
  max-width: 420px;
  padding: 8px 10px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  text-align: left;
  vertical-align: top;
  white-space: pre-wrap;
}

.preview-sheet :deep(tr:first-child td) {
  background: #f8fafc;
  color: #172033;
  font-weight: 600;
}

.preview-docx-host :deep(.docx-wrapper) {
  padding: 0;
  background: transparent;
}

.preview-docx-host :deep(section.verity-docx) {
  margin: 0 auto 24px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.07);
}
</style>
