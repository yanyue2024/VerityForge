<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Braces, LoaderCircle, Pencil, Plus, Save, Trash2, X } from 'lucide-vue-next'
import ErrorState from '@/components/ErrorState.vue'
import { api, readableError } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'
import type { MetadataFieldDefinition, MetadataFieldType, MetadataSchema } from '@/types/api'

const props = withDefaults(defineProps<{ knowledgeBaseId?: string; organization?: boolean }>(), {
  knowledgeBaseId: '',
  organization: false,
})
const queryClient = useQueryClient()
const auth = useAuthStore()
const fields = ref<MetadataFieldDefinition[]>([])
const localError = ref('')
const editing = ref(false)
const endpoint = computed(() =>
  props.organization
    ? '/api/v1/metadata-schema'
    : `/api/v1/knowledge-bases/${props.knowledgeBaseId}/metadata-schema`,
)
const queryScope = computed(() => (props.organization ? 'organization' : props.knowledgeBaseId))

const schemaQuery = useQuery(
  computed(() => ({
    queryKey: ['metadata-schema', queryScope.value],
    queryFn: () => api.get<MetadataSchema>(endpoint.value),
    enabled: props.organization || Boolean(props.knowledgeBaseId),
  })),
)

const historyQuery = useQuery(
  computed(() => ({
    queryKey: ['metadata-schema-history', queryScope.value],
    queryFn: () => api.get<MetadataSchema[]>(`${endpoint.value}/versions`),
    enabled: props.organization || Boolean(props.knowledgeBaseId),
  })),
)

watch(
  () => schemaQuery.data.value,
  (schema) => {
    fields.value = (schema?.fields ?? []).map((field) => ({
      ...field,
      allowedValues: [...field.allowedValues],
    }))
  },
  { immediate: true },
)

const fieldTypes: Array<{ value: MetadataFieldType; label: string }> = [
  { value: 'TEXT', label: '文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'BOOLEAN', label: '布尔' },
  { value: 'DATE', label: '日期' },
  { value: 'DATETIME', label: '日期时间' },
  { value: 'TEXT_LIST', label: '文本列表' },
]

const validationError = computed(() => {
  const keys = fields.value.map((field) => field.key.trim())
  if (keys.some((key) => !/^[a-z][a-z0-9_]{0,62}$/.test(key))) {
    return '字段键必须使用小写字母、数字和下划线，并以字母开头。'
  }
  if (new Set(keys).size !== keys.length) return '字段键不能重复。'
  if (fields.value.some((field) => !field.label.trim())) return '字段名称不能为空。'
  return ''
})

const saveMutation = useMutation({
  mutationFn: () =>
    api.put<MetadataSchema>(endpoint.value, {
        fields: fields.value.map((field) => ({
          ...field,
          key: field.key.trim(),
          label: field.label.trim(),
          allowedValues: field.allowedValues.map((value) => value.trim()).filter(Boolean),
        })),
      }),
  onSuccess: async () => {
    localError.value = ''
    editing.value = false
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['metadata-schema'] }),
      queryClient.invalidateQueries({ queryKey: ['metadata-schema-history'] }),
    ])
  },
  onError: (error) => {
    localError.value = readableError(error)
  },
})

function addField() {
  fields.value.push({
    key: '',
    label: '',
    type: 'TEXT',
    required: false,
    filterable: true,
    allowedValues: [],
  })
}

function resetFields() {
  fields.value = (schemaQuery.data.value?.fields ?? []).map((field) => ({
    ...field,
    allowedValues: [...field.allowedValues],
  }))
}

function startEditing() {
  resetFields()
  localError.value = ''
  editing.value = true
}

function cancelEditing() {
  resetFields()
  localError.value = ''
  editing.value = false
}

function fieldTypeLabel(type: MetadataFieldType) {
  return fieldTypes.find((item) => item.value === type)?.label ?? type
}

function setAllowedValues(field: MetadataFieldDefinition, value: string) {
  field.allowedValues = value.split(',').map((item) => item.trim()).filter(Boolean)
}

function save() {
  localError.value = validationError.value
  if (!localError.value) saveMutation.mutate()
}
</script>

