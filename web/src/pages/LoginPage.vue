<script setup lang="ts">
import { computed, ref } from 'vue'
import { useMutation } from '@tanstack/vue-query'
import { ArrowRight, Eye, EyeOff, LoaderCircle } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import BrandWordmark from '@/components/BrandWordmark.vue'
import { readableError } from '@/lib/api'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const username = ref('')
const password = ref('')
const remember = ref(true)
const showPassword = ref(false)

const loginMutation = useMutation({
  mutationFn: () =>
    auth.login(
      {
        username: username.value.trim(),
        password: password.value,
      },
      remember.value,
    ),
  onSuccess: () => {
    const redirect =
      typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
        ? route.query.redirect
        : '/chat'
    void router.replace(redirect)
  },
})

const errorMessage = computed(() => {
  if (route.query.expired === '1' && !loginMutation.error.value) {
    return '登录已过期，请重新验证身份。'
  }
  return loginMutation.error.value ? readableError(loginMutation.error.value) : ''
})
</script>

<template>
  <main class="min-h-dvh bg-paper-50 lg:p-4">
    <div class="grid min-h-dvh overflow-hidden bg-white lg:min-h-[calc(100dvh-2rem)] lg:grid-cols-[minmax(520px,0.94fr)_minmax(460px,1.06fr)] lg:rounded-xl lg:border lg:border-paper-200">
    <section class="relative hidden overflow-hidden bg-ink-950 px-12 py-10 text-white lg:flex lg:flex-col xl:px-16 xl:py-12">
      <div class="flex items-center">
        <BrandWordmark inverted />
      </div>

      <div class="my-auto max-w-2xl">
        <p class="text-xs font-semibold text-evidence-100">VERIFIABLE KNOWLEDGE</p>
        <h1 class="mt-5 font-display text-5xl font-semibold leading-[1.16] text-white xl:text-6xl">
          让每一次回答，
          <br />
          都能回到证据。
        </h1>
        <p class="mt-7 max-w-xl text-base leading-8 text-white/60">
          把知识入库、快速问答与深度研究放进同一个工作上下文，让结论、引用与运行过程始终可追溯。
        </p>
        <div class="mt-10 flex items-center gap-5 text-xs text-white/40">
          <span>知识入库</span><span class="size-1 rounded-full bg-evidence-600" />
          <span>可追溯问答</span><span class="size-1 rounded-full bg-evidence-600" />
          <span>Fast / Deep</span>
        </div>
      </div>

      <p class="text-xs text-white/30">Internal knowledge workspace</p>
    </section>

    <section class="flex min-h-dvh items-center justify-center px-5 py-10 sm:px-10 lg:min-h-0 lg:px-16 xl:px-24">
      <div class="w-full max-w-md">
        <div class="mb-10 flex items-center lg:hidden">
          <BrandWordmark />
        </div>

        <p class="section-label">安全登录</p>
        <h1 class="mt-3 font-display text-3xl font-semibold">欢迎回来</h1>
        <p class="mt-2 text-sm leading-6 text-ink-600">使用组织账号继续你的知识工作。</p>

        <form class="mt-8 space-y-5" @submit.prevent="loginMutation.mutate()">
          <label class="block text-sm font-medium text-ink-800">
            用户名
            <input
              v-model="username"
              class="control mt-2"
              name="username"
              autocomplete="username"
              required
              autofocus
            />
          </label>

          <label class="block text-sm font-medium text-ink-800">
            密码
            <span class="relative mt-2 block">
              <input
                v-model="password"
                class="control pr-11"
                :type="showPassword ? 'text' : 'password'"
                name="password"
                autocomplete="current-password"
                required
              />
              <button
                class="icon-button absolute right-0 top-0"
                type="button"
                :title="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <EyeOff v-if="showPassword" :size="17" aria-hidden="true" />
                <Eye v-else :size="17" aria-hidden="true" />
              </button>
            </span>
          </label>

          <label class="flex cursor-pointer items-center gap-2 text-sm text-ink-600">
            <input v-model="remember" type="checkbox" class="size-4 accent-brand-700" />
            在这台设备上保持登录
          </label>

          <p v-if="errorMessage" class="rounded-md bg-coral-50 px-3 py-2 text-sm text-coral-700">
            {{ errorMessage }}
          </p>

          <button
            type="submit"
            class="button-primary w-full"
            :disabled="loginMutation.isPending.value || !username.trim() || !password"
          >
            <LoaderCircle
              v-if="loginMutation.isPending.value"
              :size="17"
              class="animate-spin"
              aria-hidden="true"
            />
            <ArrowRight v-else :size="17" aria-hidden="true" />
            {{ loginMutation.isPending.value ? '正在验证' : '进入工作台' }}
          </button>
        </form>
      </div>
    </section>
    </div>
  </main>
</template>
