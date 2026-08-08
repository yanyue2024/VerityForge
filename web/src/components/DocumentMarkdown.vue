<script setup lang="ts">
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { computed } from 'vue'

const props = defineProps<{
  markdown?: string | null
}>()

function isFence(line: string) {
  return /^\s*(`{3,}|~{3,})/.test(line)
}

function looksLikePipeRow(line: string) {
  const trimmed = line.trim()
  return trimmed.startsWith('|') && trimmed.endsWith('|') && (trimmed.match(/\|/g)?.length ?? 0) >= 3
}

function isTableDivider(line: string) {
  return /^\s*\|?(?:\s*:?-{3,}:?\s*\|)+(?:\s*:?-{3,}:?\s*)?\|?\s*$/.test(line)
}

function protectMalformedTables(lines: string[]) {
  const result: string[] = []
  let inFence = false

  for (let index = 0; index < lines.length;) {
    const line = lines[index]
    if (isFence(line)) {
      inFence = !inFence
      result.push(line)
      index += 1
      continue
    }
    if (inFence || !looksLikePipeRow(line)) {
      result.push(line)
      index += 1
      continue
    }

    const block: string[] = []
    let cursor = index
    while (cursor < lines.length && looksLikePipeRow(lines[cursor])) {
      block.push(lines[cursor])
      cursor += 1
    }
    if (block.length >= 2 && !block.some(isTableDivider)) {
      result.push('```text', ...block, '```')
    } else {
      result.push(...block)
    }
    index = cursor
  }
  return result
}

function normalizeDisplayMarkdown(value: string) {
  const lines = protectMalformedTables(
    value.replace(/\r\n?/g, '\n').split('\n').map((line) => line.replace(/[\t ]+$/g, '')),
  )
  const result: string[] = []
  let inFence = false
  let previousBlank = false

  for (const line of lines) {
    if (isFence(line)) {
      inFence = !inFence
      previousBlank = false
      result.push(line)
      continue
    }
    if (!inFence && !line.trim()) {
      if (!previousBlank) result.push('')
      previousBlank = true
      continue
    }
    previousBlank = false
    result.push(line)
  }
  return result.join('\n').trim()
}

function wrapTables(html: string) {
  const documentNode = new DOMParser().parseFromString(html, 'text/html')
  documentNode.body.querySelectorAll('table').forEach((table) => {
    const wrapper = documentNode.createElement('div')
    wrapper.className = 'document-table-scroll'
    wrapper.tabIndex = 0
    wrapper.setAttribute('role', 'region')
    wrapper.setAttribute('aria-label', '表格，可横向滚动查看')
    table.parentNode?.insertBefore(wrapper, table)
    wrapper.appendChild(table)
  })
  return documentNode.body.innerHTML
}

const html = computed(() => {
  const markdown = normalizeDisplayMarkdown(props.markdown ?? '')
  if (!markdown) return ''
  const parsed = marked.parse(markdown, { gfm: true, breaks: false, async: false }) as string
  return DOMPurify.sanitize(wrapTables(parsed), { USE_PROFILES: { html: true } })
})
</script>

<template>
  <div v-if="html" class="document-markdown" v-html="html" />
</template>

<style scoped>
.document-markdown {
  min-width: 0;
  color: inherit;
  font-size: 13.5px;
  line-height: 1.76;
  overflow-wrap: break-word;
}
.document-markdown :deep(> :first-child) { margin-top: 0; }
.document-markdown :deep(> :last-child) { margin-bottom: 0; }
.document-markdown :deep(h1),
.document-markdown :deep(h2),
.document-markdown :deep(h3),
.document-markdown :deep(h4) {
  margin: 20px 0 9px;
  color: #142139;
  font-weight: 680;
  line-height: 1.42;
}
.document-markdown :deep(h1) { font-size: 18px; }
.document-markdown :deep(h2) { font-size: 16px; }
.document-markdown :deep(h3), .document-markdown :deep(h4) { font-size: 14px; }
.document-markdown :deep(p) { margin: 0 0 12px; }
.document-markdown :deep(ul), .document-markdown :deep(ol) {
  margin: 0 0 13px;
  padding-left: 23px;
}
.document-markdown :deep(li + li) { margin-top: 4px; }
.document-markdown :deep(blockquote) {
  margin: 14px 0;
  border-left: 2px solid #9fb3ca;
  padding: 2px 0 2px 13px;
  color: #52627a;
}
.document-markdown :deep(.document-table-scroll) {
  max-width: 100%;
  margin: 14px 0;
  overflow-x: auto;
  border: 1px solid #cfdbe6;
  border-radius: 6px;
  background: rgba(255, 255, 255, .72);
  scrollbar-color: #afbdcc transparent;
  scrollbar-width: thin;
}
.document-markdown :deep(.document-table-scroll:focus-visible) {
  outline: 2px solid #3974e8;
  outline-offset: 2px;
}
.document-markdown :deep(table) {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  color: #2c3b50;
  font-size: 12.5px;
  line-height: 1.55;
}
.document-markdown :deep(th), .document-markdown :deep(td) {
  border-right: 1px solid #d9e2eb;
  border-bottom: 1px solid #d9e2eb;
  padding: 8px 11px;
  text-align: left;
  vertical-align: top;
}
.document-markdown :deep(th:last-child), .document-markdown :deep(td:last-child) { border-right: 0; }
.document-markdown :deep(tr:last-child td) { border-bottom: 0; }
.document-markdown :deep(th) {
  background: #edf2f7;
  color: #18263c;
  font-weight: 680;
  white-space: nowrap;
}
.document-markdown :deep(td) {
  max-width: 34rem;
  background: rgba(255, 255, 255, .82);
  overflow-wrap: anywhere;
}
.document-markdown :deep(tbody tr:nth-child(even) td) { background: rgba(246, 249, 252, .88); }
.document-markdown :deep(pre) {
  max-width: 100%;
  margin: 14px 0;
  overflow: auto;
  border: 1px solid #d8e1eb;
  border-radius: 6px;
  background: #f6f8fb;
  padding: 13px 15px;
  color: #26364d;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre;
}
.document-markdown :deep(code) {
  border-radius: 3px;
  background: #edf1f5;
  padding: 1px 4px;
  color: #253750;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: .9em;
}
.document-markdown :deep(pre code) { background: transparent; padding: 0; color: inherit; }
.document-markdown :deep(hr) { margin: 18px 0; border: 0; border-top: 1px solid #dce4ed; }
.document-markdown :deep(a) { color: #225bd2; text-decoration: underline; text-underline-offset: 2px; }
.document-markdown :deep(img) { max-width: 100%; height: auto; }
</style>
