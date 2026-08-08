<script setup lang="ts">
import { AlertCircle, RefreshCw } from 'lucide-vue-next'

defineProps<{
  title?: string
  message: string
  retryLabel?: string
}>()

const emit = defineEmits<{
  retry: []
}>()
</script>

<template>
  <div class="flex min-h-44 flex-col items-start justify-center rounded-lg border border-coral-100 bg-coral-50 px-5 py-8">
    <AlertCircle :size="22" class="text-coral-700" aria-hidden="true" />
    <h2 class="mt-3 text-sm font-semibold text-ink-950">{{ title || '无法载入数据' }}</h2>
    <p class="mt-1 max-w-xl text-sm leading-6 text-ink-600">{{ message }}</p>
    <button
      type="button"
      class="button-secondary mt-4"
      @click="emit('retry')"
    >
      <RefreshCw :size="16" aria-hidden="true" />
      {{ retryLabel || '重试' }}
    </button>
  </div>
</template>
