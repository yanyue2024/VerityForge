<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  CheckCircle2,
  KeyRound,
  LoaderCircle,
  RefreshCw,
  ShieldAlert,
  ShieldCheck,
} from 'lucide-vue-next'
import ErrorState from '@/components/ErrorState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import type { CredentialRotationStatus } from '@/types/api'

const queryClient = useQueryClient()
const confirmOpen = ref(false)

const statusQuery = useQuery({
  queryKey: ['credential-rotation'],
  queryFn: () => api.get<CredentialRotationStatus>('/api/v1/security/credential-rotation'),
})

const rotateMutation = useMutation({
  mutationFn: () => api.post<CredentialRotationStatus>('/api/v1/security/credential-rotation'),
  onSuccess: async (status) => {
    confirmOpen.value = false
    queryClient.setQueryData(['credential-rotation'], status)
    await queryClient.invalidateQueries({ queryKey: ['credential-rotation'] })
  },
})

const sourceRows = computed(() => {
  const labels: Record<string, string> = {
    MODEL_PROFILE: '模型服务密钥',
    EVALUATION_SCHEDULE: '评测通知配置',
    EVALUATION_DELIVERY: '待投递通知快照',
  }
  return Object.entries(statusQuery.data.value?.credentialsBySource ?? {})
    .map(([key, count]) => ({ key, label: labels[key] ?? key, count }))
    .sort((left, right) => left.label.localeCompare(right.label, 'zh-CN'))
})

const keyRows = computed(() =>
  Object.entries(statusQuery.data.value?.credentialsByKeyId ?? {})
    .map(([keyId, count]) => ({ keyId, count }))
    .sort((left, right) => left.keyId.localeCompare(right.keyId)),
)

const canRotate = computed(() => {
  const status = statusQuery.data.value
  return Boolean(status && status.needsRotation > 0 && status.unreadableCredentials === 0)
})
</script>

