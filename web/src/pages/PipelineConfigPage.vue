<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Bot,
  Check,
  ChevronDown,
  CircleAlert,
  CircleCheck,
  FlaskConical,
  KeyRound,
  LoaderCircle,
  Pencil,
  Plus,
  Save,
  Send,
  SlidersHorizontal,
  Trash2,
} from 'lucide-vue-next'
import ModalDialog from '@/components/ModalDialog.vue'
import { api, readableError } from '@/lib/api'
import type {
  AiConfig,
  AiConfigPreview,
  AiConfigVersion,
  AssistantProfile,
  ModelProfile,
  PipelineConfig,
} from '@/types/api'

type Tab = 'connections' | 'runtime' | 'assistant' | 'advanced'

const config = ref<AiConfig | null>(null)
const loading = ref(true)
const saving = ref(false)
const publishing = ref(false)
const previewing = ref(false)
const activeTab = ref<Tab>('connections')
const message = ref('')
const error = ref('')
const previewQuery = ref('用两三句话介绍你自己，并说明没有内部依据时你会怎么回答。')
const preview = ref<AiConfigPreview | null>(null)
const versions = ref<AiConfigVersion[]>([])
const pipeline = reactive<Partial<PipelineConfig>>({})
const assistant = reactive<Partial<AssistantProfile>>({})
const capabilitiesText = ref('')
const boundariesText = ref('')
const showConnectionForm = ref(false)
const editingProfileId = ref('')
const testingProfileId = ref('')
const activatingProfileId = ref('')
const deletingProfileId = ref('')
const deleteTarget = ref<ModelProfile | null>(null)
const connectionForm = reactive({
  name: '',
  baseUrl: '',
  modelName: '',
  apiKey: '',
  reasoningEffort: 'low',
})
const tabs = [
  { value: 'connections' as const, label: '模型连接', icon: KeyRound },
  { value: 'runtime' as const, label: '任务与回答', icon: SlidersHorizontal },
  { value: 'assistant' as const, label: '助手角色', icon: Bot },
  { value: 'advanced' as const, label: '高级检索', icon: FlaskConical },
]

const languageProfiles = computed(() =>
  (config.value?.modelProfiles ?? []).filter(
    (profile) => profile.enabled && ['CHAT', 'QUERY_REWRITE'].includes(profile.profileType),
  ),
)
const editingProfile = computed(() =>
  languageProfiles.value.find((profile) => profile.id === editingProfileId.value) ?? null,
)
const rerankProfiles = computed(() =>
  (config.value?.modelProfiles ?? []).filter((profile) => profile.enabled && profile.profileType === 'RERANK'),
)
const hasDraft = computed(() => Boolean(config.value && config.value.draftPipeline.id !== config.value.publishedPipeline.id))
const selectedLanguageProfileId = computed(() => pipeline.chatProfileId ?? '')
const publishedLanguageProfileIds = computed(() => new Set([
  config.value?.publishedPipeline.chatProfileId,
  config.value?.publishedPipeline.queryRewriteProfileId,
].filter((profileId): profileId is string => Boolean(profileId))))

function hydrate(value: AiConfig) {
  config.value = value
  Object.assign(pipeline, value.draftPipeline)
  pipeline.queryRewriteProfileId = value.draftPipeline.chatProfileId
  Object.assign(assistant, value.draftAssistant)
  capabilitiesText.value = value.draftAssistant.capabilities.join('\n')
  boundariesText.value = value.draftAssistant.boundaries.join('\n')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    hydrate(await api.get<AiConfig>('/api/v1/ai-config'))
    versions.value = await api.get<AiConfigVersion[]>('/api/v1/ai-config/versions')
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    loading.value = false
  }
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean)
}