<template>
  <section class="border-b border-paper-200 py-6">
    <div class="flex flex-wrap items-start justify-between gap-4">
      <div class="flex items-start gap-3">
        <Braces :size="19" class="mt-0.5 text-brand-700" aria-hidden="true" />
        <div>
          <h2 class="text-sm font-semibold">
            {{ organization ? '统一文档 Metadata' : 'Metadata Schema' }}
          </h2>
          <p class="mt-1 text-xs leading-5 text-ink-400">
            <template v-if="organization">作用于组织内全部知识库 · </template>
            当前 v{{ schemaQuery.data.value?.version ?? 0 }} · 历史 {{ historyQuery.data.value?.length ?? 0 }} 版
          </p>
        </div>
      </div>
      <div v-if="auth.canEdit && editing" class="flex gap-2">
        <button type="button" class="button-secondary min-h-9 px-3" @click="addField">
          <Plus :size="16" aria-hidden="true" />
          添加字段
        </button>
        <button type="button" class="button-secondary min-h-9 px-3" @click="cancelEditing">
          <X :size="16" aria-hidden="true" />
          取消
        </button>
        <button
          type="button"
          class="button-primary min-h-9 px-3"
          :disabled="saveMutation.isPending.value"
          @click="save"
        >
          <LoaderCircle
            v-if="saveMutation.isPending.value"
            :size="16"
            class="animate-spin"
            aria-hidden="true"
          />
          <Save v-else :size="16" aria-hidden="true" />
          {{ organization ? '应用到全部知识库' : '发布新版本' }}
        </button>
      </div>
      <button v-else-if="auth.canEdit" type="button" class="button-secondary min-h-9 px-3" @click="startEditing">
        <Pencil :size="15" aria-hidden="true" />
        管理字段
      </button>
    </div>

    <ErrorState
      v-if="schemaQuery.isError.value"
      class="mt-5"
      :message="readableError(schemaQuery.error.value)"
      @retry="schemaQuery.refetch()"
    />

    <div v-else-if="schemaQuery.isPending.value" class="mt-5 h-24 animate-pulse bg-paper-100" />

    <div v-else-if="!fields.length" class="mt-5 border-y border-paper-200 py-7 text-sm text-ink-400">
      尚未定义自定义字段。上传仍可使用标准标题、来源、版本和有效期字段。
    </div>

    <div v-else class="mt-5 overflow-x-auto border-y border-paper-200">
      <table class="w-full min-w-[820px] text-left text-sm">
        <thead class="bg-paper-100 text-xs text-ink-400">
          <tr>
            <th class="px-3 py-2.5 font-medium">字段键</th>
            <th class="px-3 py-2.5 font-medium">显示名称</th>
            <th class="px-3 py-2.5 font-medium">类型</th>
            <th class="px-3 py-2.5 font-medium">候选值</th>
            <th class="px-3 py-2.5 text-center font-medium">必填</th>
            <th class="px-3 py-2.5 text-center font-medium">可过滤</th>
            <th class="w-12 px-2 py-2.5" />
          </tr>
        </thead>
        <tbody class="divide-y divide-paper-200">
          <tr v-for="(field, index) in fields" :key="index">
            <td class="px-3 py-2">
              <input v-if="editing" v-model="field.key" class="control h-9 py-1.5 font-mono text-xs" />
              <code v-else class="text-xs text-ink-600">{{ field.key }}</code>
            </td>
            <td class="px-3 py-2">
              <input v-if="editing" v-model="field.label" class="control h-9 py-1.5" />
              <span v-else class="font-medium text-ink-900">{{ field.label }}</span>
            </td>
            <td class="px-3 py-2">
              <select v-if="editing" v-model="field.type" class="control h-9 py-1.5">
                <option v-for="type in fieldTypes" :key="type.value" :value="type.value">
                  {{ type.label }}
                </option>
              </select>
              <span v-else class="text-ink-600">{{ fieldTypeLabel(field.type) }}</span>
            </td>
            <td class="px-3 py-2">
              <input
                v-if="editing"
                class="control h-9 py-1.5"
                :value="field.allowedValues.join(', ')"
                placeholder="可选，逗号分隔"
                @input="setAllowedValues(field, ($event.target as HTMLInputElement).value)"
              />
              <div v-else class="flex max-w-[360px] flex-wrap gap-1.5">
                <span v-for="value in field.allowedValues.slice(0, 4)" :key="value" class="rounded bg-paper-100 px-2 py-1 text-[11px] text-ink-600">{{ value }}</span>
                <span v-if="field.allowedValues.length > 4" class="px-1 py-1 text-[11px] text-ink-400">+{{ field.allowedValues.length - 4 }}</span>
                <span v-if="!field.allowedValues.length" class="text-xs text-ink-400">任意值</span>
              </div>
            </td>
            <td class="px-3 py-2 text-center">
              <input v-if="editing" v-model="field.required" type="checkbox" class="size-4 accent-brand-700" />
              <span v-else class="text-xs" :class="field.required ? 'text-ink-800' : 'text-ink-400'">{{ field.required ? '是' : '否' }}</span>
            </td>
            <td class="px-3 py-2 text-center">
              <input v-if="editing" v-model="field.filterable" type="checkbox" class="size-4 accent-brand-700" />
              <span v-else class="text-xs" :class="field.filterable ? 'text-evidence-700' : 'text-ink-400'">{{ field.filterable ? '可用' : '关闭' }}</span>
            </td>
            <td class="px-2 py-2">
              <button
                v-if="auth.canEdit && editing"
                type="button"
                class="icon-button size-8"
                title="删除字段"
                @click="fields.splice(index, 1)"
              >
                <Trash2 :size="15" aria-hidden="true" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="localError" class="mt-4 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
      {{ localError }}
    </p>
  </section>
</template>
