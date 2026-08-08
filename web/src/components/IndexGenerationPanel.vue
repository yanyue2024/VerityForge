<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { DatabaseZap, LoaderCircle, RefreshCw, RotateCcw } from 'lucide-vue-next'
import ErrorState from '@/components/ErrorState.vue'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { useAuthStore } from '@/stores/auth'
import type { IndexGeneration } from '@/types/api'

const props = defineProps<{ knowledgeBaseId: string }>()
const queryClient = useQueryClient()
const auth = useAuthStore()
const actionError = ref('')

const generationsQuery = useQuery(
  computed(() => ({
    queryKey: ['index-generations', props.knowledgeBaseId],
    queryFn: () =>
      api.get<IndexGeneration[]>(
        `/api/v1/knowledge-bases/${props.knowledgeBaseId}/index-generations`,
      ),
    refetchInterval: (query: { state: { data?: IndexGeneration[] } }) =>
      query.state.data?.some(
        (generation) =>
          generation.status === 'BUILDING' ||
          ['QUEUED', 'RUNNING'].includes(generation.rebuildJob?.status ?? ''),
      )
        ? 2_500
        : false,
  })),
)

const activeGeneration = computed(() =>
  generationsQuery.data.value?.find((generation) => generation.status === 'ACTIVE'),
)

const rebuilding = computed(() =>
  generationsQuery.data.value?.some(
    (generation) =>
      generation.status === 'BUILDING' ||
      ['QUEUED', 'RUNNING'].includes(generation.rebuildJob?.status ?? ''),
  ),
)

const rebuildMutation = useMutation({
  mutationFn: () => {
    const profileId = activeGeneration.value?.embeddingProfileId
    if (!profileId) throw new Error('当前代际没有可复用的 Embedding Profile')
    return api.post<IndexGeneration>(
      `/api/v1/knowledge-bases/${props.knowledgeBaseId}/index-generations/reindex`,
      { embeddingProfileId: profileId },
    )
  },
  onSuccess: async () => {
    actionError.value = ''
    await queryClient.invalidateQueries({ queryKey: ['index-generations', props.knowledgeBaseId] })
  },
  onError: (error) => {
    actionError.value = readableError(error)
  },
})

const activateMutation = useMutation({
  mutationFn: (generationId: string) =>
    api.post<IndexGeneration>(
      `/api/v1/knowledge-bases/${props.knowledgeBaseId}/index-generations/${generationId}/activate`,
    ),
  onSuccess: async () => {
    actionError.value = ''
    await queryClient.invalidateQueries({ queryKey: ['index-generations', props.knowledgeBaseId] })
  },
  onError: (error) => {
    actionError.value = readableError(error)
  },
})

function progress(generation: IndexGeneration) {
  const job = generation.rebuildJob
  if (!job?.totalChunks) return 0
  return Math.min(100, Math.round((job.completedChunks / job.totalChunks) * 100))
}
</script>

