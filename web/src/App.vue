<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useRunStore } from '@/stores/runs'

const router = useRouter()
const queryClient = useQueryClient()
const auth = useAuthStore()
const runs = useRunStore()

function handleAuthExpired() {
  auth.logout()
  runs.clear()
  queryClient.clear()
  void router.replace({ name: 'login', query: { expired: '1' } })
}

onMounted(() => window.addEventListener('rag:auth-expired', handleAuthExpired))
onBeforeUnmount(() => window.removeEventListener('rag:auth-expired', handleAuthExpired))
</script>

<template>
  <RouterView />
</template>
