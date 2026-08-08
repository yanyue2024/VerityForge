<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { CalendarDays, ChevronDown, ListFilter, Plus, RotateCcw, X } from 'lucide-vue-next'
import { api, readableError } from '@/lib/api'
import type {
  CreateRunRequest,
  MetadataFilterFieldOption,
  MetadataFilterOptions,
} from '@/types/api'

type Filter = CreateRunRequest['filters'][number]

const props = defineProps<{
  modelValue: Filter[]
  knowledgeBaseIds: string[]
  composer?: boolean
  context?: 'chat' | 'knowledge'
}>()

const emit = defineEmits<{ 'update:modelValue': [filters: Filter[]] }>()
const root = ref<HTMLElement | null>(null)
const open = ref(false)
const moreOpen = ref(false)
const newMoreKey = ref('')
const selectedMoreKeys = ref<string[]>([])
const draftValues = ref<Record<string, string[]>>({})
const draftDates = ref<Record<string, { from: string; to: string }>>({})
const draftText = ref<Record<string, string>>({})

const fixedKeys = ['upload_time', 'category', 'file_type'] as const
const searchFields = new Set(['document_key', 'file_name'])

const optionsQuery = useQuery(
  computed(() => {
    const ids = [...props.knowledgeBaseIds].sort()
    const params = new URLSearchParams()
    ids.forEach((id) => params.append('knowledgeBaseIds', id))
    const suffix = params.size ? `?${params.toString()}` : ''
    return {
      queryKey: ['metadata-filter-options', ids],
      queryFn: () => api.get<MetadataFilterOptions>(`/api/v1/metadata-filter-options${suffix}`),
    }
  }),
)

const fields = computed(() => optionsQuery.data.value?.fields ?? [])
const fieldMap = computed(() => new Map(fields.value.map((field) => [field.key, field])))
const fixedFields = computed(() => fixedKeys
  .map((key) => fieldMap.value.get(key))
  .filter((field): field is MetadataFilterFieldOption => Boolean(field)))
const moreFields = computed(() => fields.value.filter((field) =>
  !fixedKeys.includes(field.key as typeof fixedKeys[number]) && field.populated,
))
const addableMoreFields = computed(() => moreFields.value.filter((field) =>
  !selectedMoreKeys.value.includes(field.key),
))
const activeFieldCount = computed(() => new Set(props.modelValue.map((filter) => filter.field)).size)

function dateValue(value: unknown) {
  if (typeof value !== 'string' || !value) return ''
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value.slice(0, 10)
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(parsed)
}

function ensureDate(key: string) {
  draftDates.value[key] ??= { from: '', to: '' }
  return draftDates.value[key]
}

function loadDraft() {
  draftValues.value = {}
  draftDates.value = {}
  draftText.value = {}
  selectedMoreKeys.value = []
  moreOpen.value = false
  newMoreKey.value = ''

  props.modelValue.forEach((filter) => {
    const definition = fieldMap.value.get(filter.field)
    if (definition?.type === 'DATE' || definition?.type === 'DATETIME') {
      const range = ensureDate(filter.field)
      if (filter.operator === 'GT' || filter.operator === 'GTE' || filter.operator === 'EQ') {
        range.from = dateValue(filter.value)
      }
      if (filter.operator === 'LT' || filter.operator === 'LTE' || filter.operator === 'EQ') {
        range.to = dateValue(filter.value)
      }
    } else if (filter.operator === 'IN' && Array.isArray(filter.value)) {
      draftValues.value[filter.field] = filter.value.map(String)
    } else if (filter.operator === 'EQ' && definition?.values.length) {
      draftValues.value[filter.field] = [String(filter.value)]
    } else {
      draftText.value[filter.field] = String(filter.value ?? '')
    }
    if (!fixedKeys.includes(filter.field as typeof fixedKeys[number])) {
      selectedMoreKeys.value.push(filter.field)
    }
  })
  selectedMoreKeys.value = [...new Set(selectedMoreKeys.value)]
}

function toggleOpen() {
  if (open.value) {
    open.value = false
    return
  }
  loadDraft()
  open.value = true
}

