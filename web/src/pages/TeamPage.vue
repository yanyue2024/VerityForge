<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import {
  KeyRound,
  LoaderCircle,
  Pencil,
  Plus,
  ShieldCheck,
  UserRoundCheck,
  UsersRound,
} from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import ModalDialog from '@/components/ModalDialog.vue'
import StatusPill from '@/components/StatusPill.vue'
import { api, readableError } from '@/lib/api'
import { formatDate } from '@/lib/format'
import { useAuthStore } from '@/stores/auth'
import type { AuthSession, TeamMember, UserRole } from '@/types/api'

const auth = useAuthStore()
const queryClient = useQueryClient()
const createOpen = ref(false)
const editOpen = ref(false)
const resetOpen = ref(false)
const passwordOpen = ref(false)
const selected = ref<TeamMember | null>(null)
const actionError = ref('')

const createForm = reactive({
  username: '',
  displayName: '',
  role: 'VIEWER' as UserRole,
  password: '',
})
const editForm = reactive({ displayName: '', role: 'VIEWER' as UserRole, enabled: true })
const resetPassword = ref('')
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmation: '' })

const roleOptions: Array<{ value: UserRole; label: string; description: string }> = [
  { value: 'ADMIN', label: '管理员', description: '成员、模型与全部工作区管理' },
  { value: 'EDITOR', label: '编辑者', description: '知识治理、问答和评测操作' },
  { value: 'VIEWER', label: '查看者', description: '问答、阅读知识与查看评测结果' },
]

const membersQuery = useQuery({
  queryKey: ['team-members'],
  queryFn: () => api.get<TeamMember[]>('/api/v1/team/members'),
})

const enabledCount = computed(() => membersQuery.data.value?.filter((member) => member.enabled).length ?? 0)
const adminCount = computed(
  () => membersQuery.data.value?.filter((member) => member.enabled && member.role === 'ADMIN').length ?? 0,
)

const createMutation = useMutation({
  mutationFn: () => api.post<TeamMember>('/api/v1/team/members', {
    username: createForm.username.trim(),
    displayName: createForm.displayName.trim(),
    role: createForm.role,
    password: createForm.password,
  }),
  onSuccess: async () => {
    createOpen.value = false
    Object.assign(createForm, { username: '', displayName: '', role: 'VIEWER', password: '' })
    await refresh()
  },
})

const updateMutation = useMutation({
  mutationFn: () => api.put<TeamMember>(`/api/v1/team/members/${selected.value?.id}`, {
    displayName: editForm.displayName.trim(),
    role: editForm.role,
    enabled: editForm.enabled,
  }),
  onSuccess: async (member) => {
    editOpen.value = false
    actionError.value = ''
    if (member.currentUser && auth.session) {
      auth.persistSession({ ...auth.session, displayName: member.displayName })
    }
    await refresh()
  },
  onError: (error) => {
    actionError.value = readableError(error)
  },
})

const resetMutation = useMutation({
  mutationFn: () => api.post<void>(`/api/v1/team/members/${selected.value?.id}/reset-password`, {
    newPassword: resetPassword.value,
  }),
  onSuccess: () => {
    resetOpen.value = false
    resetPassword.value = ''
    actionError.value = ''
  },
  onError: (error) => {
    actionError.value = readableError(error)
  },
})

const changePasswordMutation = useMutation({
  mutationFn: () => api.post<AuthSession>('/api/v1/auth/change-password', {
    currentPassword: passwordForm.currentPassword,
    newPassword: passwordForm.newPassword,
  }),
  onSuccess: (session) => {
    auth.persistSession(session)
    passwordOpen.value = false
    Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmation: '' })
    actionError.value = ''
  },
})

async function refresh() {
  await queryClient.invalidateQueries({ queryKey: ['team-members'] })
}

function openEdit(member: TeamMember) {
  selected.value = member
  Object.assign(editForm, {
    displayName: member.displayName,
    role: member.role,
    enabled: member.enabled,
  })
  actionError.value = ''
  editOpen.value = true
}

function openReset(member: TeamMember) {
  selected.value = member
  resetPassword.value = ''
  actionError.value = ''
  resetOpen.value = true
}

function roleLabel(role: UserRole) {
  return roleOptions.find((option) => option.value === role)?.label ?? role
}
</script>

