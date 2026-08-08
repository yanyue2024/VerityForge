<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { CheckCircle2, ChevronDown, CircleAlert, LoaderCircle, RefreshCw, SearchCheck } from 'lucide-vue-next'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { useAuthStore } from '@/stores/auth'
import type { IndexGeneration } from '@/types/api'

const props = defineProps<{ knowledgeBaseId: string }>()
const auth = useAuthStore()
const queryClient = useQueryClient()
const root = ref<HTMLElement | null>(null)
const open = ref(false)
const actionError = ref('')

const generationsQuery = useQuery(
  computed(() => ({
    queryKey: ['index-generations', props.knowledgeBaseId],
    queryFn: () => api.get<IndexGeneration[]>(`/api/v1/knowledge-bases/${props.knowledgeBaseId}/index-generations`),
    refetchInterval: (query: { state: { data?: IndexGeneration[] } }) =>
      query.state.data?.some((item) => item.status === 'BUILDING' || ['QUEUED', 'RUNNING'].includes(item.rebuildJob?.status ?? ''))
        ? 2_500
        : false,
  })),
)

const active = computed(() => generationsQuery.data.value?.find((item) => item.status === 'ACTIVE'))
const building = computed(() => generationsQuery.data.value?.find((item) =>
  item.status === 'BUILDING' || ['QUEUED', 'RUNNING'].includes(item.rebuildJob?.status ?? ''),
))
const failed = computed(() => generationsQuery.data.value?.find((item) =>
  item.status === 'FAILED' || item.rebuildJob?.status === 'FAILED',
))
const state = computed(() => {
  if (generationsQuery.isError.value || (!active.value && failed.value)) return 'ERROR'
  if (building.value) return 'BUILDING'
  if (active.value) return 'READY'
  return 'EMPTY'
})
const label = computed(() => ({ READY: '检索可用', BUILDING: '正在构建', ERROR: '检索异常', EMPTY: '等待索引' }[state.value]))
const progress = computed(() => {
  const job = building.value?.rebuildJob
  if (!job?.totalChunks) return 0
  return Math.min(100, Math.round((job.completedChunks / job.totalChunks) * 100))
})

const rebuildMutation = useMutation({
  mutationFn: () => {
    const profileId = active.value?.embeddingProfileId
    if (!profileId) throw new Error('当前索引没有可复用的向量模型配置')
    return api.post<IndexGeneration>(`/api/v1/knowledge-bases/${props.knowledgeBaseId}/index-generations/reindex`, {
      embeddingProfileId: profileId,
    })
  },
  onSuccess: async () => {
    actionError.value = ''
    await queryClient.invalidateQueries({ queryKey: ['index-generations', props.knowledgeBaseId] })
  },
  onError: (error) => { actionError.value = readableError(error) },
})

function outside(event: PointerEvent) {
  if (open.value && root.value && !root.value.contains(event.target as Node)) open.value = false
}

onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>

<template>
  <div ref="root" class="relative">
    <button
      type="button"
      class="index-status-button"
      :class="`index-status-${state.toLowerCase()}`"
      :aria-expanded="open"
      @click="open = !open"
    >
      <LoaderCircle v-if="state === 'BUILDING'" :size="14" class="animate-spin" aria-hidden="true" />
      <CircleAlert v-else-if="state === 'ERROR'" :size="14" aria-hidden="true" />
      <CheckCircle2 v-else-if="state === 'READY'" :size="14" aria-hidden="true" />
      <SearchCheck v-else :size="14" aria-hidden="true" />
      {{ label }}
      <ChevronDown :size="13" class="transition-transform" :class="{ 'rotate-180': open }" aria-hidden="true" />
    </button>

    <div v-if="open" class="absolute right-0 top-10 z-40 w-[360px] overflow-hidden rounded-lg border border-paper-200 bg-white shadow-panel">
      <div class="border-b border-paper-200 px-5 py-4">
        <div class="flex items-center justify-between gap-3">
          <p class="text-sm font-semibold text-ink-950">知识库索引</p>
          <span class="text-xs font-medium" :class="state === 'ERROR' ? 'text-coral-700' : state === 'READY' ? 'text-evidence-700' : 'text-brand-700'">{{ label }}</span>
        </div>
        <p class="mt-1 text-xs text-ink-400">{{ active ? `Gen ${active.generationNumber} · ${active.vectorCount} 个向量` : '还没有可用索引' }}</p>
      </div>

      <div class="space-y-3 px-5 py-4 text-xs">
        <div class="flex items-center justify-between gap-4"><span class="text-ink-400">最近激活</span><span class="text-ink-700">{{ formatDate(active?.activatedAt) }}</span></div>
        <div class="flex items-center justify-between gap-4"><span class="text-ink-400">分块策略</span><span class="max-w-52 truncate text-ink-700">{{ active?.chunkPolicyVersion || '—' }}</span></div>
        <template v-if="building?.rebuildJob">
          <div class="pt-1">
            <div class="flex items-center justify-between text-ink-500"><span>构建进度</span><span>{{ building.rebuildJob.completedChunks }} / {{ building.rebuildJob.totalChunks }}</span></div>
            <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-paper-200"><div class="h-full bg-brand-600" :style="{ width: `${progress}%` }" /></div>
          </div>
        </template>
        <p v-if="generationsQuery.isError.value || failed?.rebuildJob?.errorMessage" class="rounded-md bg-coral-50 px-3 py-2 leading-5 text-coral-700">
          {{ generationsQuery.isError.value ? readableError(generationsQuery.error.value) : failed?.rebuildJob?.errorMessage }}
        </p>
        <p v-if="actionError" class="rounded-md bg-coral-50 px-3 py-2 leading-5 text-coral-700">{{ actionError }}</p>
      </div>

      <div v-if="auth.canEdit" class="flex justify-end border-t border-paper-200 bg-paper-50 px-5 py-3">
        <button
          type="button"
          class="button-secondary min-h-9 px-3 text-xs"
          :disabled="Boolean(building) || rebuildMutation.isPending.value || !active?.embeddingProfileId"
          @click="rebuildMutation.mutate()"
        >
          <LoaderCircle v-if="rebuildMutation.isPending.value" :size="14" class="animate-spin" aria-hidden="true" />
          <RefreshCw v-else :size="14" aria-hidden="true" />
          重新构建
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.index-status-button {
  display: inline-flex;
  height: 32px;
  align-items: center;
  gap: 6px;
  border: 1px solid #dce3ed;
  border-radius: 7px;
  background: #ffffff;
  padding: 0 10px;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
  transition: border-color 160ms ease, color 160ms ease, background-color 160ms ease;
}

.index-status-button:hover {
  border-color: #bfdbfe;
  color: #1d4ed8;
}

.index-status-ready {
  color: #14805e;
}

.index-status-error {
  border-color: #efc2c8;
  background: #fff7f8;
  color: #b64252;
}

.index-status-building {
  color: #1d4ed8;
}
</style>