<template>
  <div class="mx-auto w-full max-w-7xl px-4 py-7 sm:px-7 sm:py-10 lg:px-10">
    <header class="flex flex-col gap-5 border-b border-paper-200 pb-7 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="section-label">Credential control</p>
        <h1 class="page-title mt-2">凭据安全</h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-ink-600">
          查看服务端加密状态并将历史密文重新封装到当前活动密钥。密钥内容仅存在于服务运行环境中。
        </p>
      </div>
      <button
        type="button"
        class="button-primary"
        :disabled="!canRotate"
        @click="confirmOpen = true"
      >
        <RefreshCw :size="17" aria-hidden="true" />
        重新加密
      </button>
    </header>

    <ErrorState
      v-if="statusQuery.isError.value"
      class="mt-6"
      :message="readableError(statusQuery.error.value)"
      @retry="statusQuery.refetch()"
    />
    <div v-else-if="statusQuery.isPending.value" class="mt-6 space-y-2">
      <div v-for="index in 4" :key="index" class="h-16 animate-pulse bg-paper-100" />
    </div>

    <template v-else-if="statusQuery.data.value">
      <section class="grid border-b border-paper-200 py-6 sm:grid-cols-2 lg:grid-cols-4">
        <div class="border-b border-paper-200 py-4 sm:border-r sm:px-5 sm:first:pl-0 lg:border-b-0">
          <p class="text-xs text-ink-400">活动 Key ID</p>
          <p class="mt-2 break-all font-mono text-lg font-semibold text-ink-950">
            {{ statusQuery.data.value.activeKeyId }}
          </p>
        </div>
        <div class="border-b border-paper-200 py-4 sm:px-5 lg:border-b-0 lg:border-r">
          <p class="text-xs text-ink-400">受保护凭据</p>
          <p class="mt-2 text-2xl font-semibold text-ink-950">
            {{ statusQuery.data.value.totalCredentials }}
          </p>
        </div>
        <div class="border-b border-paper-200 py-4 sm:border-b-0 sm:border-r sm:px-5">
          <p class="text-xs text-ink-400">等待轮换</p>
          <p class="mt-2 text-2xl font-semibold" :class="statusQuery.data.value.needsRotation ? 'text-amber-700' : 'text-ink-950'">
            {{ statusQuery.data.value.needsRotation }}
          </p>
        </div>
        <div class="py-4 sm:px-5">
          <p class="text-xs text-ink-400">不可读取</p>
          <p class="mt-2 text-2xl font-semibold" :class="statusQuery.data.value.unreadableCredentials ? 'text-coral-700' : 'text-ink-950'">
            {{ statusQuery.data.value.unreadableCredentials }}
          </p>
        </div>
      </section>

      <div
        v-if="statusQuery.data.value.unreadableCredentials > 0"
        class="mt-6 flex items-start gap-3 border-y border-coral-200 bg-coral-50 px-4 py-4 text-coral-700"
      >
        <ShieldAlert :size="20" class="mt-0.5 shrink-0" aria-hidden="true" />
        <div>
          <p class="text-sm font-semibold">轮换已锁定</p>
          <p class="mt-1 text-sm leading-6">当前 Keyring 无法读取部分密文。加载对应旧密钥后再执行重新加密。</p>
        </div>
      </div>
      <div
        v-else-if="statusQuery.data.value.needsRotation === 0"
        class="mt-6 flex items-center gap-3 border-y border-evidence-100 bg-evidence-50 px-4 py-4 text-evidence-700"
      >
        <CheckCircle2 :size="20" class="shrink-0" aria-hidden="true" />
        <p class="text-sm font-medium">全部凭据已使用活动密钥封装。</p>
      </div>

      <div class="mt-8 grid gap-10 lg:grid-cols-2">
        <section>
          <div class="flex items-center gap-2 border-b border-paper-200 pb-3">
            <ShieldCheck :size="18" class="text-brand-700" aria-hidden="true" />
            <h2 class="text-sm font-semibold text-ink-950">凭据位置</h2>
          </div>
          <div v-if="sourceRows.length" class="divide-y divide-paper-200">
            <div v-for="row in sourceRows" :key="row.key" class="flex items-center justify-between gap-4 py-4">
              <span class="text-sm text-ink-600">{{ row.label }}</span>
              <span class="font-mono text-sm font-semibold text-ink-950">{{ row.count }}</span>
            </div>
          </div>
          <p v-else class="py-5 text-sm text-ink-400">当前没有已保存的加密凭据。</p>
        </section>

        <section>
          <div class="flex items-center gap-2 border-b border-paper-200 pb-3">
            <KeyRound :size="18" class="text-brand-700" aria-hidden="true" />
            <h2 class="text-sm font-semibold text-ink-950">密钥分布</h2>
          </div>
          <div v-if="keyRows.length" class="divide-y divide-paper-200">
            <div v-for="row in keyRows" :key="row.keyId" class="flex items-center justify-between gap-4 py-4">
              <div class="min-w-0">
                <p class="break-all font-mono text-sm text-ink-800">{{ row.keyId }}</p>
                <StatusPill :status="row.keyId === statusQuery.data.value.activeKeyId ? 'ACTIVE' : 'RETIRED'" class="mt-1.5" />
              </div>
              <span class="font-mono text-sm font-semibold text-ink-950">{{ row.count }}</span>
            </div>
          </div>
          <p v-else class="py-5 text-sm text-ink-400">当前没有密钥分布记录。</p>
        </section>
      </div>

      <section class="mt-10 border-t border-paper-200 pt-6">
        <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 class="text-sm font-semibold text-ink-950">最近轮换</h2>
            <p v-if="statusQuery.data.value.lastRotation" class="mt-1 text-sm text-ink-500">
              {{ formatDate(statusQuery.data.value.lastRotation.createdAt) }} ·
              {{ statusQuery.data.value.lastRotation.rotatedCredentials }} 条已更新 ·
              Key ID {{ statusQuery.data.value.lastRotation.activeKeyId }}
            </p>
            <p v-else class="mt-1 text-sm text-ink-400">暂无轮换审计记录</p>
          </div>
          <StatusPill :status="statusQuery.data.value.lastRotation ? 'COMPLETED' : 'PENDING'" />
        </div>
      </section>
    </template>

    <ModalDialog
      :open="confirmOpen"
      title="重新加密历史凭据"
      :description="`目标 Key ID：${statusQuery.data.value?.activeKeyId ?? ''}`"
      @close="confirmOpen = false"
    >
      <div class="space-y-5">
        <p class="text-sm leading-6 text-ink-600">
          将在同一事务中验证并更新 {{ statusQuery.data.value?.needsRotation ?? 0 }} 条历史密文。
          任意一条验证失败时不会写入变更。
        </p>
        <p v-if="rotateMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
          {{ readableError(rotateMutation.error.value) }}
        </p>
        <div class="flex justify-end gap-3">
          <button type="button" class="button-secondary" @click="confirmOpen = false">取消</button>
          <button type="button" class="button-primary" :disabled="rotateMutation.isPending.value" @click="rotateMutation.mutate()">
            <LoaderCircle v-if="rotateMutation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />
            确认重新加密
          </button>
        </div>
      </div>
    </ModalDialog>
  </div>
</template>
