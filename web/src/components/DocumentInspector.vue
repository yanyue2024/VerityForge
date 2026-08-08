<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  AlignLeft,
  Braces,
  FileText,
  GitCompare,
  Layers3,
  ListChecks,
  LockKeyhole,
  LoaderCircle,
  Power,
  Pencil,
  Save,
  ShieldCheck,
  Upload,
  UsersRound,
  X,
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { useAuthStore } from '@/stores/auth'
import type {
  ChunkRow,
  DocumentAccessMode,
  DocumentDetail,
  DocumentMetadataRevision,
  IngestionJob,
  MetadataFieldDefinition,
  MetadataSchema,
  TeamMember,
  UserRole,
  VersionDiff,
} from '@/types/api'

const props = defineProps<{
  documentId: string | null
  initialVersionId?: string | null
  initialChunkId?: string | null
  initialPageNumber?: number | null
  initialSourceStart?: number | null
  initialSourceEnd?: number | null
}>()

const emit = defineEmits<{
  close: []
  newVersion: [documentId: string]
  statusChanged: []
}>()

const auth = useAuthStore()
const queryClient = useQueryClient()
const selectedVersionId = ref('')
const compareVersionId = ref('')
const changingStatus = ref(false)
const statusError = ref('')
const metadataEditing = ref(false)
const metadataValues = ref<Record<string, string>>({})
const metadataValidFrom = ref('')
const metadataValidTo = ref('')
const accessEditing = ref(false)
const accessMode = ref<DocumentAccessMode>('ORGANIZATION')
const allowedRoles = ref<UserRole[]>([])
const allowedUserIds = ref<string[]>([])
const accessError = ref('')
const accessRoleOptions: Array<{ role: UserRole; label: string }> = [
  { role: 'EDITOR', label: '编辑者' },
  { role: 'VIEWER', label: '查看者' },
]

const detailQuery = useQuery(
  computed(() => ({
    queryKey: ['document', props.documentId],
    queryFn: () => api.get<DocumentDetail>(`/api/v1/documents/${props.documentId}`),
    enabled: Boolean(props.documentId),
  })),
)

watch(
  () => detailQuery.data.value,
  (detail) => {
    if (!detail) return
    selectedVersionId.value =
      detail.versions.some((version) => version.id === props.initialVersionId)
        ? props.initialVersionId ?? ''
        : detail.currentVersionId || detail.versions[0]?.id || ''
    compareVersionId.value =
      detail.versions.find((version) => version.id !== selectedVersionId.value)?.id ?? ''
  },
  { immediate: true },
)

const chunksQuery = useQuery(
  computed(() => ({
    queryKey: ['chunks', selectedVersionId.value],
    queryFn: () =>
      api.get<ChunkRow[]>(`/api/v1/document-versions/${selectedVersionId.value}/chunks`),
    enabled: Boolean(selectedVersionId.value),
  })),
)

watch(
  () => chunksQuery.data.value,
  async (chunks) => {
    if (!props.initialChunkId || !chunks?.some((chunk) => chunk.id === props.initialChunkId)) return
    await nextTick()
    document.getElementById(`chunk-${props.initialChunkId}`)?.scrollIntoView({ block: 'center' })
  },
)

const selectedVersion = computed(() =>
  detailQuery.data.value?.versions.find((version) => version.id === selectedVersionId.value),
)

const membersQuery = useQuery(
  computed(() => ({
    queryKey: ['team-members'],
    queryFn: () => api.get<TeamMember[]>('/api/v1/team/members'),
    enabled: auth.isAdmin && Boolean(props.documentId),
  })),
)

function resetAccessForm() {
  const policy = detailQuery.data.value?.accessPolicy
  if (!policy) return
  accessMode.value = policy.mode
  allowedRoles.value = policy.allowedRoles.filter((role) => role !== 'ADMIN')
  allowedUserIds.value = [...policy.allowedUserIds]
}

function startAccessEdit() {
  resetAccessForm()
  accessError.value = ''
  accessEditing.value = true
}

function toggleRole(role: UserRole) {
  allowedRoles.value = allowedRoles.value.includes(role)
    ? allowedRoles.value.filter((value) => value !== role)
    : [...allowedRoles.value, role]
}