function closeWithoutApplying() {
  open.value = false
}

function toggleValue(key: string, value: string) {
  const current = draftValues.value[key] ?? []
  draftValues.value[key] = current.includes(value)
    ? current.filter((item) => item !== value)
    : [...current, value]
}

function isSelected(key: string, value: string) {
  return (draftValues.value[key] ?? []).includes(value)
}

function beijingToday() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}

function subtractDays(date: string, amount: number) {
  const value = new Date(`${date}T00:00:00Z`)
  value.setUTCDate(value.getUTCDate() - amount)
  return value.toISOString().slice(0, 10)
}

function datePreset(key: string, days: number | null) {
  const range = ensureDate(key)
  if (days === null) {
    range.from = ''
    range.to = ''
    return
  }
  const today = beijingToday()
  range.from = subtractDays(today, Math.max(0, days - 1))
  range.to = today
}

function addMoreField() {
  if (!newMoreKey.value) return
  selectedMoreKeys.value.push(newMoreKey.value)
  newMoreKey.value = ''
}

function removeMoreField(key: string) {
  selectedMoreKeys.value = selectedMoreKeys.value.filter((item) => item !== key)
  delete draftValues.value[key]
  delete draftDates.value[key]
  delete draftText.value[key]
}

function clearAll() {
  draftValues.value = {}
  draftDates.value = {}
  draftText.value = {}
  selectedMoreKeys.value = []
  newMoreKey.value = ''
}

function typedValue(field: MetadataFilterFieldOption, value: string) {
  if (field.type === 'BOOLEAN') return value === 'true'
  if (field.type === 'NUMBER') return Number(value)
  return value
}

function filtersFor(field: MetadataFilterFieldOption): Filter[] {
  if (field.type === 'DATE' || field.type === 'DATETIME') {
    const range = draftDates.value[field.key]
    if (!range) return []
    const filters: Filter[] = []
    if (range.from) {
      filters.push({
        field: field.key,
        operator: 'GTE',
        value: field.type === 'DATE' ? range.from : `${range.from}T00:00:00+08:00`,
      })
    }
    if (range.to) {
      filters.push({
        field: field.key,
        operator: 'LTE',
        value: field.type === 'DATE' ? range.to : `${range.to}T23:59:59.999+08:00`,
      })
    }
    return filters
  }

  const selected = draftValues.value[field.key] ?? []
  if (selected.length) {
    return [{ field: field.key, operator: 'IN', value: selected.map((value) => typedValue(field, value)) }]
  }
  const text = draftText.value[field.key]?.trim()
  if (!text) return []
  return [{
    field: field.key,
    operator: field.type === 'TEXT' ? 'CONTAINS' : 'EQ',
    value: typedValue(field, text),
  }]
}

function apply() {
  const activeKeys = [...fixedKeys, ...selectedMoreKeys.value]
  const filters = activeKeys.flatMap((key) => {
    const field = fieldMap.value.get(key)
    return field ? filtersFor(field) : []
  })
  emit('update:modelValue', filters)
  open.value = false
}

function fieldUsesChoices(field: MetadataFilterFieldOption) {
  return field.values.length > 0 && !searchFields.has(field.key)
}

function handleOutsidePointer(event: PointerEvent) {
  if (open.value && root.value && !root.value.contains(event.target as Node)) closeWithoutApplying()
}

onMounted(() => document.addEventListener('pointerdown', handleOutsidePointer))
onBeforeUnmount(() => document.removeEventListener('pointerdown', handleOutsidePointer))
</script>