function pipelinePayload() {
  return {
    name: pipeline.name,
    chatProfileId: pipeline.chatProfileId,
    queryRewriteProfileId: pipeline.chatProfileId,
    rerankProfileId: pipeline.rerankProfileId,
    keywordTopK: pipeline.keywordTopK,
    semanticTopK: pipeline.semanticTopK,
    rrfCandidateLimit: pipeline.rrfCandidateLimit,
    rerankCandidateLimit: pipeline.rerankCandidateLimit,
    finalContextGroups: pipeline.finalContextGroups,
    contextTokenBudget: pipeline.contextTokenBudget,
    minimumRerankScore: pipeline.minimumRerankScore,
    fastTimeoutSeconds: pipeline.fastTimeoutSeconds,
    maxIterations: pipeline.maxIterations,
    maxRetrievalRounds: pipeline.maxRetrievalRounds,
    maxSubQueries: pipeline.maxSubQueries,
    maxSearchCalls: pipeline.maxSearchCalls,
    maxDeepReadCalls: pipeline.maxDeepReadCalls,
    maxToolCallsPerRound: pipeline.maxToolCallsPerRound,
    maxFinalReferences: pipeline.maxFinalReferences,
    recentTurns: pipeline.recentTurns,
    maxContextTokens: pipeline.maxContextTokens,
    llmTimeoutSeconds: pipeline.llmTimeoutSeconds,
    agenticLoopTimeoutSeconds: pipeline.agenticLoopTimeoutSeconds,
    toolTimeoutSeconds: pipeline.toolTimeoutSeconds,
    maxCompletionTokens: pipeline.maxCompletionTokens,
    temperature: pipeline.temperature,
    parallelToolCalls: pipeline.parallelToolCalls,
    requireDeepReadBeforeAnswer: pipeline.requireDeepReadBeforeAnswer,
  }
}

async function saveDraft() {
  saving.value = true
  error.value = ''
  message.value = ''
  preview.value = null
  try {
    const value = await api.put<AiConfig>('/api/v1/ai-config/draft', {
      pipeline: pipelinePayload(),
      assistant: {
        assistantName: assistant.assistantName,
        identity: assistant.identity,
        capabilities: lines(capabilitiesText.value),
        tone: assistant.tone,
        boundaries: lines(boundariesText.value),
        additionalInstructions: assistant.additionalInstructions ?? '',
      },
    })
    hydrate(value)
    message.value = '草稿已保存。请运行一次预览，确认后再发布。'
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    saving.value = false
  }
}

async function runPreview() {
  previewing.value = true
  error.value = ''
  try {
    preview.value = await api.post<AiConfigPreview>('/api/v1/ai-config/draft/preview', {
      query: previewQuery.value,
    })
    await load()
    message.value = '预览成功。当前草稿已具备发布条件。'
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    previewing.value = false
  }
}

async function publish() {
  publishing.value = true
  error.value = ''
  try {
    hydrate(await api.post<AiConfig>('/api/v1/ai-config/draft/publish', {}))
    preview.value = null
    message.value = '配置已发布。模型与回答参数作用于后续运行；新角色只作用于新会话。'
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    publishing.value = false
  }
}

async function restoreVersion(version: AiConfigVersion) {
  saving.value = true
  error.value = ''
  try {
    hydrate(await api.post<AiConfig>(`/api/v1/ai-config/versions/${version.id}/restore`, {}))
    versions.value = await api.get<AiConfigVersion[]>('/api/v1/ai-config/versions')
    preview.value = null
    message.value = `已从 ${version.name} 创建恢复草稿，请预览后发布。`
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    saving.value = false
  }
}

function resetConnectionForm() {
  Object.assign(connectionForm, { name: '', baseUrl: '', modelName: '', apiKey: '', reasoningEffort: 'low' })
  editingProfileId.value = ''
}

function openCreateConnection() {
  resetConnectionForm()
  showConnectionForm.value = true
}

