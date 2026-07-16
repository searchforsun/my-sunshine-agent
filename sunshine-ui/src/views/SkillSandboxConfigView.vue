<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  NButton,
  NDynamicTags,
  NEmpty,
  NInput,
  NInputNumber,
  NSelect,
  NSpin,
  useMessage,
} from 'naive-ui'
import {
  listSkillVersions,
  updateSkillVersionSandbox,
  type SandboxPolicy,
  type SkillVersion,
} from '../api/skills'
import { friendlyErrorMessage } from '../api/apiError'
import { formatSkillVersionTime } from '../utils/formatSkillVersionTime'

const DEFAULT_POLICY: Required<Pick<
  SandboxPolicy,
  'runtime' | 'image' | 'timeoutSec' | 'memoryMb' | 'cpus'
>> & {
  networkAllow: string[]
  execReadonlyAllow: string[]
} = {
  runtime: 'docker',
  image: 'sunshine-sandbox-python:3.11-slim',
  timeoutSec: 30,
  memoryMb: 256,
  cpus: 0.5,
  networkAllow: [],
  execReadonlyAllow: ['ls *', 'pwd', 'python -m pytest *'],
}

const SANDBOX_OPTIONS = [
  { label: '关闭（none）', value: 'none' },
  { label: 'Docker 沙箱', value: 'docker' },
]

const route = useRoute()
const router = useRouter()
const message = useMessage()

const loading = ref(true)
const saving = ref(false)
const versions = ref<SkillVersion[]>([])

const skillId = computed(() => String(route.params.skillId ?? '').trim())
const versionNum = computed(() => {
  const q = route.query.version
  const n = typeof q === 'string' ? Number.parseInt(q, 10) : Number.NaN
  return Number.isFinite(n) && n > 0 ? n : null
})

const currentVersion = computed(() => {
  if (versionNum.value != null) {
    return versions.value.find(v => v.version === versionNum.value) ?? null
  }
  return versions.value.find(v => v.status === 'published' && v.storagePath)
    ?? versions.value[0]
    ?? null
})

const sandboxType = ref<'none' | 'docker'>('none')
const image = ref(DEFAULT_POLICY.image)
const timeoutSec = ref(DEFAULT_POLICY.timeoutSec)
const memoryMb = ref(DEFAULT_POLICY.memoryMb)
const cpus = ref(DEFAULT_POLICY.cpus)
const networkAllow = ref<string[]>([])
const execReadonlyAllow = ref<string[]>([...DEFAULT_POLICY.execReadonlyAllow])

const versionLabel = computed(() => {
  const v = currentVersion.value
  if (!v) return '—'
  const t = formatSkillVersionTime(v.createdAt)
  return t !== '—' ? `v${v.version} · ${t}` : `v${v.version}`
})

const dockerEnabled = computed(() => sandboxType.value === 'docker')

function parsePolicy(raw: string | null | undefined): SandboxPolicy {
  if (!raw?.trim()) return { ...DEFAULT_POLICY }
  try {
    return { ...DEFAULT_POLICY, ...(JSON.parse(raw) as SandboxPolicy) }
  } catch {
    return { ...DEFAULT_POLICY }
  }
}

function applyFromVersion(ver: SkillVersion | null) {
  if (!ver) {
    sandboxType.value = 'none'
    return
  }
  sandboxType.value = ver.sandbox === 'docker' ? 'docker' : 'none'
  const p = parsePolicy(ver.sandboxPolicyJson)
  image.value = (p.image?.trim() || DEFAULT_POLICY.image)
  timeoutSec.value = p.timeoutSec ?? DEFAULT_POLICY.timeoutSec
  memoryMb.value = p.memoryMb ?? DEFAULT_POLICY.memoryMb
  cpus.value = p.cpus ?? DEFAULT_POLICY.cpus
  networkAllow.value = [...(p.networkAllow ?? [])]
  execReadonlyAllow.value = [...(p.execReadonlyAllow?.length
    ? p.execReadonlyAllow
    : DEFAULT_POLICY.execReadonlyAllow)]
}

function goBack() {
  void router.push({ name: 'skills' })
}

function handleTryRun() {
  if (!skillId.value) return
  const prompt = `@${skillId.value} 请用沙箱工具：读取 /skill 下脚本，在 /workspace 写 test.txt，再 ls`
  void router.push({ name: 'chat', query: { prompt } })
}

function buildPolicy(): SandboxPolicy | null {
  if (sandboxType.value !== 'docker') return null
  return {
    runtime: 'docker',
    image: (image.value ?? '').trim() || DEFAULT_POLICY.image,
    timeoutSec: timeoutSec.value ?? DEFAULT_POLICY.timeoutSec,
    memoryMb: memoryMb.value ?? DEFAULT_POLICY.memoryMb,
    cpus: cpus.value ?? DEFAULT_POLICY.cpus,
    networkAllow: networkAllow.value.map(s => s.trim()).filter(Boolean),
    execReadonlyAllow: execReadonlyAllow.value.map(s => s.trim()).filter(Boolean),
  }
}

async function handleSave() {
  const ver = currentVersion.value
  if (!skillId.value || !ver) {
    message.error('未找到可配置的版本')
    return
  }
  if (sandboxType.value === 'docker' && !(image.value ?? '').trim()) {
    message.error('请填写镜像名')
    return
  }
  saving.value = true
  try {
    await updateSkillVersionSandbox(
      skillId.value,
      ver.version,
      sandboxType.value,
      buildPolicy(),
    )
    message.success('沙箱配置已保存')
    versions.value = await listSkillVersions(skillId.value)
    applyFromVersion(currentVersion.value)
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '保存沙箱配置失败'))
  } finally {
    saving.value = false
  }
}

