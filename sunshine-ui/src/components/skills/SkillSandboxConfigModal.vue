<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  NButton,
  NDynamicTags,
  NForm,
  NFormItem,
  NInput,
  NInputNumber,
  NModal,
  NSelect,
  useMessage,
} from 'naive-ui'
import {
  listSkillVersions,
  updateSkillVersionSandbox,
  type SandboxPolicy,
  type SkillVersion,
} from '../../api/skills'
import { friendlyErrorMessage } from '../../api/apiError'
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'

const props = defineProps<{
  show: boolean
  skillId: string
  version: number | null
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
  saved: []
}>()

const DEFAULT_POLICY = {
  runtime: 'docker' as const,
  image: 'sunshine-sandbox-python:3.11-slim',
  timeoutSec: 30,
  memoryMb: 256,
  cpus: 0.5,
  networkAllow: [] as string[],
  execReadonlyAllow: ['ls *', 'pwd', 'python -m pytest *'],
}

const SANDBOX_OPTIONS = [
  { label: 'none', value: 'none' },
  { label: 'docker', value: 'docker' },
]

const HELP = {
  type: '关闭后不注入 sandbox__* 工具。\ndocker：会话级容器，挂载 /skill（只读）与 /workspace（可写）。',
  image: '容器镜像名，默认 sunshine-sandbox-python:3.11-slim。',
  timeoutSec: '单次 sandbox__exec 超时秒数，超时强杀并返回失败。',
  memoryMb: '容器内存上限（MB）。',
  cpus: '容器 CPU 限额（可小数，如 0.5）。',
  networkAllow: '空列表 = network=none。\n填写域名或 CIDR 后经 egress 白名单代理出网。',
  execReadonlyAllow: '命中的 exec 命令免 HITL（支持简单通配如 ls *）。\n写文件与未命中命令仍需确认。',
}

const router = useRouter()
const message = useMessage()
const loading = ref(false)
const saving = ref(false)
const versionRow = ref<SkillVersion | null>(null)

const sandboxType = ref<'none' | 'docker'>('none')
const image = ref(DEFAULT_POLICY.image)
const timeoutSec = ref<number | null>(DEFAULT_POLICY.timeoutSec)
const memoryMb = ref<number | null>(DEFAULT_POLICY.memoryMb)
const cpus = ref<number | null>(DEFAULT_POLICY.cpus)
const networkAllow = ref<string[]>([])
const execReadonlyAllow = ref<string[]>([...DEFAULT_POLICY.execReadonlyAllow])

const dockerEnabled = computed(() => sandboxType.value === 'docker')
const canSave = computed(() => !!props.skillId && props.version != null && !!versionRow.value)

function parsePolicy(raw: string | null | undefined): SandboxPolicy {
  if (!raw?.trim()) return { ...DEFAULT_POLICY }
  try {
    return { ...DEFAULT_POLICY, ...(JSON.parse(raw) as SandboxPolicy) }
  } catch {
    return { ...DEFAULT_POLICY }
  }
}

function applyFromVersion(ver: SkillVersion | null) {
  versionRow.value = ver
  if (!ver) {
    sandboxType.value = 'none'
    return
  }
  sandboxType.value = ver.sandbox === 'docker' ? 'docker' : 'none'
  const p = parsePolicy(ver.sandboxPolicyJson)
  image.value = p.image?.trim() || DEFAULT_POLICY.image
  timeoutSec.value = p.timeoutSec ?? DEFAULT_POLICY.timeoutSec
  memoryMb.value = p.memoryMb ?? DEFAULT_POLICY.memoryMb
  cpus.value = p.cpus ?? DEFAULT_POLICY.cpus
  networkAllow.value = [...(p.networkAllow ?? [])]
  execReadonlyAllow.value = [...(p.execReadonlyAllow?.length
    ? p.execReadonlyAllow
    : DEFAULT_POLICY.execReadonlyAllow)]
}

async function load() {
  if (!props.skillId || props.version == null) {
    applyFromVersion(null)
    return
  }
  loading.value = true
  try {
    const versions = await listSkillVersions(props.skillId)
    applyFromVersion(versions.find(v => v.version === props.version) ?? null)
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '加载沙箱配置失败'))
    applyFromVersion(null)
  } finally {
    loading.value = false
  }
}