function editConnection(profile: ModelProfile) {
  editingProfileId.value = profile.id
  Object.assign(connectionForm, {
    name: profile.name,
    baseUrl: profile.baseUrl ?? '',
    modelName: profile.modelName,
    apiKey: '',
    reasoningEffort: typeof profile.settings.reasoningEffort === 'string'
      ? profile.settings.reasoningEffort
      : 'low',
  })
  showConnectionForm.value = true
}

function closeConnectionForm() {
  showConnectionForm.value = false
  resetConnectionForm()
}

async function saveConnection() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    const payload = {
      provider: editingProfile.value?.provider ?? 'OPENAI_COMPATIBLE',
      name: connectionForm.name,
      modelName: connectionForm.modelName,
      baseUrl: connectionForm.baseUrl,
      apiKey: connectionForm.apiKey || undefined,
      settings: { reasoningEffort: connectionForm.reasoningEffort },
    }
    if (editingProfile.value) {
      const profile = await api.put<ModelProfile>(`/api/v1/model-profiles/${editingProfile.value.id}`, {
        ...payload,
        clearApiKey: false,
        enabled: true,
      })
      const result = await api.post<{ status: string; message: string }>(
        `/api/v1/model-profiles/${profile.id}/test`,
        {},
      )
      message.value = result.status === 'PASSED'
        ? `${profile.name} 已更新并通过连接测试，后续对话会使用新配置。`
        : `${profile.name} 已保存，但连接测试失败：${result.message}`
    } else {
      await api.post<ModelProfile>('/api/v1/model-profiles', {
        ...payload,
        profileType: 'CHAT',
      })
      message.value = '模型连接已创建。测试通过后可绑定并发布。'
    }
    closeConnectionForm()
    await load()
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    saving.value = false
  }
}

async function testConnection(profile: ModelProfile) {
  testingProfileId.value = profile.id
  error.value = ''
  try {
    await api.post(`/api/v1/model-profiles/${profile.id}/test`, {})
    await load()
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    testingProfileId.value = ''
  }
}

async function activateLanguageModel(profileId: string) {
  if (!profileId || profileId === selectedLanguageProfileId.value || activatingProfileId.value) return
  const profile = languageProfiles.value.find((item) => item.id === profileId)
  if (!profile || profile.testStatus !== 'PASSED') return
  activatingProfileId.value = profileId
  error.value = ''
  message.value = ''
  try {
    hydrate(await api.post<AiConfig>(`/api/v1/ai-config/language-models/${profileId}/activate`, {}))
    versions.value = await api.get<AiConfigVersion[]>('/api/v1/ai-config/versions')
    preview.value = null
    message.value = `${profile.name} 已设为线上默认语言模型，后续 Fast、Deep、改写和最终回答都会使用它。`
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    activatingProfileId.value = ''
  }
}

function selectConnection(profile: ModelProfile) {
  void activateLanguageModel(profile.id)
}

function selectLanguageModel(event: Event) {
  void activateLanguageModel((event.target as HTMLSelectElement).value)
}

function canDeleteConnection(profile: ModelProfile) {
  return !publishedLanguageProfileIds.value.has(profile.id) && selectedLanguageProfileId.value !== profile.id
}

function requestDeleteConnection(profile: ModelProfile) {
  if (!canDeleteConnection(profile)) return
  deleteTarget.value = profile
}

async function deleteConnection() {
  if (!deleteTarget.value) return
  deletingProfileId.value = deleteTarget.value.id
  error.value = ''
  message.value = ''
  try {
    const deletedName = deleteTarget.value.name
    await api.delete<void>(`/api/v1/model-profiles/${deleteTarget.value.id}`)
    deleteTarget.value = null
    await load()
    message.value = `${deletedName} 已删除，保存的 API Key 已清除。`
  } catch (reason) {
    error.value = readableError(reason)
  } finally {
    deletingProfileId.value = ''
  }
}

function profileStatus(profile: ModelProfile) {
  if (profile.testStatus === 'PASSED') return '可用'
  if (profile.testStatus === 'FAILED') return '测试失败'
  return '待测试'
}