<template>
  <div class="mx-auto w-full max-w-7xl px-4 py-7 sm:px-7 sm:py-10 lg:px-10">
    <header class="flex flex-col gap-5 border-b border-paper-200 pb-7 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <p class="section-label">Access control</p>
        <h1 class="page-title mt-2">团队成员</h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-ink-600">
          管理工作台访问身份与角色。角色、启用状态或密码改变后，成员已有会话会立即失效。
        </p>
      </div>
      <div class="flex flex-wrap gap-2">
        <button type="button" class="button-secondary" @click="passwordOpen = true">
          <KeyRound :size="17" aria-hidden="true" />
          修改我的密码
        </button>
        <button type="button" class="button-primary" @click="createOpen = true">
          <Plus :size="17" aria-hidden="true" />
          添加成员
        </button>
      </div>
    </header>

    <div class="grid grid-cols-2 border-b border-paper-200 py-5 sm:max-w-md">
      <div class="border-r border-paper-200 pr-5">
        <p class="text-2xl font-semibold text-ink-950">{{ enabledCount }}</p>
        <p class="mt-1 text-xs text-ink-400">可用成员</p>
      </div>
      <div class="pl-5">
        <p class="text-2xl font-semibold text-ink-950">{{ adminCount }}</p>
        <p class="mt-1 text-xs text-ink-400">管理员</p>
      </div>
    </div>

    <ErrorState
      v-if="membersQuery.isError.value"
      class="mt-6"
      :message="readableError(membersQuery.error.value)"
      @retry="membersQuery.refetch()"
    />
    <div v-else-if="membersQuery.isPending.value" class="divide-y divide-paper-200">
      <div v-for="index in 4" :key="index" class="h-20 animate-pulse bg-paper-100" />
    </div>
    <EmptyState
      v-else-if="!membersQuery.data.value?.length"
      :icon="UsersRound"
      title="还没有团队成员"
      description="添加第一个成员后，可以为其分配管理员、编辑者或查看者角色。"
    />
    <div v-else class="border-b border-paper-200">
      <div
        class="hidden grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_8rem_7rem_11rem_5rem] border-b border-paper-200 bg-paper-100 px-3 py-3 text-xs text-ink-400 lg:grid"
      >
        <span>成员</span><span>用户名</span><span>角色</span><span>状态</span><span>更新时间</span><span />
      </div>
      <div class="divide-y divide-paper-200">
        <article
          v-for="member in membersQuery.data.value"
          :key="member.id"
          class="grid grid-cols-2 gap-x-3 gap-y-4 py-5 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_8rem_7rem_11rem_5rem] lg:items-center lg:gap-0 lg:px-3 lg:py-4"
        >
          <div class="col-span-2 flex min-w-0 items-center gap-3 lg:col-span-1 lg:pr-4">
            <span class="flex size-9 shrink-0 items-center justify-center rounded-full bg-brand-50 font-semibold text-brand-700">
              {{ member.displayName.slice(0, 1) }}
            </span>
            <div class="min-w-0">
              <p class="truncate text-sm font-medium text-ink-950">{{ member.displayName }}</p>
              <p v-if="member.currentUser" class="mt-0.5 text-xs text-brand-700">当前登录成员</p>
            </div>
          </div>
          <div class="min-w-0">
            <p class="mb-1 text-xs text-ink-400 lg:hidden">用户名</p>
            <p class="truncate text-sm text-ink-600">{{ member.username }}</p>
          </div>
          <div>
            <p class="mb-1 text-xs text-ink-400 lg:hidden">角色</p>
            <span class="inline-flex items-center gap-1.5 text-sm text-ink-800">
              <ShieldCheck v-if="member.role === 'ADMIN'" :size="15" class="text-brand-700" aria-hidden="true" />
              <UserRoundCheck v-else :size="15" class="text-ink-400" aria-hidden="true" />
              {{ roleLabel(member.role) }}
            </span>
          </div>
          <div>
            <p class="mb-1 text-xs text-ink-400 lg:hidden">状态</p>
            <StatusPill :status="member.enabled ? 'ACTIVE' : 'INACTIVE'" />
          </div>
          <div class="text-xs text-ink-600">
            <p class="mb-1 text-ink-400 lg:hidden">更新时间</p>
            {{ formatDate(member.updatedAt) }}
          </div>
          <div class="flex justify-end gap-1">
            <button v-if="!member.currentUser" type="button" class="icon-button size-8" title="重置密码" @click="openReset(member)">
              <KeyRound :size="16" aria-hidden="true" />
            </button>
            <button type="button" class="icon-button size-8" title="编辑成员" @click="openEdit(member)">
              <Pencil :size="16" aria-hidden="true" />
            </button>
          </div>
        </article>
      </div>
    </div>

    <p v-if="actionError" class="mt-4 rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
      {{ actionError }}
    </p>

    <ModalDialog :open="createOpen" title="添加团队成员" description="新成员创建后可以立即登录工作台。" @close="createOpen = false">
      <form class="space-y-5" @submit.prevent="createMutation.mutate()">
        <label class="block text-sm font-medium text-ink-800">显示名称
          <input v-model="createForm.displayName" class="control mt-2" maxlength="120" required autocomplete="off" />
        </label>
        <label class="block text-sm font-medium text-ink-800">用户名
          <input v-model="createForm.username" class="control mt-2" minlength="3" maxlength="80" pattern="[\p{L}\p{N}][\p{L}\p{N}._-]{2,79}" required autocomplete="off" />
        </label>
        <label class="block text-sm font-medium text-ink-800">角色
          <select v-model="createForm.role" class="control mt-2">
            <option v-for="option in roleOptions" :key="option.value" :value="option.value">{{ option.label }} · {{ option.description }}</option>
          </select>
        </label>
        <label class="block text-sm font-medium text-ink-800">初始密码
          <input v-model="createForm.password" class="control mt-2" type="password" minlength="12" maxlength="200" required autocomplete="new-password" />
          <span class="mt-1.5 block text-xs text-ink-400">至少 12 个字符，仅在本次创建时提交。</span>
        </label>
        <p v-if="createMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ readableError(createMutation.error.value) }}</p>
        <div class="flex justify-end gap-3">
          <button type="button" class="button-secondary" @click="createOpen = false">取消</button>
          <button type="submit" class="button-primary" :disabled="createMutation.isPending.value">
            <LoaderCircle v-if="createMutation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />保存成员
          </button>
        </div>
      </form>
    </ModalDialog>

    <ModalDialog :open="editOpen" title="编辑团队成员" :description="selected?.username" @close="editOpen = false">
      <form class="space-y-5" @submit.prevent="updateMutation.mutate()">
        <label class="block text-sm font-medium text-ink-800">显示名称
          <input v-model="editForm.displayName" class="control mt-2" maxlength="120" required />
        </label>
        <label class="block text-sm font-medium text-ink-800">角色
          <select v-model="editForm.role" class="control mt-2" :disabled="selected?.currentUser">
            <option v-for="option in roleOptions" :key="option.value" :value="option.value">{{ option.label }} · {{ option.description }}</option>
          </select>
        </label>
        <label class="flex items-center justify-between gap-4 border-y border-paper-200 py-4">
          <span><span class="block text-sm font-medium text-ink-800">允许登录</span><span class="mt-1 block text-xs text-ink-400">停用后已有会话立即失效，历史记录仍然保留。</span></span>
          <input v-model="editForm.enabled" type="checkbox" class="size-5 shrink-0 accent-brand-700" :disabled="selected?.currentUser" />
        </label>
        <p v-if="updateMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ readableError(updateMutation.error.value) }}</p>
        <div class="flex justify-end gap-3">
          <button type="button" class="button-secondary" @click="editOpen = false">取消</button>
          <button type="submit" class="button-primary" :disabled="updateMutation.isPending.value">
            <LoaderCircle v-if="updateMutation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />保存修改
          </button>
        </div>
      </form>
    </ModalDialog>

    <ModalDialog :open="resetOpen" title="重置成员密码" :description="selected ? `${selected.displayName} · ${selected.username}` : ''" @close="resetOpen = false">
      <form class="space-y-5" @submit.prevent="resetMutation.mutate()">
        <label class="block text-sm font-medium text-ink-800">新密码
          <input v-model="resetPassword" class="control mt-2" type="password" minlength="12" maxlength="200" required autocomplete="new-password" />
        </label>
        <p class="text-xs leading-5 text-ink-400">保存后该成员的全部已有 Token 立即失效，需要使用新密码重新登录。</p>
        <p v-if="resetMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ readableError(resetMutation.error.value) }}</p>
        <div class="flex justify-end gap-3">
          <button type="button" class="button-secondary" @click="resetOpen = false">取消</button>
          <button type="submit" class="button-primary" :disabled="resetMutation.isPending.value">
            <LoaderCircle v-if="resetMutation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />重置密码
          </button>
        </div>
      </form>
    </ModalDialog>

    <ModalDialog :open="passwordOpen" title="修改我的密码" description="更新成功后当前页面会切换到新会话。" @close="passwordOpen = false">
      <form class="space-y-5" @submit.prevent="changePasswordMutation.mutate()">
        <label class="block text-sm font-medium text-ink-800">当前密码
          <input v-model="passwordForm.currentPassword" class="control mt-2" type="password" required autocomplete="current-password" />
        </label>
        <label class="block text-sm font-medium text-ink-800">新密码
          <input v-model="passwordForm.newPassword" class="control mt-2" type="password" minlength="12" maxlength="200" required autocomplete="new-password" />
        </label>
        <label class="block text-sm font-medium text-ink-800">确认新密码
          <input v-model="passwordForm.confirmation" class="control mt-2" type="password" minlength="12" maxlength="200" required autocomplete="new-password" />
        </label>
        <p v-if="passwordForm.confirmation && passwordForm.confirmation !== passwordForm.newPassword" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">两次输入的新密码不一致</p>
        <p v-if="changePasswordMutation.isError.value" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">{{ readableError(changePasswordMutation.error.value) }}</p>
        <div class="flex justify-end gap-3">
          <button type="button" class="button-secondary" @click="passwordOpen = false">取消</button>
          <button type="submit" class="button-primary" :disabled="changePasswordMutation.isPending.value || passwordForm.newPassword !== passwordForm.confirmation">
            <LoaderCircle v-if="changePasswordMutation.isPending.value" :size="17" class="animate-spin" aria-hidden="true" />更新密码
          </button>
        </div>
      </form>
    </ModalDialog>
  </div>
</template>
