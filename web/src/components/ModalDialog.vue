<script setup lang="ts">
import { X } from 'lucide-vue-next'

defineProps<{
  open: boolean
  title: string
  description?: string
  widthClass?: string
}>()

const emit = defineEmits<{
  close: []
}>()
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="fixed inset-0 z-50 flex items-end justify-center bg-ink-950/40 p-0 sm:items-center sm:p-5"
      role="dialog"
      aria-modal="true"
      :aria-label="title"
      @click.self="emit('close')"
    >
      <section
        class="max-h-[92dvh] w-full overflow-y-auto rounded-t-lg border border-paper-200 bg-white shadow-panel sm:rounded-lg"
        :class="widthClass || 'max-w-lg'"
      >
        <header class="flex items-start justify-between border-b border-paper-200 bg-paper-50 px-5 py-4">
          <div>
            <h2 class="text-base font-semibold">{{ title }}</h2>
            <p v-if="description" class="mt-1 text-sm leading-5 text-ink-600">{{ description }}</p>
          </div>
          <button class="icon-button -mr-2 -mt-1" type="button" title="关闭" @click="emit('close')">
            <X :size="19" aria-hidden="true" />
          </button>
        </header>
        <div class="p-5">
          <slot />
        </div>
      </section>
    </div>
  </Teleport>
</template>