async function load() {
  loading.value = true
  try {
    if (!skillId.value) {
      message.error('缺少 Skill ID')
      return
    }
    versions.value = await listSkillVersions(skillId.value)
    const ver = currentVersion.value
    if (ver && versionNum.value == null) {
      void router.replace({
        name: 'skill-sandbox',
        params: { skillId: skillId.value },
        query: { version: String(ver.version) },
      })
    }
    applyFromVersion(ver)
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '加载版本失败'))
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

watch(currentVersion, (ver) => {
  if (!loading.value) applyFromVersion(ver)
})
</script>

<template>
  <div class="sandbox-config-root">
    <header class="page-header">
      <div class="header-left">
        <NButton size="small" quaternary @click="goBack">返回</NButton>
        <div class="title-block">
          <h2>沙箱配置</h2>
          <p class="subtitle">
            <span class="skill-id">{{ skillId || '—' }}</span>
            <span class="sep">·</span>
            <span>{{ versionLabel }}</span>
          </p>
        </div>
      </div>
      <div class="header-actions">
        <NButton size="small" quaternary :disabled="!skillId || saving" @click="handleTryRun">
          试跑
        </NButton>
        <NButton
          size="small"
          type="primary"
          class="action-btn"
          :loading="saving"
          :disabled="loading || !currentVersion"
          @click="handleSave"
        >
          保存
        </NButton>
      </div>
    </header>

    <NSpin :show="loading" class="body-spin">
      <div v-if="!loading && !currentVersion" class="empty-wrap">
        <NEmpty description="该 Skill 尚无可配置版本，请先上传包文件" />
      </div>
      <form v-else class="form-card" @submit.prevent="handleSave">
        <section class="form-section">
          <h3 class="section-title">运行时</h3>
          <label class="field">
            <span class="field-label">沙箱类型</span>
            <NSelect
              v-model:value="sandboxType"
              :options="SANDBOX_OPTIONS"
              size="small"
              class="sun-field field-control"
              :menu-props="{ class: 'version-select-menu' }"
            />
          </label>
          <p class="field-hint">
            关闭后 Agent 不注入 sandbox__* 工具；开启 Docker 后挂载 /skill（只读）与 /workspace（可写）。
          </p>
        </section>

        <section v-if="dockerEnabled" class="form-section">
          <h3 class="section-title">容器资源</h3>
          <label class="field">
            <span class="field-label">镜像</span>
            <NInput
              v-model:value="image"
              size="small"
              class="sun-field field-control"
              placeholder="sunshine-sandbox-python:3.11-slim"
            />
          </label>
          <div class="field-grid">
            <label class="field">
              <span class="field-label">超时（秒）</span>
              <NInputNumber
                v-model:value="timeoutSec"
                size="small"
                class="sun-field field-control"
                :min="1"
                :max="600"
                :show-button="false"
              />
            </label>
            <label class="field">
              <span class="field-label">内存（MB）</span>
              <NInputNumber
                v-model:value="memoryMb"
                size="small"
                class="sun-field field-control"
                :min="64"
                :max="4096"
                :show-button="false"
              />
            </label>
            <label class="field">
              <span class="field-label">CPU</span>
              <NInputNumber
                v-model:value="cpus"
                size="small"
                class="sun-field field-control"
                :min="0.1"
                :max="4"
                :step="0.1"
                :show-button="false"
              />
            </label>
          </div>
        </section>

        <section v-if="dockerEnabled" class="form-section">
          <h3 class="section-title">网络与 HITL</h3>
          <label class="field">
            <span class="field-label">出网白名单</span>
            <NDynamicTags
              v-model:value="networkAllow"
              size="small"
              class="field-tags"
            />
            <span class="field-hint">空 = network=none；填写域名或 CIDR 后经 egress 代理放行</span>
          </label>
          <label class="field">
            <span class="field-label">免确认 exec 命令</span>
            <NDynamicTags
              v-model:value="execReadonlyAllow"
              size="small"
              class="field-tags"
            />
            <span class="field-hint">支持简单通配（如 ls *）；未命中的 exec 与写文件仍需 HITL</span>
          </label>
        </section>
      </form>
    </NSpin>
  </div>
</template>

<style scoped>
.sandbox-config-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 16px;
  box-sizing: border-box;
  overflow: auto;
  background: var(--sun-black);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  min-width: 0;
}

.title-block h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: var(--sun-text);
}

.subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--sun-text-secondary);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.skill-id {
  font-family: var(--sun-font-mono, ui-monospace, monospace);
  color: var(--sun-text);
}

.sep {
  color: var(--sun-text-muted);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.action-btn {
  min-width: 72px;
}

.body-spin {
  flex: 1;
  min-height: 0;
}

.body-spin :deep(.n-spin-content) {
  height: 100%;
}

.empty-wrap {
  padding: 48px 0;
}

.form-card {
  max-width: 640px;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 16px 18px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
}

.form-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.section-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text-secondary);
  letter-spacing: 0.02em;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 13px;
  color: var(--sun-text);
}

.field-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--sun-text-muted);
}

.field-control {
  width: 100%;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.field-tags {
  min-height: 32px;
}

.form-card :deep(.n-base-selection),
.form-card :deep(.n-input),
.form-card :deep(.n-input-number) {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.form-card :deep(.n-input-number) {
  width: 100%;
}

.form-card :deep(.n-dynamic-tags .n-tag) {
  background: transparent;
  border: 1px solid var(--sun-border);
  color: var(--sun-text);
}

@media (max-width: 720px) {
  .field-grid {
    grid-template-columns: 1fr;
  }
}
</style>
