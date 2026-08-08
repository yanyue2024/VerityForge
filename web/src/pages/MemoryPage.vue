<script setup lang="ts">
import { ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { Ban, BrainCircuit, Check, LoaderCircle, Plus, Trash2 } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import type { MemoryFact } from '@/types/api'

const queryClient = useQueryClient()
const createOpen = ref(false)
const factText = ref('')
const confidence = ref(0.9)
const validFrom = ref('')
const validTo = ref('')
const actionError = ref('')

const factsQuery = useQuery({
  queryKey: ['memory-facts'],
  queryFn: () => api.get<MemoryFact[]>('/api/v1/memory-facts'),
})

const createMutation = useMutation({
  mutationFn: () =>
    api.post<MemoryFact>('/api/v1/memory-facts', {
      factText: factText.value.trim(),
      confidence: confidence.value,
      sourceMessageId: null,
      validFrom: validFrom.value ? new Date(validFrom.value).toISOString() : null,
      validTo: validTo.value ? new Date(validTo.value).toISOString() : null,
    }),
  onSuccess: async () => {
    createOpen.value = false
    factText.value = ''
    confidence.value = 0.9
    validFrom.value = ''
    validTo.value = ''
    await queryClient.invalidateQueries({ queryKey: ['memory-facts'] })
  },
})

const updateMutation = useMutation({
  mutationFn: ({ fact, status }: { fact: MemoryFact; status: MemoryFact['status'] }) =>
    api.patch<MemoryFact>(`/api/v1/memory-facts/${fact.id}`, {
      status,
      validTo: fact.validTo,
    }),
  onSuccess: async () => {
    actionError.value = ''
    await queryClient.invalidateQueries({ queryKey: ['memory-facts'] })
  },
  onError: (error) => {
    actionError.value = readableError(error)
  },
})

const deleteMutation = useMutation({
  mutationFn: (factId: string) => api.delete<void>(`/api/v1/memory-facts/${factId}`),
  onSuccess: async () => {
    actionError.value = ''
    await queryClient.invalidateQueries({ queryKey: ['memory-facts'] })
  },
  onError: (error) => {
    actionError.value = readableError(error)
  },
})
</script>

<template>
  <div class="mx-auto w-full max-w-6xl px-4 py-7 sm:px-7 sm:py-10 lg:px-10">
    <header class="flex flex-col gap-5 border-b border-paper-200 pb-7 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="section-label">Personal memory</p>
        <h1 class="page-title mt-2">长期记忆</h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-ink-600">
          管理个人表达偏好与稳定上下文。只有已确认且在有效期内的记忆会参与个性化，不会作为知识证据。
        </p>
      </div>
      <button type="button" class="button-primary self-start sm:self-auto" @click="createOpen = true">
        <Plus :size="17" aria-hidden="true" />
        新增记忆
      </button>
    </header>

    <ErrorState
      v-if="factsQuery.isError.value"
      class="mt-6"
      :message="readableError(factsQuery.error.value)"
      @retry="factsQuery.refetch()"
    />
    <div v-else-if="factsQuery.isPending.value" class="divide-y divide-paper-200">
      <div v-for="index in 4" :key="index" class="h-24 animate-pulse bg-paper-100" />
    </div>
    <EmptyState
      v-else-if="!factsQuery.data.value?.length"
      :icon="BrainCircuit"
      title="还没有长期记忆"
      description="新增后先处于待确认状态，由你决定是否允许它参与后续回答的个性化。"
    />
    <div v-else class="border-b border-paper-200">
      <div
        class="hidden grid-cols-[minmax(0,1fr)_7rem_6rem_11rem_8rem] border-b border-paper-200 bg-paper-100 px-3 py-3 text-xs text-ink-400 lg:grid"
      >
        <span>记忆内容</span>
        <span>状态</span>
        <span>置信度</span>
        <span>有效期</span>
        <span />
      </div>
      <div class="divide-y divide-paper-200">
        <article
          v-for="fact in factsQuery.data.value"
          :key="fact.id"
          class="grid grid-cols-2 gap-x-3 gap-y-4 py-5 lg:grid-cols-[minmax(0,1fr)_7rem_6rem_11rem_8rem] lg:items-center lg:gap-0 lg:px-3 lg:py-4"
        >
          <div class="col-span-2 min-w-0 lg:col-span-1 lg:pr-4">
              <p class="break-words font-medium leading-6 text-ink-800">{{ fact.factText }}</p>
              <p class="mt-1 text-xs text-ink-400">更新于 {{ formatDate(fact.updatedAt) }}</p>
          </div>
          <div>
            <p class="mb-1 text-xs text-ink-400 lg:hidden">状态</p>
            <StatusPill :status="fact.status" />
          </div>
          <div>
            <p class="mb-1 text-xs text-ink-400 lg:hidden">置信度</p>
            <p class="text-sm text-ink-800">{{ Math.round(fact.confidence * 100) }}%</p>
          </div>
          <div class="col-span-2 text-xs leading-5 text-ink-600 lg:col-span-1">
            <p class="mb-1 text-ink-400 lg:hidden">有效期</p>
            {{ formatDate(fact.validFrom) }}<br class="hidden lg:block" /> 至 {{ formatDate(fact.validTo) }}
          </div>
          <div class="col-span-2 flex justify-end gap-1 border-t border-paper-100 pt-3 lg:col-span-1 lg:border-0 lg:pt-0">
                <button
                  v-if="fact.status !== 'CONFIRMED'"
                  type="button"
                  class="icon-button size-8 text-brand-700"
                  title="确认并启用"
                  @click="updateMutation.mutate({ fact, status: 'CONFIRMED' })"
                >
                  <Check :size="16" aria-hidden="true" />
                </button>
                <button
                  v-if="fact.status !== 'REJECTED'"
                  type="button"
                  class="icon-button size-8 text-amber-700"
                  title="拒绝使用"
                  @click="updateMutation.mutate({ fact, status: 'REJECTED' })"
                >
                  <Ban :size="16" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  class="icon-button size-8 text-coral-700"
                  title="删除记忆"
                  @click="deleteMutation.mutate(fact.id)"
                >
                  <Trash2 :size="16" aria-hidden="true" />
                </button>
          </div>
        </article>
      </div>
    </div>

    <p v-if="actionError" class="mt-4 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
      {{ actionError }}
    </p>

    <ModalDialog
      :open="createOpen"
      title="新增长期记忆"
      description="新记录不会立即生效，需要在列表中显式确认。"
      @close="createOpen = false"
    >
      <form class="space-y-5" @submit.prevent="createMutation.mutate()">
        <label class="block text-sm font-medium text-ink-800">
          记忆内容
          <textarea v-model="factText" class="control mt-2 min-h-28 resize-y" maxlength="2000" required />
        </label>
        <label class="block text-sm font-medium text-ink-800">
          <span class="flex items-center justify-between">
            <span>置信度</span>
            <span class="text-xs text-ink-400">{{ Math.round(confidence * 100) }}%</span>
          </span>
          <input v-model.number="confidence" type="range" min="0" max="1" step="0.01" class="mt-3 w-full accent-brand-700" />
        </label>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="block text-sm font-medium text-ink-800">
            生效时间
            <input v-model="validFrom" type="datetime-local" class="control mt-2" />
          </label>
          <label class="block text-sm font-medium text-ink-800">
            失效时间
            <input v-model="validTo" type="datetime-local" class="control mt-2" />
          </label>
        </div>
        <p v-if="createMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
          {{ readableError(createMutation.error.value) }}
        </p>
        <div class="flex justify-end gap-3">
          <button type="button" class="button-secondary" @click="createOpen = false">取消</button>
          <button type="submit" class="button-primary" :disabled="createMutation.isPending.value || !factText.trim()">
            <LoaderCircle v-if="createMutation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />
            保存为待确认
          </button>
        </div>
      </form>
    </ModalDialog>
  </div>
</template>
