<script setup lang="ts">
import { ArrowUpRight, MessageSquareText, RefreshCw, Sparkles } from 'lucide-vue-next'
import type { QuestionSuggestionEmptyReason, QuestionSuggestionView } from '@/types/api'

defineProps<{
  items: QuestionSuggestionView[]
  loading: boolean
  refreshing: boolean
  error: boolean
  emptyReason: QuestionSuggestionEmptyReason | null
}>()

const emit = defineEmits<{
  select: [text: string]
  refresh: []
  retry: []
}>()
</script>

<template>
  <section class="question-suggestions" aria-label="推荐问题">
    <div class="question-suggestions-heading">
      <div class="question-suggestions-title">
        <Sparkles :size="15" aria-hidden="true" />
        <p>为你推荐</p>
      </div>
      <button
        v-if="items.length"
        type="button"
        class="question-suggestions-refresh"
        :disabled="refreshing"
        title="换一组"
        aria-label="换一组推荐问题"
        @click="emit('refresh')"
      >
        <RefreshCw :size="15" :class="{ 'animate-spin': refreshing }" aria-hidden="true" />
        <span>换一组</span>
      </button>
    </div>

    <span class="sr-only" role="status">
      {{ loading && !items.length ? '正在准备推荐问题' : refreshing ? '正在更换推荐问题' : error ? '推荐问题暂时不可用' : '' }}
    </span>

    <div v-if="loading && !items.length" class="question-suggestions-grid" aria-hidden="true">
      <div v-for="item in 4" :key="item" class="question-suggestion-skeleton">
        <span class="question-suggestion-skeleton-mark" />
        <span class="question-suggestion-skeleton-copy" />
      </div>
    </div>

    <div v-else-if="items.length" class="question-suggestions-grid">
      <button
        v-for="item in items"
        :key="item.id"
        type="button"
        class="question-suggestion-item"
        :title="item.text"
        @click="emit('select', item.text)"
      >
        <span class="question-suggestion-mark" aria-hidden="true">
          <MessageSquareText :size="15" />
        </span>
        <span class="question-suggestion-copy">{{ item.text }}</span>
        <ArrowUpRight :size="15" aria-hidden="true" />
      </button>
    </div>

    <div v-else-if="error" class="question-suggestions-message">
      <span>推荐问题暂时不可用</span>
      <button type="button" @click="emit('retry')">重试</button>
    </div>

    <div v-else-if="emptyReason" class="question-suggestions-message">
      <span>
        {{
          emptyReason === 'NO_ELIGIBLE_CONTENT'
            ? '当前范围内还没有可用内容'
            : emptyReason === 'CATALOG_BUILDING'
              ? '正在准备适合当前范围的问题'
              : '当前内容暂时不足以提供可靠问题'
        }}
      </span>
    </div>
  </section>
</template>
