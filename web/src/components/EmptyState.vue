<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  icon: Component
  title: string
  description?: string
}>()
</script>

<template>
  <div class="flex min-h-56 flex-col items-center justify-center px-6 py-12 text-center">
    <span class="mb-4 flex size-11 items-center justify-center rounded-lg bg-brand-50 text-brand-700">
      <component :is="icon" :size="21" aria-hidden="true" />
    </span>
    <h2 class="font-display text-base font-semibold text-ink-950">{{ title }}</h2>
    <p v-if="description" class="mt-2 max-w-md text-sm leading-6 text-ink-600">
      {{ description }}
    </p>
    <div v-if="$slots.default" class="mt-5">
      <slot />
    </div>
  </div>
</template>
