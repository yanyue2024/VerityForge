<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status?: string | null
}>()

const normalized = computed(() => props.status?.toUpperCase() ?? 'UNKNOWN')
const style = computed(() => {
  if (['READY', 'PUBLISHED', 'ACTIVE', 'SUCCEEDED', 'COMPLETED', 'CONFIRMED', 'PASS'].includes(normalized.value)) {
    return 'bg-evidence-50 text-evidence-700'
  }
  if (['FAILED', 'FAIL', 'EXPIRED', 'CANCELLED', 'DISCONNECTED', 'REJECTED'].includes(normalized.value)) {
    return 'bg-coral-50 text-coral-700'
  }
  if (['QUEUED', 'RUNNING', 'PROCESSING', 'PENDING', 'WAITING', 'DELIVERING', 'RETRY', 'ACCEPTED', 'DRAFT', 'INFERRED', 'WARNING', 'AWAITING_REVIEW', 'REVIEW_REQUIRED'].includes(normalized.value)) {
    return 'bg-amber-50 text-amber-700'
  }
  return 'bg-paper-200 text-ink-600'
})

const label = computed(() => {
  const labels: Record<string, string> = {
    ACTIVE: '可用',
    INACTIVE: '停用',
    READY: '就绪',
    PUBLISHED: '已发布',
    PROCESSING: '处理中',
    PENDING: '等待中',
    QUEUED: '排队中',
    RUNNING: '运行中',
    WAITING: '等待结果',
    DELIVERING: '投递中',
    RETRY: '等待重试',
    ACCEPTED: '已接收',
    COMPLETED: '已完成',
    SUCCEEDED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    DISCONNECTED: '连接中断',
    DRAFT: '草稿',
    EXPIRED: '已过期',
    INFERRED: '待确认',
    CONFIRMED: '已确认',
    REJECTED: '已拒绝',
    PASS: '质量通过',
    WARNING: '需要确认',
    FAIL: '质量未通过',
    AWAITING_REVIEW: '等待确认',
    REVIEW_REQUIRED: '需要确认',
  }
  return labels[normalized.value] ?? props.status ?? '未知'
})
</script>

<template>
  <span
    class="inline-flex min-h-6 items-center rounded-full px-2.5 py-1 text-xs font-medium"
    :class="style"
  >
    {{ label }}
  </span>
</template>