<template>
  <div ref="root" class="relative">
    <button
      type="button"
      :class="props.composer ? ['chat-tool-button', { active: open || modelValue.length }] : 'button-secondary min-h-9 px-3 py-1.5 text-xs'"
      title="设置 Metadata 过滤条件"
      :aria-expanded="open"
      @click="toggleOpen"
    >
      <span v-if="props.composer" class="chat-tool-icon" aria-hidden="true">
        <ListFilter :size="16" />
      </span>
      <ListFilter v-else :size="15" aria-hidden="true" />
      <span>过滤</span>
      <span v-if="activeFieldCount" class="font-semibold text-brand-700">· {{ activeFieldCount }}</span>
    </button>

    <div
      v-if="open"
      class="absolute right-0 z-30 w-[720px] overflow-hidden rounded-lg border border-paper-200 bg-white shadow-panel"
      :class="props.composer ? 'bottom-11' : 'top-11'"
    >
      <div class="flex items-start justify-between gap-4 border-b border-paper-200 px-5 py-4">
        <div>
          <p class="text-base font-semibold text-ink-950">Metadata 过滤</p>
          <p class="mt-1 text-xs text-ink-400">
            <template v-if="props.context === 'knowledge'">筛选当前知识库中的文档</template>
            <template v-else>条件持续作用于当前对话 · {{ knowledgeBaseIds.length ? `所选 ${knowledgeBaseIds.length} 个知识库` : '全部知识库' }}</template>
          </p>
        </div>
        <button type="button" class="icon-button size-8" title="放弃修改并关闭" @click="closeWithoutApplying">
          <X :size="17" aria-hidden="true" />
        </button>
      </div>

      <div class="max-h-[520px] overflow-y-auto px-5 py-4">
        <div v-if="optionsQuery.isPending.value" class="space-y-3">
          <div v-for="index in 3" :key="index" class="h-16 animate-pulse rounded-md bg-paper-100" />
        </div>
        <div v-else-if="optionsQuery.isError.value" class="rounded-md bg-coral-50 px-4 py-3 text-sm text-coral-700">
          {{ readableError(optionsQuery.error.value) }}
        </div>
        <div v-else class="space-y-5">
          <section v-for="field in fixedFields" :key="field.key">
            <div class="mb-2.5 flex items-center justify-between gap-3">
              <label class="text-sm font-medium text-ink-900">{{ field.label }}</label>
              <button
                v-if="field.type === 'DATE' || field.type === 'DATETIME'"
                type="button"
                class="text-xs text-ink-400 transition-colors hover:text-brand-700"
                @click="datePreset(field.key, null)"
              >
                不限时间
              </button>
            </div>

            <template v-if="field.type === 'DATE' || field.type === 'DATETIME'">
              <div class="flex items-center gap-2">
                <div class="relative min-w-0 flex-1">
                  <CalendarDays :size="15" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400" />
                  <input v-model="ensureDate(field.key).from" type="date" class="control h-10 pl-9 text-sm" aria-label="开始日期" />
                </div>
                <span class="text-xs text-ink-300">至</span>
                <div class="relative min-w-0 flex-1">
                  <CalendarDays :size="15" class="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-ink-400" />
                  <input v-model="ensureDate(field.key).to" type="date" class="control h-10 pl-9 text-sm" aria-label="结束日期" />
                </div>
                <div class="flex shrink-0 gap-1">
                  <button type="button" class="rounded-md px-2.5 py-2 text-xs text-ink-500 hover:bg-paper-100" @click="datePreset(field.key, 1)">今天</button>
                  <button type="button" class="rounded-md px-2.5 py-2 text-xs text-ink-500 hover:bg-paper-100" @click="datePreset(field.key, 7)">近 7 天</button>
                  <button type="button" class="rounded-md px-2.5 py-2 text-xs text-ink-500 hover:bg-paper-100" @click="datePreset(field.key, 30)">近 30 天</button>
                </div>
              </div>
            </template>
            <div v-else-if="field.values.length" class="flex flex-wrap gap-2">
              <button
                v-for="value in field.values"
                :key="value"
                type="button"
                class="rounded-md border px-3 py-2 text-xs transition-colors"
                :class="isSelected(field.key, value)
                  ? 'border-brand-300 bg-brand-50 text-brand-700'
                  : 'border-paper-200 bg-white text-ink-600 hover:border-brand-200 hover:text-ink-900'"
                :aria-pressed="isSelected(field.key, value)"
                @click="toggleValue(field.key, value)"
              >
                {{ value }}
              </button>
            </div>
            <p v-else class="text-xs text-ink-400">当前知识范围暂无可用值</p>
          </section>

          <section class="border-t border-paper-200 pt-4">
            <button
              type="button"
              class="flex w-full items-center justify-between gap-3 text-sm font-medium text-ink-800"
              :aria-expanded="moreOpen"
              @click="moreOpen = !moreOpen"
            >
              <span>更多条件</span>
              <ChevronDown :size="16" class="transition-transform" :class="{ 'rotate-180': moreOpen }" aria-hidden="true" />
            </button>

            <div v-if="moreOpen" class="mt-4 space-y-4">
              <div
                v-for="key in selectedMoreKeys"
                :key="key"
                class="rounded-md border border-paper-200 bg-paper-50 px-3.5 py-3"
              >
                <template v-if="fieldMap.get(key)">
                  <div class="mb-2.5 flex items-center justify-between gap-3">
                    <label class="text-xs font-medium text-ink-700">{{ fieldMap.get(key)?.label }}</label>
                    <button type="button" class="icon-button size-7" :title="`移除${fieldMap.get(key)?.label}`" @click="removeMoreField(key)">
                      <X :size="14" aria-hidden="true" />
                    </button>
                  </div>
                  <div v-if="fieldMap.get(key)?.type === 'DATE' || fieldMap.get(key)?.type === 'DATETIME'" class="flex items-center gap-2">
                    <input v-model="ensureDate(key).from" type="date" class="control h-9 py-1.5 text-xs" aria-label="开始日期" />
                    <span class="text-xs text-ink-300">至</span>
                    <input v-model="ensureDate(key).to" type="date" class="control h-9 py-1.5 text-xs" aria-label="结束日期" />
                  </div>
                  <div v-else-if="fieldUsesChoices(fieldMap.get(key)!)" class="flex flex-wrap gap-2">
                    <button
                      v-for="value in fieldMap.get(key)?.values"
                      :key="value"
                      type="button"
                      class="rounded-md border px-2.5 py-1.5 text-xs transition-colors"
                      :class="isSelected(key, value)
                        ? 'border-brand-300 bg-white text-brand-700'
                        : 'border-paper-200 bg-white text-ink-600 hover:border-brand-200'"
                      :aria-pressed="isSelected(key, value)"
                      @click="toggleValue(key, value)"
                    >
                      {{ value }}
                    </button>
                  </div>
                  <input
                    v-else
                    v-model="draftText[key]"
                    :type="fieldMap.get(key)?.type === 'NUMBER' ? 'number' : 'text'"
                    class="control h-9 py-1.5 text-xs"
                    :placeholder="`输入${fieldMap.get(key)?.label}`"
                  />
                </template>
              </div>

              <div v-if="addableMoreFields.length" class="flex items-center gap-2">
                <select v-model="newMoreKey" class="control h-9 min-w-0 flex-1 py-1.5 text-xs">
                  <option value="">选择其他 Metadata 字段</option>
                  <option v-for="field in addableMoreFields" :key="field.key" :value="field.key">
                    {{ field.label }}
                  </option>
                </select>
                <button type="button" class="button-secondary min-h-9 px-3 text-xs" :disabled="!newMoreKey" @click="addMoreField">
                  <Plus :size="14" aria-hidden="true" />
                  添加
                </button>
              </div>
              <p v-else-if="!selectedMoreKeys.length" class="text-xs text-ink-400">当前知识范围没有其他可用字段</p>
            </div>
          </section>
        </div>
      </div>

      <div class="flex items-center justify-between border-t border-paper-200 bg-paper-50 px-5 py-3">
        <button type="button" class="inline-flex items-center gap-1.5 text-xs text-ink-500 hover:text-ink-900" @click="clearAll">
          <RotateCcw :size="14" aria-hidden="true" />
          清除全部
        </button>
        <div class="flex items-center gap-2">
          <button type="button" class="button-secondary min-h-9 px-4 text-xs" @click="closeWithoutApplying">取消</button>
          <button type="button" class="button-primary min-h-9 px-4 text-xs" :disabled="optionsQuery.isPending.value" @click="apply">应用</button>
        </div>
      </div>
    </div>
  </div>
</template>