onMounted(() => void load())
</script>

<template>
  <section class="mx-auto min-h-dvh max-w-[1180px] px-9 py-8">
    <header class="flex items-start justify-between gap-8 border-b border-paper-200 pb-7">
      <div>
        <div class="flex items-center gap-2.5">
          <p class="section-label">AI 配置</p>
          <span v-if="hasDraft" class="rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-semibold text-amber-700">有未发布草稿</span>
          <span v-else class="rounded-full bg-evidence-50 px-2 py-0.5 text-[11px] font-semibold text-evidence-700">已发布</span>
        </div>
        <h1 class="mt-2 font-display text-[28px] font-semibold text-ink-950">模型、回答与助手角色</h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-ink-500">
          先保存草稿并验证一次真实回答，再发布到工作台。凭据只写入加密存储，不会在页面重新显示。
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button class="button-secondary" type="button" :disabled="loading || saving" @click="saveDraft">
          <Save :size="16" aria-hidden="true" />{{ saving ? '保存中…' : '保存草稿' }}
        </button>
        <button class="button-primary" type="button" :disabled="!config?.previewReady || publishing" @click="publish">
          <Check :size="16" aria-hidden="true" />{{ publishing ? '发布中…' : '发布配置' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="mt-5 flex items-start gap-2 rounded-lg border border-coral-200 bg-coral-50 px-4 py-3 text-sm text-coral-700">
      <CircleAlert :size="17" class="mt-0.5 shrink-0" aria-hidden="true" />{{ error }}
    </div>
    <div v-else-if="message" class="mt-5 rounded-lg border border-evidence-100 bg-evidence-50 px-4 py-3 text-sm text-evidence-700">{{ message }}</div>

    <div v-if="loading" class="py-20 text-center text-sm text-ink-500">正在读取 AI 配置…</div>
    <div v-else class="grid grid-cols-[190px_minmax(0,1fr)] gap-10 pt-8">
      <nav class="space-y-1 self-start" aria-label="AI 配置分区">
        <button
          v-for="tab in tabs"
          :key="tab.value"
          type="button"
          class="flex h-10 w-full items-center gap-3 rounded-lg px-3 text-left text-sm font-medium transition-colors"
          :class="activeTab === tab.value ? 'bg-ink-950 text-white' : 'text-ink-600 hover:bg-paper-100 hover:text-ink-950'"
          @click="activeTab = tab.value"
        >
          <component :is="tab.icon" :size="16" aria-hidden="true" />{{ tab.label }}
        </button>
      </nav>

      <main class="min-w-0">
        <section v-if="activeTab === 'connections'">
          <div class="flex items-start justify-between gap-6">
            <div>
              <h2 class="text-lg font-semibold text-ink-950">模型连接</h2>
              <p class="mt-1 text-sm leading-6 text-ink-500">一个语言模型统一负责问题理解、改写、规划和最终回答。测试通过后设为当前，选择会立即保存并持续生效。</p>
            </div>
            <button class="button-secondary" type="button" @click="openCreateConnection">
              <Plus :size="16" aria-hidden="true" />添加连接
            </button>
          </div>

          <form v-if="showConnectionForm" class="mt-6 border-y border-paper-200 bg-paper-50 px-5 py-5" @submit.prevent="saveConnection">
            <div class="mb-4 flex items-start justify-between gap-4">
              <div>
                <h3 class="text-sm font-semibold text-ink-950">{{ editingProfile ? '编辑模型连接' : '添加模型连接' }}</h3>
                <p v-if="editingProfile" class="mt-1 text-xs leading-5 text-ink-500">凭据不会回显。API Key 留空会保留原值，重新填写会安全替换并立即测试。</p>
              </div>
              <span v-if="editingProfile && selectedLanguageProfileId === editingProfile.id" class="rounded bg-brand-50 px-2 py-1 text-[11px] font-semibold text-brand-700">当前模型</span>
            </div>
            <div class="grid grid-cols-2 gap-4">
              <label class="field-label">连接名称<input v-model="connectionForm.name" class="field-input" required maxlength="120" placeholder="生产语言模型" /></label>
              <label class="field-label">模型名称<input v-model="connectionForm.modelName" class="field-input" required maxlength="160" placeholder="gpt-5" /></label>
              <label class="field-label col-span-2">Base URL<input v-model="connectionForm.baseUrl" class="field-input" required placeholder="https://example.com/v1" /></label>
              <label class="field-label">API Key<input v-model="connectionForm.apiKey" class="field-input" :required="!editingProfile" type="password" autocomplete="new-password" :placeholder="editingProfile ? '留空保留，输入新值则替换' : '输入 API Key'" /></label>
              <label class="field-label">Reasoning effort<select v-model="connectionForm.reasoningEffort" class="field-input"><option value="minimal">minimal</option><option value="low">low</option><option value="medium">medium</option><option value="high">high</option></select></label>
            </div>
            <div class="mt-5 flex justify-end gap-2">
              <button class="button-secondary" type="button" @click="closeConnectionForm">取消</button>
              <button class="button-primary" type="submit" :disabled="saving">
                <LoaderCircle v-if="saving" :size="15" class="animate-spin" />
                {{ saving ? '保存并测试中…' : editingProfile ? '保存并测试' : '创建连接' }}
              </button>
            </div>
          </form>

          <div class="mt-7 divide-y divide-paper-200 border-y border-paper-200">
            <div v-for="profile in languageProfiles" :key="profile.id" class="grid grid-cols-[minmax(0,1fr)_110px_310px] items-center gap-4 py-4">
              <div class="min-w-0">
                <div class="flex items-center gap-2">
                  <p class="truncate text-sm font-semibold text-ink-900">{{ profile.name }}</p>
                  <span v-if="selectedLanguageProfileId === profile.id" class="inline-flex items-center gap-1 rounded bg-brand-50 px-1.5 py-0.5 text-[10px] font-semibold text-brand-700"><CircleCheck :size="11" />当前模型</span>
                  <span v-else-if="publishedLanguageProfileIds.has(profile.id)" class="rounded bg-paper-100 px-1.5 py-0.5 text-[10px] font-semibold text-ink-600">线上使用中</span>
                </div>
                <p class="mt-1 truncate text-xs text-ink-500">{{ profile.modelName }} · {{ profile.baseUrl }}</p>
              </div>
              <span class="text-xs" :class="profile.testStatus === 'PASSED' ? 'text-evidence-700' : profile.testStatus === 'FAILED' ? 'text-coral-700' : 'text-ink-400'">
                {{ profileStatus(profile) }}
              </span>
              <div class="flex items-center justify-end gap-2">
                <button class="button-secondary min-h-8 px-3 py-1 text-xs" type="button" @click="editConnection(profile)">
                  <Pencil :size="13" aria-hidden="true" />编辑
                </button>
                <button
                  class="button-secondary min-h-8 px-3 py-1 text-xs"
                  type="button"
                  :disabled="selectedLanguageProfileId === profile.id || profile.testStatus !== 'PASSED' || Boolean(activatingProfileId)"
                  :title="profile.testStatus !== 'PASSED' ? '请先测试连接' : '设为线上默认语言模型'"
                  @click="selectConnection(profile)"
                >
                  <LoaderCircle v-if="activatingProfileId === profile.id" :size="13" class="animate-spin" />
                  {{ activatingProfileId === profile.id ? '切换中' : selectedLanguageProfileId === profile.id ? '已选择' : profile.testStatus === 'PASSED' ? '设为当前' : '先测试' }}
                </button>
                <button class="button-secondary min-h-8 px-3 py-1 text-xs" type="button" :disabled="testingProfileId === profile.id" @click="testConnection(profile)">
                  <LoaderCircle v-if="testingProfileId === profile.id" :size="13" class="animate-spin" />
                  {{ testingProfileId === profile.id ? '测试中' : '测试' }}
                </button>
                <button
                  class="icon-button h-8 w-8 text-ink-400 hover:text-coral-700 disabled:cursor-not-allowed disabled:opacity-35"
                  type="button"
                  :disabled="!canDeleteConnection(profile)"
                  :title="canDeleteConnection(profile) ? '删除连接' : '请先切换到其他模型，再删除此连接'"
                  @click="requestDeleteConnection(profile)"
                >
                  <Trash2 :size="15" aria-hidden="true" />
                </button>
              </div>
            </div>
            <p v-if="!languageProfiles.length" class="py-10 text-center text-sm text-ink-500">还没有语言模型连接。添加并测试一个连接后再配置任务。</p>
          </div>
        </section>

        <section v-else-if="activeTab === 'runtime'">
          <h2 class="text-lg font-semibold text-ink-950">任务与回答</h2>
          <p class="mt-1 text-sm leading-6 text-ink-500">统一语言模型与回答参数对所有后续运行生效，包括已有会话中的新问题。</p>

          <div class="mt-7 space-y-7">
            <label class="config-row">
              <span><strong>语言模型</strong><small>同一个模型负责理解问题、改写、规划和生成最终回答</small></span>
              <select :value="selectedLanguageProfileId" class="control w-80" :disabled="Boolean(activatingProfileId)" @change="selectLanguageModel"><option v-for="profile in languageProfiles" :key="profile.id" :value="profile.id" :disabled="profile.testStatus !== 'PASSED'">{{ profile.name }} · {{ profile.modelName }}</option></select>
            </label>
            <label class="config-row">
              <span><strong>重排模型</strong><small>为检索候选计算相关性顺序</small></span>
              <select v-model="pipeline.rerankProfileId" class="control w-80"><option v-for="profile in rerankProfiles" :key="profile.id" :value="profile.id">{{ profile.name }} · {{ profile.modelName }}</option></select>
            </label>
            <label class="config-row items-start">
              <span><strong>回答 Temperature</strong><small>只影响最终答案，不影响规划、改写和证据判断</small></span>
              <span class="w-80">
                <span class="flex items-center gap-3"><input v-model.number="pipeline.temperature" class="w-full accent-brand-600" type="range" min="0" max="2" step="0.1" /><output class="w-10 text-right text-sm font-semibold tabular-nums">{{ Number(pipeline.temperature ?? 0).toFixed(1) }}</output></span>
                <span class="mt-1 flex justify-between text-[11px] text-ink-400"><span>稳定</span><span>更有变化</span></span>
              </span>
            </label>
          </div>

          <div class="mt-10 border-t border-paper-200 pt-7">
            <div class="flex items-start justify-between gap-6">
              <div><h3 class="text-sm font-semibold text-ink-900">发布前预览</h3><p class="mt-1 text-xs leading-5 text-ink-500">使用草稿中的统一语言模型、Temperature 和助手角色发起一次真实请求，不写入会话历史。</p></div>
              <span v-if="config?.previewReady" class="inline-flex items-center gap-1.5 text-xs font-semibold text-evidence-700"><Check :size="14" />已验证</span>
            </div>
            <textarea v-model="previewQuery" class="control mt-4 min-h-24 resize-y" maxlength="1200" />
            <div class="mt-3 flex justify-end"><button class="button-primary" type="button" :disabled="!hasDraft || previewing" @click="runPreview"><Send :size="15" />{{ previewing ? '生成中…' : '运行预览' }}</button></div>
            <div v-if="preview" class="mt-5 border-l-2 border-brand-600 pl-4">
              <p class="text-sm leading-7 text-ink-800">{{ preview.answer }}</p>
              <p class="mt-2 text-[11px] text-ink-400">{{ preview.modelName }} · Temperature {{ preview.temperature }}</p>
            </div>
          </div>
        </section>

        <section v-else-if="activeTab === 'assistant'">
          <h2 class="text-lg font-semibold text-ink-950">助手角色</h2>
          <p class="mt-1 text-sm leading-6 text-ink-500">组织只有一个默认角色。发布后仅新建会话使用新版本，已有会话继续保持原角色。</p>
          <div class="mt-7 space-y-5">
            <label class="field-label">助手名称<input v-model="assistant.assistantName" class="field-input text-base font-semibold" maxlength="80" /></label>
            <label class="field-label">身份与职责<textarea v-model="assistant.identity" class="field-input min-h-28 resize-y leading-6" maxlength="1000" /></label>
            <label class="field-label">能力范围 <span class="font-normal text-ink-400">每行一项，最多 12 项</span><textarea v-model="capabilitiesText" class="field-input min-h-28 resize-y leading-6" /></label>
            <label class="field-label">表达风格<textarea v-model="assistant.tone" class="field-input min-h-20 resize-y leading-6" maxlength="500" /></label>
            <label class="field-label">行为边界 <span class="font-normal text-ink-400">每行一项</span><textarea v-model="boundariesText" class="field-input min-h-28 resize-y leading-6" /></label>
            <label class="field-label">补充要求 <span class="font-normal text-ink-400">不能覆盖引用、证据和禁止编造等平台规则</span><textarea v-model="assistant.additionalInstructions" class="field-input min-h-32 resize-y leading-6" maxlength="4000" /></label>
          </div>
        </section>

        <section v-else>
          <h2 class="text-lg font-semibold text-ink-950">高级检索</h2>
          <p class="mt-1 text-sm leading-6 text-ink-500">这些参数直接影响召回规模和运行成本。日常调整模型与角色时无需修改。</p>
          <details class="group mt-7 border-y border-paper-200 py-4" open>
            <summary class="flex list-none items-center justify-between text-sm font-semibold text-ink-900">检索与证据预算<ChevronDown :size="16" class="transition-transform group-open:rotate-180" /></summary>
            <div class="mt-5 grid grid-cols-3 gap-4">
              <label class="field-label">关键词 Top K<input v-model.number="pipeline.keywordTopK" class="field-input" type="number" min="1" max="100" /></label>
              <label class="field-label">语义 Top K<input v-model.number="pipeline.semanticTopK" class="field-input" type="number" min="1" max="100" /></label>
              <label class="field-label">RRF 候选上限<input v-model.number="pipeline.rrfCandidateLimit" class="field-input" type="number" min="1" max="100" /></label>
              <label class="field-label">重排候选上限<input v-model.number="pipeline.rerankCandidateLimit" class="field-input" type="number" min="1" max="100" /></label>
              <label class="field-label">最低重排分数<input v-model.number="pipeline.minimumRerankScore" class="field-input" type="number" min="0" max="1" step="0.01" /></label>
              <label class="field-label">最终引用上限<input v-model.number="pipeline.maxFinalReferences" class="field-input" type="number" min="1" max="64" /></label>
              <label class="field-label">最大检索轮次<input v-model.number="pipeline.maxRetrievalRounds" class="field-input" type="number" min="1" max="20" /></label>
              <label class="field-label">子 Query 配额<input v-model.number="pipeline.maxSubQueries" class="field-input" type="number" min="1" max="32" /></label>
              <label class="field-label">Deep Read 上限<input v-model.number="pipeline.maxDeepReadCalls" class="field-input" type="number" min="1" max="100" /></label>
            </div>
          </details>
          <details class="group border-b border-paper-200 py-4">
            <summary class="flex list-none items-center justify-between text-sm font-semibold text-ink-900">超时与上下文<ChevronDown :size="16" class="transition-transform group-open:rotate-180" /></summary>
            <div class="mt-5 grid grid-cols-3 gap-4">
              <label class="field-label">最近对话轮数<input v-model.number="pipeline.recentTurns" class="field-input" type="number" min="1" max="20" /></label>
              <label class="field-label">LLM 超时（秒）<input v-model.number="pipeline.llmTimeoutSeconds" class="field-input" type="number" min="5" max="600" /></label>
              <label class="field-label">Deep 总预算（秒）<input v-model.number="pipeline.agenticLoopTimeoutSeconds" class="field-input" type="number" min="30" max="1800" /></label>
              <label class="field-label">最大输出 Token<input v-model.number="pipeline.maxCompletionTokens" class="field-input" type="number" min="1" max="16384" /></label>
              <label class="field-label">上下文 Token 预算<input v-model.number="pipeline.contextTokenBudget" class="field-input" type="number" min="500" max="32000" /></label>
              <label class="field-label">FAST 超时（秒）<input v-model.number="pipeline.fastTimeoutSeconds" class="field-input" type="number" min="5" max="300" /></label>
            </div>
          </details>
          <p class="mt-5 text-xs leading-5 text-ink-400">当前运行版本：{{ config?.publishedPipeline.pipelineVersion }} · Prompt：{{ config?.publishedPipeline.promptVersion }}</p>
          <div class="mt-9 border-t border-paper-200 pt-6">
            <h3 class="text-sm font-semibold text-ink-900">版本记录</h3>
            <p class="mt-1 text-xs leading-5 text-ink-500">恢复会创建一份新草稿，不会直接改变线上配置。</p>
            <div class="mt-4 divide-y divide-paper-200 border-y border-paper-200">
              <div v-for="version in versions.filter((item) => item.status !== 'DRAFT').slice(0, 8)" :key="version.id" class="grid grid-cols-[90px_minmax(0,1fr)_100px] items-center gap-4 py-3">
                <span class="text-[11px] font-semibold text-ink-400">{{ version.kind === 'PIPELINE' ? '运行配置' : '助手角色' }} v{{ version.version }}</span>
                <span class="truncate text-sm text-ink-700">{{ version.name }}</span>
                <button class="button-secondary min-h-8 px-3 py-1 text-xs" type="button" :disabled="saving" @click="restoreVersion(version)">恢复</button>
              </div>
            </div>
          </div>
        </section>
      </main>
    </div>
  </section>

  <ModalDialog
    :open="Boolean(deleteTarget)"
    title="删除模型连接"
    description="连接会从可用列表中移除，已保存的 API Key 将被清除；历史配置记录仍会保留。"
    @close="deleteTarget = null"
  >
    <p class="text-sm leading-6 text-ink-700">
      确认删除 <strong class="font-semibold text-ink-950">{{ deleteTarget?.name }}</strong>？此操作不可在页面中撤销。
    </p>
    <div class="mt-6 flex justify-end gap-2">
      <button class="button-secondary" type="button" :disabled="Boolean(deletingProfileId)" @click="deleteTarget = null">取消</button>
      <button class="button-primary bg-coral-600 hover:bg-coral-700" type="button" :disabled="Boolean(deletingProfileId)" @click="deleteConnection">
        <LoaderCircle v-if="deletingProfileId" :size="15" class="animate-spin" />
        {{ deletingProfileId ? '删除中…' : '确认删除' }}
      </button>
    </div>
  </ModalDialog>
</template>

<style scoped>
.config-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 32px;
  padding-bottom: 24px;
  border-bottom: 1px solid #e2e8f0;
}

.config-row > span:first-child {
  min-width: 0;
}

.config-row strong,
.config-row small {
  display: block;
}

.config-row strong {
  color: #172033;
  font-size: 14px;
  font-weight: 600;
}

.config-row small {
  margin-top: 4px;
  color: #7c8ba1;
  font-size: 12px;
  line-height: 1.5;
}
</style>
