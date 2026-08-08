<script setup lang="ts">
withDefaults(defineProps<{ inverted?: boolean; compact?: boolean }>(), {
  inverted: false,
  compact: false,
})
</script>

<template>
  <span
    class="verity-wordmark"
    :class="[
      inverted ? 'text-white' : 'text-ink-950',
      compact ? 'text-[15px]' : 'text-[17px]',
    ]"
    aria-label="VerityForge"
  >
    <span class="font-semibold">Verity</span><span class="text-brand-600">Forge</span>
  </span>
</template>