<template>
  <section class="border-b border-paper-200 py-6">
    <div class="flex flex-wrap items-start justify-between gap-4">
      <div class="flex items-start gap-3">
        <DatabaseZap :size="19" class="mt-0.5 text-amber-700" aria-hidden="true" />
        <div>
          <h2 class="text-sm font-semibold">索引代际</h2>
          <p class="mt-1 text-xs leading-5 text-ink-400">
            当前 Gen {{ activeGeneration?.generationNumber ?? '—' }} ·
            {{ activeGeneration?.embeddingDimension ?? '—' }} 维 ·
            {{ activeGeneration?.vectorCount ?? 0 }} 个向量
          </p>
        </div>
      </div>
      <button
        v-if="auth.canEdit"
        type="button"
        class="button-secondary min-h-9 px-3"
        :disabled="rebuilding || rebuildMutation.isPending.value || !activeGeneration?.embeddingProfileId"
        @click="rebuildMutation.mutate()"
      >
        <LoaderCircle
          v-if="rebuilding || rebuildMutation.isPending.value"
          :size="16"
          class="animate-spin"
          aria-hidden="true"
        />
        <RefreshCw v-else :size="16" aria-hidden="true" />
        按当前模型重建
      </button>
    </div>

    <ErrorState
      v-if="generationsQuery.isError.value"
      class="mt-5"
      :message="readableError(generationsQuery.error.value)"
      @retry="generationsQuery.refetch()"
    />
    <div v-else-if="generationsQuery.isPending.value" class="mt-5 h-24 animate-pulse bg-paper-100" />
    <div v-else class="mt-5 overflow-x-auto border-y border-paper-200">
      <table class="w-full min-w-[780px] text-left text-sm">
        <thead class="bg-paper-100 text-xs text-ink-400">
          <tr>
            <th class="px-3 py-2.5 font-medium">代际</th>
            <th class="px-3 py-2.5 font-medium">状态</th>
            <th class="px-3 py-2.5 font-medium">模型</th>
            <th class="px-3 py-2.5 font-medium">策略</th>
            <th class="px-3 py-2.5 font-medium">向量 / 进度</th>
            <th class="px-3 py-2.5 font-medium">激活时间</th>
            <th class="w-24 px-3 py-2.5" />
          </tr>
        </thead>
        <tbody class="divide-y divide-paper-200">
          <tr v-for="generation in generationsQuery.data.value" :key="generation.id">
            <td class="px-3 py-3 font-semibold">Gen {{ generation.generationNumber }}</td>
            <td class="px-3 py-3"><StatusPill :status="generation.status" /></td>
            <td class="px-3 py-3">
              <p class="font-medium text-ink-800">{{ generation.embeddingModelId }}</p>
              <p class="mt-1 text-xs text-ink-400">
                {{ generation.embeddingDimension }} 维 · {{ generation.embeddingModelVersion }}
              </p>
            </td>
            <td class="px-3 py-3 text-xs text-ink-600">{{ generation.chunkPolicyVersion }}</td>
            <td class="px-3 py-3">
              <template v-if="generation.rebuildJob && ['QUEUED', 'RUNNING'].includes(generation.rebuildJob.status)">
                <div class="flex items-center justify-between gap-3 text-xs">
                  <span>{{ generation.rebuildJob.completedChunks }}/{{ generation.rebuildJob.totalChunks }}</span>
                  <span class="text-ink-400">
                    尝试 {{ generation.rebuildJob.attempt }}/{{ generation.rebuildJob.maxAttempts }} ·
                    复用 {{ generation.rebuildJob.reusedChunks }}
                  </span>
                </div>
                <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-paper-200">
                  <div class="h-full bg-brand-600" :style="{ width: `${progress(generation)}%` }" />
                </div>
                <p
                  v-if="generation.rebuildJob.status === 'QUEUED' && generation.rebuildJob.errorMessage"
                  class="mt-2 max-w-[28rem] text-xs leading-5 text-coral-700"
                >
                  {{ generation.rebuildJob.errorMessage }} · {{ formatDate(generation.rebuildJob.nextAttemptAt) }} 重试
                </p>
              </template>
              <span v-else>{{ generation.vectorCount }}</span>
            </td>
            <td class="px-3 py-3 text-xs text-ink-400">{{ formatDate(generation.activatedAt) }}</td>
            <td class="px-3 py-3 text-right">
              <button
                v-if="auth.canEdit && generation.status === 'RETIRED'"
                type="button"
                class="icon-button ml-auto size-8"
                title="回滚并激活该代际"
                :disabled="rebuilding || activateMutation.isPending.value"
                @click="activateMutation.mutate(generation.id)"
              >
                <RotateCcw :size="15" aria-hidden="true" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="actionError" class="mt-4 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
      {{ actionError }}
    </p>
  </section>
</template>