function toggleMember(memberId: string) {
  allowedUserIds.value = allowedUserIds.value.includes(memberId)
    ? allowedUserIds.value.filter((value) => value !== memberId)
    : [...allowedUserIds.value, memberId]
}

const accessMutation = useMutation({
  mutationFn: () => {
    const detail = detailQuery.data.value
    if (!detail) throw new Error('文档不存在')
    return api.put(`/api/v1/documents/${detail.id}/access-policy`, {
      mode: accessMode.value,
      allowedRoles: accessMode.value === 'RESTRICTED' ? allowedRoles.value : [],
      allowedUserIds: accessMode.value === 'RESTRICTED' ? allowedUserIds.value : [],
    })
  },
  onSuccess: async () => {
    accessEditing.value = false
    accessError.value = ''
    await Promise.all([
      detailQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: ['documents', detailQuery.data.value?.knowledgeBaseId] }),
      queryClient.invalidateQueries({ queryKey: ['knowledge-bases'] }),
    ])
    emit('statusChanged')
  },
  onError: (error) => {
    accessError.value = readableError(error)
  },
})

const schemaQuery = useQuery(
  computed(() => ({
    queryKey: ['metadata-schema', detailQuery.data.value?.knowledgeBaseId],
    queryFn: () =>
      api.get<MetadataSchema>(
        `/api/v1/knowledge-bases/${detailQuery.data.value?.knowledgeBaseId}/metadata-schema`,
      ),
    enabled: Boolean(detailQuery.data.value?.knowledgeBaseId),
  })),
)

const diffQuery = useQuery(
  computed(() => ({
    queryKey: ['version-diff', props.documentId, compareVersionId.value, selectedVersionId.value],
    queryFn: () =>
      api.get<VersionDiff>(
        `/api/v1/documents/${props.documentId}/version-diff?fromVersionId=${compareVersionId.value}&toVersionId=${selectedVersionId.value}`,
      ),
    enabled:
      Boolean(props.documentId) &&
      Boolean(compareVersionId.value) &&
      Boolean(selectedVersionId.value) &&
      compareVersionId.value !== selectedVersionId.value,
  })),
)

const ingestionQuery = useQuery(
  computed(() => ({
    queryKey: ['ingestion-job', selectedVersion.value?.ingestionJobId],
    queryFn: () =>
      api.get<IngestionJob>(
        `/api/v1/ingestion-jobs/${selectedVersion.value?.ingestionJobId}`,
      ),
    enabled: Boolean(selectedVersion.value?.ingestionJobId),
  })),
)

const formattedMetadata = computed(() => {
  const raw = selectedVersion.value?.metadata
  if (!raw) return '{}'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

const metadataMutation = useMutation({
  mutationFn: () => {
    if (!selectedVersion.value) throw new Error('未选择文档版本')
    const metadata: Record<string, unknown> = {}
    for (const field of schemaQuery.data.value?.fields ?? []) {
      const value = metadataValue(field)
      if (value === undefined) {
        if (field.required) throw new Error(`${field.label} 为必填字段`)
        continue
      }
      metadata[field.key] = value
    }
    return api.patch<DocumentMetadataRevision>(
      `/api/v1/document-versions/${selectedVersion.value.id}/metadata`,
      {
        metadata,
        validFrom: metadataValidFrom.value ? new Date(metadataValidFrom.value).toISOString() : null,
        validTo: metadataValidTo.value ? new Date(metadataValidTo.value).toISOString() : null,
      },
    )
  },
  onSuccess: async () => {
    metadataEditing.value = false
    statusError.value = ''
    await Promise.all([
      detailQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: ['documents', detailQuery.data.value?.knowledgeBaseId] }),
    ])
    emit('statusChanged')
  },
  onError: (error) => {
    statusError.value = readableError(error)
  },
})

function metadataValue(field: MetadataFieldDefinition) {
  const raw = metadataValues.value[field.key]?.trim() ?? ''
  if (!raw) return undefined
  if (field.type === 'NUMBER') return Number(raw)
  if (field.type === 'BOOLEAN') return raw === 'true'
  if (field.type === 'TEXT_LIST') return raw.split(',').map((value) => value.trim()).filter(Boolean)
  if (field.type === 'DATETIME') return new Date(raw).toISOString()
  return raw
}

