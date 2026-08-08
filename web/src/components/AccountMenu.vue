<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { LogOut, MessageSquareText, MoreHorizontal, Settings2 } from 'lucide-vue-next'
import { useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useRunStore } from '@/stores/runs'

const props = withDefaults(
  defineProps<{
    workspace: 'chat' | 'management'
    compact?: boolean
  }>(),
  { compact: false },
)
const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const popup = ref<HTMLElement | null>(null)
const open = ref(false)
const popupStyle = ref<Record<string, string>>({})
const router = useRouter()
const queryClient = useQueryClient()
const auth = useAuthStore()
const runs = useRunStore()

const initial = computed(() => auth.session?.displayName?.trim().slice(0, 1).toUpperCase() || 'A')
const displayName = computed(() => auth.session?.displayName?.trim() || 'Administrator')
const roleLabel = computed(() => ({ ADMIN: '管理员', EDITOR: '编辑者', VIEWER: '查看者' })[auth.session?.role ?? 'ADMIN'])
const destination = computed(() => (props.workspace === 'chat' ? '/knowledge' : '/chat'))
const destinationLabel = computed(() => (props.workspace === 'chat' ? '进入管理工作台' : '返回对话'))

function onDocumentClick(event: MouseEvent) {
  const target = event.target as Node
  if (!root.value?.contains(target) && !popup.value?.contains(target)) open.value = false
}

function updatePopupPosition() {
  if (!root.value) return
  const rect = root.value.getBoundingClientRect()
  popupStyle.value = props.compact
    ? {
        left: `${Math.round(rect.right + 10)}px`,
        bottom: `${Math.max(10, Math.round(window.innerHeight - rect.bottom))}px`,
        width: '224px',
      }
    : {
        left: `${Math.round(rect.left)}px`,
        bottom: `${Math.max(10, Math.round(window.innerHeight - rect.top + 8))}px`,
        width: `${Math.round(rect.width)}px`,
      }
}

async function toggleMenu() {
  open.value = !open.value
  if (!open.value) return
  await nextTick()
  updatePopupPosition()
}

function onWindowKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !open.value) return
  open.value = false
  trigger.value?.focus()
}

function logout() {
  auth.logout()
  runs.clear()
  queryClient.clear()
  void router.replace('/login')
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  window.addEventListener('keydown', onWindowKeydown)
  window.addEventListener('resize', updatePopupPosition)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
  window.removeEventListener('keydown', onWindowKeydown)
  window.removeEventListener('resize', updatePopupPosition)
})
</script>

<template>
  <div ref="root" class="relative">
    <button
      v-if="compact"
      ref="trigger"
      type="button"
      class="mx-auto flex size-11 items-center justify-center rounded-lg transition-colors hover:bg-paper-100"
      :title="displayName"
      :aria-label="`${open ? '关闭' : '打开'} ${displayName} 的账户菜单`"
      :aria-expanded="open"
      aria-haspopup="true"
      aria-controls="account-menu-popup"
      @click.stop="toggleMenu"
    >
      <span class="flex size-9 shrink-0 items-center justify-center rounded-lg border border-brand-100 bg-brand-50 text-sm font-semibold text-brand-700">
        {{ initial }}
      </span>
    </button>

    <div v-else class="flex min-h-14 w-full items-center gap-3 rounded-lg px-2">
      <span class="flex size-9 shrink-0 items-center justify-center rounded-lg border border-brand-100 bg-brand-50 text-sm font-semibold text-brand-700">
        {{ initial }}
      </span>
      <span class="min-w-0 flex-1">
        <span class="block truncate text-sm font-semibold text-ink-900">{{ displayName }}</span>
        <span class="mt-0.5 block text-[11px] font-medium text-ink-600">{{ roleLabel }}</span>
      </span>
      <button
        ref="trigger"
        type="button"
        class="icon-button size-8 rounded-md text-ink-500"
        title="账户菜单"
        :aria-label="`${open ? '关闭' : '打开'} ${displayName} 的账户菜单`"
        :aria-expanded="open"
        aria-haspopup="true"
        aria-controls="account-menu-popup"
        @click.stop="toggleMenu"
      >
        <MoreHorizontal :size="18" aria-hidden="true" />
      </button>
    </div>

    <Teleport to="body">
      <div
        v-if="open"
        id="account-menu-popup"
        ref="popup"
        class="fixed z-[100] rounded-lg border border-paper-200 bg-white p-1.5 shadow-panel"
        :style="popupStyle"
      >
        <div v-if="compact" class="mb-1.5 flex items-center gap-3 border-b border-paper-200 px-2.5 py-2.5">
          <span class="flex size-9 shrink-0 items-center justify-center rounded-lg border border-brand-100 bg-brand-50 text-sm font-semibold text-brand-700">
            {{ initial }}
          </span>
          <span class="min-w-0">
            <span class="block truncate text-sm font-semibold text-ink-900">{{ displayName }}</span>
            <span class="mt-0.5 block text-[11px] font-medium text-ink-600">{{ roleLabel }}</span>
          </span>
        </div>
        <RouterLink
          :to="destination"
          class="flex h-10 items-center gap-2.5 rounded-md px-2.5 text-sm font-medium text-ink-800 hover:bg-paper-100"
          @click="open = false"
        >
          <Settings2 v-if="workspace === 'chat'" :size="16" aria-hidden="true" />
          <MessageSquareText v-else :size="16" aria-hidden="true" />
          {{ destinationLabel }}
        </RouterLink>
        <div class="my-1 border-t border-paper-200" />
        <button
          type="button"
          class="flex h-10 w-full items-center gap-2.5 rounded-md px-2.5 text-sm font-medium text-coral-700 hover:bg-coral-50"
          @click="logout"
        >
          <LogOut :size="16" aria-hidden="true" />
          退出登录
        </button>
      </div>
    </Teleport>
  </div>
</template>