function close() {
  emit('update:show', false)
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
  if (!props.skillId || props.version == null || !versionRow.value) return
  if (sandboxType.value === 'docker' && !(image.value ?? '').trim()) {
    message.error('请填写镜像名')
    return
  }
  saving.value = true
  try {
    await updateSkillVersionSandbox(
      props.skillId,
      props.version,
      sandboxType.value,
      buildPolicy(),
    )
    message.success('沙箱配置已保存')
    emit('saved')
    close()
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '保存沙箱配置失败'))
  } finally {
    saving.value = false
  }
}

function handleTryRun() {
  if (!props.skillId) return
  const prompt = `@${props.skillId} 请用沙箱工具：读取 /skill 下脚本，在 /workspace 写 test.txt，再 ls`
  close()
  void router.push({ name: 'chat', query: { prompt } })
}

watch(
  () => props.show,
  (open) => {
    if (open) void load()
  },
)
</script>

<template>
  <NModal
    :show="show"
    preset="dialog"
    title="沙箱配置"
    class="sunshine-dialog sandbox-config-dialog"
    style="width: min(520px, 94vw)"
    @update:show="emit('update:show', $event)"
  >
    <NForm
      label-placement="left"
      label-width="118"
      :show-feedback="false"
      class="sandbox-form"
      :disabled="loading || saving"
    >
      <NFormItem>
        <template #label>
          <span class="field-label-row">类型<ConfigFieldHelp :text="HELP.type" /></span>
        </template>
        <NSelect
          v-model:value="sandboxType"
          :options="SANDBOX_OPTIONS"
          size="small"
          class="sun-field"
          :menu-props="{ class: 'version-select-menu' }"
        />
      </NFormItem>
      <template v-if="dockerEnabled">
        <NFormItem>
          <template #label>
            <span class="field-label-row">镜像<ConfigFieldHelp :text="HELP.image" /></span>
          </template>
          <NInput v-model:value="image" size="small" class="sun-field" />
        </NFormItem>
        <NFormItem>
          <template #label>
            <span class="field-label-row">超时（秒）<ConfigFieldHelp :text="HELP.timeoutSec" /></span>
          </template>
          <NInputNumber
            v-model:value="timeoutSec"
            size="small"
            class="sun-field field-num"
            :min="1"
            :max="600"
            :show-button="false"
          />
        </NFormItem>
        <NFormItem>
          <template #label>
            <span class="field-label-row">内存（MB）<ConfigFieldHelp :text="HELP.memoryMb" /></span>
          </template>
          <NInputNumber
            v-model:value="memoryMb"
            size="small"
            class="sun-field field-num"
            :min="64"
            :max="4096"
            :show-button="false"
          />
        </NFormItem>
        <NFormItem>
          <template #label>
            <span class="field-label-row">CPU<ConfigFieldHelp :text="HELP.cpus" /></span>
          </template>
          <NInputNumber
            v-model:value="cpus"
            size="small"
            class="sun-field field-num"
            :min="0.1"
            :max="4"
            :step="0.1"
            :show-button="false"
          />
        </NFormItem>
        <NFormItem>
          <template #label>
            <span class="field-label-row">出网白名单<ConfigFieldHelp :text="HELP.networkAllow" /></span>
          </template>
          <NDynamicTags v-model:value="networkAllow" size="small" />
        </NFormItem>
        <NFormItem>
          <template #label>
            <span class="field-label-row">免确认 exec<ConfigFieldHelp :text="HELP.execReadonlyAllow" /></span>
          </template>
          <NDynamicTags v-model:value="execReadonlyAllow" size="small" />
        </NFormItem>
      </template>
    </NForm>
    <template #action>
      <NButton quaternary :disabled="!skillId || saving" @click="handleTryRun">试跑</NButton>
      <NButton @click="close">取消</NButton>
      <NButton
        type="primary"
        class="action-btn"
        :loading="saving"
        :disabled="!canSave || loading"
        @click="handleSave"
      >
        保存
      </NButton>
    </template>
  </NModal>
</template>

<style scoped>
.field-label-row {
  display: inline-flex;
  align-items: center;
  gap: 0;
  white-space: nowrap;
}

.sandbox-form :deep(.n-form-item) {
  margin-bottom: 14px;
}

.sandbox-form :deep(.n-base-selection),
.sandbox-form :deep(.n-input),
.sandbox-form :deep(.n-input-number) {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.field-num {
  width: 100%;
}

.sandbox-form :deep(.n-dynamic-tags .n-tag) {
  background: transparent;
  border: 1px solid var(--sun-border);
  color: var(--sun-text);
}
</style>