function asLocalDateTime(value: string | null) {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function startMetadataEdit() {
  const version = selectedVersion.value
  if (!version) return
  let existing: Record<string, unknown> = {}
  try {
    existing = JSON.parse(version.metadata || '{}') as Record<string, unknown>
  } catch {
    existing = {}
  }
  metadataValues.value = Object.fromEntries(
    (schemaQuery.data.value?.fields ?? []).map((field) => {
      const value = existing[field.key]
      return [field.key, Array.isArray(value) ? value.join(', ') : value == null ? '' : String(value)]
    }),
  )
  metadataValidFrom.value = asLocalDateTime(version.validFrom)
  metadataValidTo.value = asLocalDateTime(version.validTo)
  metadataEditing.value = true
}

watch(selectedVersionId, (versionId) => {
  if (compareVersionId.value === versionId) {
    compareVersionId.value = detailQuery.data.value?.versions.find((version) => version.id !== versionId)?.id ?? ''
  }
  metadataEditing.value = false
})

async function toggleStatus() {
  const detail = detailQuery.data.value
  if (!detail) return
  changingStatus.value = true
  statusError.value = ''
  try {
    await api.patch<void>(`/api/v1/documents/${detail.id}/status`, {
      status: detail.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
    })
    await detailQuery.refetch()
    emit('statusChanged')
  } catch (error) {
    statusError.value = readableError(error)
  } finally {
    changingStatus.value = false
  }
}
</script>

<template>
  <ModalDialog
    :open="Boolean(documentId)"
    :title="detailQuery.data.value?.title || '文档详情'"
    description="版本与已生成分块"
    width-class="max-w-5xl"
    @close="emit('close')"
  >
    <div v-if="detailQuery.isPending.value" class="space-y-4 py-4">
      <div class="h-10 animate-pulse rounded-md bg-paper-200" />
      <div class="h-32 animate-pulse rounded-md bg-paper-200" />
    </div>

    <ErrorState
      v-else-if="detailQuery.isError.value"
      :message="readableError(detailQuery.error.value)"
      @retry="detailQuery.refetch()"
    />

    <template v-else-if="detailQuery.data.value">
      <div class="grid gap-5 border-b border-paper-200 pb-5 sm:grid-cols-[1fr_auto]">
        <div class="flex items-start gap-3">
          <FileText :size="20" class="mt-0.5 shrink-0 text-ink-400" aria-hidden="true" />
          <div>
            <div class="flex flex-wrap items-center gap-2">
              <StatusPill :status="detailQuery.data.value.status" />
              <span class="text-xs text-ink-400">
                更新于 {{ formatDate(detailQuery.data.value.updatedAt) }}
              </span>
            </div>
            <p class="mt-3 text-sm text-ink-600">
              共 {{ detailQuery.data.value.versions.length }} 个版本
            </p>
            <div v-if="auth.canEdit" class="mt-4 flex flex-wrap gap-2">
              <button
                type="button"
                class="button-secondary"
                @click="emit('newVersion', detailQuery.data.value.id)"
              >
                <Upload :size="16" aria-hidden="true" />
                上传新版本
              </button>
              <button
                type="button"
                class="button-secondary"
                :disabled="changingStatus"
                @click="toggleStatus"
              >
                <LoaderCircle
                  v-if="changingStatus"
                  :size="16"
                  class="animate-spin"
                  aria-hidden="true"
                />
                <Power v-else :size="16" aria-hidden="true" />
                {{ detailQuery.data.value.status === 'ACTIVE' ? '停止检索' : '恢复检索' }}
              </button>
            </div>
          </div>
        </div>

        <label class="min-w-48 text-sm font-medium text-ink-800">
          查看版本
          <select v-model="selectedVersionId" class="control mt-2">
            <option
              v-for="version in detailQuery.data.value.versions"
              :key="version.id"
              :value="version.id"
            >
              v{{ version.versionNumber }} · {{ version.status }}
            </option>
          </select>
        </label>
      </div>

      <section class="border-b border-paper-200 py-5">
        <div class="flex flex-wrap items-start justify-between gap-4">
          <div class="flex min-w-0 items-start gap-3">
            <ShieldCheck :size="19" class="mt-0.5 shrink-0 text-ink-400" aria-hidden="true" />
            <div class="min-w-0">
              <h3 class="text-sm font-semibold">文档权限</h3>
              <p class="mt-1 text-xs leading-5 text-ink-500">
                <template v-if="detailQuery.data.value.accessPolicy.mode === 'ORGANIZATION'">
                  组织内成员可检索和查看
                </template>
                <template v-else-if="detailQuery.data.value.accessPolicy.allowedRoles.length || detailQuery.data.value.accessPolicy.allowedUserIds.length">
                  仅授权角色、成员与管理员可访问
                </template>
                <template v-else>仅管理员可访问</template>
              </p>
            </div>
          </div>
          <div class="flex items-center gap-2">
            <span class="inline-flex items-center gap-1.5 border border-paper-300 px-2.5 py-1 text-xs text-ink-600">
              <UsersRound
                v-if="detailQuery.data.value.accessPolicy.mode === 'ORGANIZATION'"
                :size="13"
                aria-hidden="true"
              />
              <LockKeyhole v-else :size="13" aria-hidden="true" />
              {{ detailQuery.data.value.accessPolicy.mode === 'ORGANIZATION' ? '组织可见' : '受限可见' }}
            </span>
            <button
              v-if="auth.isAdmin && !accessEditing"
              type="button"
              class="button-secondary min-h-8 px-3 py-1 text-xs"
              @click="startAccessEdit"
            >
              <Pencil :size="14" aria-hidden="true" />
              编辑
            </button>
          </div>
        </div>

        <template v-if="accessEditing">
          <div class="mt-4 inline-grid grid-cols-2 border border-paper-300 p-0.5" role="group" aria-label="文档可见范围">
            <button
              type="button"
              class="min-h-9 px-4 text-xs font-medium transition-colors"
              :class="accessMode === 'ORGANIZATION' ? 'bg-ink-900 text-white' : 'text-ink-600 hover:bg-paper-100'"
              :aria-pressed="accessMode === 'ORGANIZATION'"
              @click="accessMode = 'ORGANIZATION'"
            >
              组织可见
            </button>
            <button
              type="button"
              class="min-h-9 px-4 text-xs font-medium transition-colors"
              :class="accessMode === 'RESTRICTED' ? 'bg-ink-900 text-white' : 'text-ink-600 hover:bg-paper-100'"
              :aria-pressed="accessMode === 'RESTRICTED'"
              @click="accessMode = 'RESTRICTED'"
            >
              受限可见
            </button>
          </div>

          <div v-if="accessMode === 'RESTRICTED'" class="mt-5 grid gap-5 sm:grid-cols-2">
            <fieldset>
              <legend class="section-label">按角色授权</legend>
              <div class="mt-3 space-y-2">
                <label
                  v-for="option in accessRoleOptions"
                  :key="option.role"
                  class="flex min-h-10 cursor-pointer items-center gap-3 border border-paper-200 px-3 text-sm text-ink-700"
                >
                  <input
                    type="checkbox"
                    class="h-4 w-4 accent-brand-700"
                    :checked="allowedRoles.includes(option.role)"
                    @change="toggleRole(option.role)"
                  />
                  {{ option.label }}
                </label>
              </div>
            </fieldset>

            <fieldset>
              <legend class="section-label">按成员授权</legend>
              <div class="mt-3 max-h-44 divide-y divide-paper-200 overflow-y-auto border-y border-paper-200 scrollbar-subtle">
                <label
                  v-for="member in membersQuery.data.value?.filter((value) => value.role !== 'ADMIN')"
                  :key="member.id"
                  class="flex min-h-11 cursor-pointer items-center gap-3 px-1 text-sm"
                  :class="member.enabled ? 'text-ink-700' : 'text-ink-400'"
                >
                  <input
                    type="checkbox"
                    class="h-4 w-4 accent-brand-700"
                    :checked="allowedUserIds.includes(member.id)"
                    @change="toggleMember(member.id)"
                  />
                  <span class="min-w-0 flex-1 truncate">{{ member.displayName }}</span>
                  <span class="text-xs text-ink-400">{{ member.enabled ? member.username : '已停用' }}</span>
                </label>
                <p
                  v-if="!membersQuery.isPending.value && !membersQuery.data.value?.some((value) => value.role !== 'ADMIN')"
                  class="py-3 text-xs text-ink-400"
                >
                  暂无可授权成员
                </p>
              </div>
            </fieldset>
          </div>

          <p v-if="accessError" class="mt-4 bg-coral-50 px-3 py-2 text-sm text-coral-700">
            {{ accessError }}
          </p>
          <div class="mt-4 flex justify-end gap-2">
            <button
              type="button"
              class="button-secondary min-h-9 px-3"
              @click="accessEditing = false; accessError = ''"
            >
              <X :size="15" aria-hidden="true" />
              取消
            </button>
            <button
              type="button"
              class="button-primary min-h-9 px-3"
              :disabled="accessMutation.isPending.value"
              @click="accessMutation.mutate()"
            >
              <LoaderCircle v-if="accessMutation.isPending.value" :size="15" class="animate-spin" aria-hidden="true" />
              <Save v-else :size="15" aria-hidden="true" />
              保存权限
            </button>
          </div>
        </template>
      </section>

      <p v-if="statusError" class="mt-4 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
        {{ statusError }}
      </p>

      <div v-if="selectedVersion" class="grid gap-4 border-b border-paper-200 py-5 text-sm sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <p class="section-label">来源文件</p>
          <p class="mt-2 break-all text-ink-800">{{ selectedVersion.sourceName }}</p>
        </div>
        <div>
          <p class="section-label">版本状态</p>
          <div class="mt-2"><StatusPill :status="selectedVersion.status" /></div>
        </div>
        <div>
          <p class="section-label">有效期</p>
          <p class="mt-2 text-ink-800">
            {{ formatDate(selectedVersion.validFrom) }} 至 {{ formatDate(selectedVersion.validTo) }}
          </p>
        </div>
        <div>
          <p class="section-label">来源类型</p>
          <p class="mt-2 text-ink-800">{{ selectedVersion.sourceType || '未知' }}</p>
        </div>
      </div>

      <section v-if="selectedVersion" class="border-b border-paper-200 py-5">
        <div class="flex items-center justify-between gap-4">
          <div class="flex items-center gap-2">
            <Braces :size="18" class="text-ink-400" aria-hidden="true" />
            <h3 class="text-sm font-semibold">Metadata</h3>
          </div>
          <button
            v-if="auth.canEdit && selectedVersion.id === detailQuery.data.value.currentVersionId && selectedVersion.status === 'PUBLISHED' && !metadataEditing"
            type="button"
            class="button-secondary min-h-8 px-3 py-1 text-xs"
            @click="startMetadataEdit"
          >
            <Pencil :size="14" aria-hidden="true" />
            编辑
          </button>
        </div>
        <template v-if="metadataEditing">
          <div class="mt-4 grid gap-4 sm:grid-cols-2">
            <label class="block text-sm font-medium text-ink-800">
              生效时间
              <input v-model="metadataValidFrom" type="datetime-local" class="control mt-2" />
            </label>
            <label class="block text-sm font-medium text-ink-800">
              失效时间
              <input v-model="metadataValidTo" type="datetime-local" class="control mt-2" />
            </label>
            <label
              v-for="field in schemaQuery.data.value?.fields"
              :key="field.key"
              class="block text-sm font-medium text-ink-800"
            >
              {{ field.label }}<span v-if="field.required" class="text-coral-700"> *</span>
              <select
                v-if="field.type === 'BOOLEAN'"
                v-model="metadataValues[field.key]"
                class="control mt-2"
                :required="field.required"
              >
                <option value="">未设置</option>
                <option value="true">是</option>
                <option value="false">否</option>
              </select>
              <select
                v-else-if="field.allowedValues.length && field.type !== 'TEXT_LIST'"
                v-model="metadataValues[field.key]"
                class="control mt-2"
                :required="field.required"
              >
                <option value="">请选择</option>
                <option v-for="value in field.allowedValues" :key="value" :value="value">{{ value }}</option>
              </select>
              <input
                v-else
                v-model="metadataValues[field.key]"
                class="control mt-2"
                :type="field.type === 'NUMBER' ? 'number' : field.type === 'DATE' ? 'date' : field.type === 'DATETIME' ? 'datetime-local' : 'text'"
                :placeholder="field.type === 'TEXT_LIST' ? '多个值用逗号分隔' : undefined"
                :required="field.required"
              />
            </label>
          </div>
          <div class="mt-4 flex justify-end gap-2">
            <button type="button" class="button-secondary min-h-9 px-3" @click="metadataEditing = false">
              <X :size="15" aria-hidden="true" />
              取消
            </button>
            <button
              type="button"
              class="button-primary min-h-9 px-3"
              :disabled="metadataMutation.isPending.value"
              @click="metadataMutation.mutate()"
            >
              <LoaderCircle v-if="metadataMutation.isPending.value" :size="15" class="animate-spin" aria-hidden="true" />
              <Save v-else :size="15" aria-hidden="true" />
              保存且不重建向量
            </button>
          </div>
        </template>
        <pre
          v-else
          class="mt-4 max-h-44 overflow-auto whitespace-pre-wrap break-words bg-paper-100 px-4 py-3 text-xs leading-6 text-ink-700 scrollbar-subtle"
        >{{ formattedMetadata }}</pre>
      </section>

      <section v-if="detailQuery.data.value.versions.length > 1" class="border-b border-paper-200 py-5">
        <div class="flex flex-wrap items-center justify-between gap-4">
          <div class="flex items-center gap-2">
            <GitCompare :size="18" class="text-ink-400" aria-hidden="true" />
            <h3 class="text-sm font-semibold">版本差异</h3>
          </div>
          <label class="flex items-center gap-2 text-xs text-ink-600">
            对比基线
            <select v-model="compareVersionId" class="control h-9 w-44 py-1.5 text-xs">
              <option
                v-for="version in detailQuery.data.value.versions.filter((item) => item.id !== selectedVersionId)"
                :key="version.id"
                :value="version.id"
              >
                v{{ version.versionNumber }} · {{ version.status }}
              </option>
            </select>
          </label>
        </div>

        <div v-if="diffQuery.isPending.value" class="mt-4 h-20 animate-pulse bg-paper-100" />
        <ErrorState
          v-else-if="diffQuery.isError.value"
          class="mt-4"
          :message="readableError(diffQuery.error.value)"
          @retry="diffQuery.refetch()"
        />
        <template v-else-if="diffQuery.data.value">
          <div class="mt-4 grid grid-cols-2 divide-x divide-y divide-paper-200 border border-paper-200 sm:grid-cols-4 sm:divide-y-0">
            <div class="p-3 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.addedBlocks }}</p><p class="mt-1 text-xs text-brand-700">新增</p></div>
            <div class="p-3 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.modifiedBlocks }}</p><p class="mt-1 text-xs text-amber-700">修改</p></div>
            <div class="p-3 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.removedBlocks }}</p><p class="mt-1 text-xs text-coral-700">删除</p></div>
            <div class="p-3 text-center"><p class="text-lg font-semibold">{{ diffQuery.data.value.unchangedBlocks }}</p><p class="mt-1 text-xs text-ink-400">未变化</p></div>
          </div>
          <div v-if="diffQuery.data.value.metadataChanged || diffQuery.data.value.validityChanged" class="mt-3 flex flex-wrap gap-2 text-xs">
            <span v-if="diffQuery.data.value.metadataChanged" class="rounded bg-brand-50 px-2 py-1 text-brand-700">Metadata 已变化</span>
            <span v-if="diffQuery.data.value.validityChanged" class="rounded bg-amber-100 px-2 py-1 text-amber-700">有效期已变化</span>
          </div>
          <div v-if="diffQuery.data.value.entries.length" class="mt-4 max-h-72 divide-y divide-paper-200 overflow-y-auto border-y border-paper-200 scrollbar-subtle">
            <details v-for="entry in diffQuery.data.value.entries" :key="`${entry.changeType}-${entry.orderIndex}`" class="py-3">
              <summary class="cursor-pointer list-none text-sm font-medium">
                <span :class="entry.changeType === 'ADDED' ? 'text-brand-700' : entry.changeType === 'REMOVED' ? 'text-coral-700' : 'text-amber-700'">
                  {{ entry.changeType }}
                </span>
                <span class="ml-2 text-ink-400">Block #{{ entry.orderIndex + 1 }}</span>
              </summary>
              <div class="mt-3 grid gap-3 sm:grid-cols-2">
                <pre v-if="entry.beforeText" class="max-h-40 overflow-auto whitespace-pre-wrap bg-coral-50 p-3 text-xs leading-5 text-ink-700">{{ entry.beforeText }}</pre>
                <pre v-if="entry.afterText" class="max-h-40 overflow-auto whitespace-pre-wrap bg-brand-50 p-3 text-xs leading-5 text-ink-700">{{ entry.afterText }}</pre>
              </div>
            </details>
          </div>
        </template>
      </section>

      <section v-if="selectedVersion?.ingestionJobId" class="border-b border-paper-200 py-5">
        <div class="flex items-center justify-between gap-4">
          <div class="flex items-center gap-2">
            <ListChecks :size="18" class="text-ink-400" aria-hidden="true" />
            <h3 class="text-sm font-semibold">处理时间线</h3>
          </div>
          <StatusPill
            :status="ingestionQuery.data.value?.status || selectedVersion.ingestionStatus"
          />
        </div>

        <div v-if="ingestionQuery.isPending.value" class="mt-4 space-y-2">
          <div v-for="index in 3" :key="index" class="h-10 animate-pulse bg-paper-100" />
        </div>
        <ErrorState
          v-else-if="ingestionQuery.isError.value"
          class="mt-4"
          :message="readableError(ingestionQuery.error.value)"
          @retry="ingestionQuery.refetch()"
        />
        <div v-else class="mt-4 divide-y divide-paper-200 border-y border-paper-200">
          <div
            v-for="stage in ingestionQuery.data.value?.stages"
            :key="stage.stage"
            class="grid gap-2 py-3 text-sm sm:grid-cols-[110px_100px_minmax(0,1fr)_auto] sm:items-center"
          >
            <span class="font-medium">{{ stage.stage }}</span>
            <StatusPill :status="stage.status" />
            <span class="truncate text-xs text-ink-400">{{ stage.metrics }}</span>
            <span class="text-xs text-ink-400">{{ formatDate(stage.completedAt) }}</span>
          </div>
        </div>
      </section>

      <div class="pt-5">
        <div class="mb-4 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <Layers3 :size="18" class="text-ink-400" aria-hidden="true" />
            <h3 class="text-sm font-semibold">分块</h3>
          </div>
          <span class="text-xs text-ink-400">{{ chunksQuery.data.value?.length || 0 }} 条</span>
        </div>

        <div
          v-if="initialChunkId && (initialPageNumber != null || initialSourceStart != null)"
          class="mb-3 border-l-2 border-brand-600 bg-brand-50 px-3 py-2 text-xs leading-5 text-brand-700"
        >
          引用定位
          <span v-if="initialPageNumber != null"> · 第 {{ initialPageNumber }} 页</span>
          <span v-if="initialSourceStart != null">
            · 规范化原文 {{ initialSourceStart }}–{{ initialSourceEnd ?? '?' }}
          </span>
        </div>

        <div v-if="chunksQuery.isPending.value" class="space-y-3">
          <div v-for="index in 3" :key="index" class="h-20 animate-pulse bg-paper-100" />
        </div>
        <ErrorState
          v-else-if="chunksQuery.isError.value"
          :message="readableError(chunksQuery.error.value)"
          @retry="chunksQuery.refetch()"
        />
        <EmptyState
          v-else-if="!chunksQuery.data.value?.length"
          :icon="AlignLeft"
          title="这个版本还没有分块"
          description="文档可能仍在入库处理中，完成后再刷新查看。"
        />
        <div v-else class="max-h-[42dvh] divide-y divide-paper-200 overflow-y-auto scrollbar-subtle">
          <article
            v-for="chunk in chunksQuery.data.value"
            :id="`chunk-${chunk.id}`"
            :key="chunk.id"
            class="border-l-2 py-4 pl-3 transition-colors"
            :class="chunk.id === initialChunkId ? 'border-brand-600 bg-brand-50' : 'border-transparent'"
          >
            <div class="mb-2 flex flex-wrap items-center gap-2 text-xs text-ink-400">
              <span>{{ chunk.type }}</span>
              <span>#{{ chunk.orderIndex + 1 }}</span>
              <span>{{ chunk.estimatedTokens }} tokens</span>
            </div>
            <p class="whitespace-pre-wrap break-words text-sm leading-6 text-ink-800">
              {{ chunk.text }}
            </p>
          </article>
        </div>
      </div>
    </template>
  </ModalDialog>
</template>
