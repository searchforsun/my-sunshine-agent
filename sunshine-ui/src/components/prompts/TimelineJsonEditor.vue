<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NFormItem, NInput } from 'naive-ui'

const props = defineProps<{
  modelValue: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

/** 常见时间线字段中文名；未知键仍显示原 key */
const KEY_LABELS: Record<string, string> = {
  label: '步骤名',
  'label-follow-up': '后续步骤名',
  before: '开始前',
  active: '进行中',
  progress: '进展中',
  after: '完成后',
  'after-default': '完成后（默认）',
  'after-fallback': '完成后（兜底）',
  'after-no-context': '完成后（无上下文）',
  'after-outline': '完成后（有大纲）',
  'after-zero-hits': '完成后（零命中）',
  'after-with-hits': '完成后（有命中）',
  'before-fallback': '开始前（兜底）',
  'active-fallback': '进行中（兜底）',
  'before-follow-up': '后续开始前',
  'active-follow-up': '后续进行中',
  'after-follow-up': '后续完成后',
  'before-follow-up-no-tool': '后续开始前（无工具）',
  'active-follow-up-no-tool': '后续进行中（无工具）',
  'after-follow-up-no-tool': '后续完成后（无工具）',
  'before-follow-up-fallback': '后续开始前（兜底）',
  'active-follow-up-fallback': '后续进行中（兜底）',
  'after-follow-up-fallback': '后续完成后（兜底）',
  pending: '待确认',
  awaiting: '等待中',
  approved: '已确认',
  denied: '已拒绝',
  regenerating: '重新规划中',
  'timed-out': '已超时',
  'skipped-after': '跳过完成后',
  'default-after': '默认完成后',
  'unmatched-after': '未匹配完成后',
  detail: '详情文案',
  'forced-after': '强制模式完成后',
  'hits-with-sources': '有命中（含来源）',
  'hits-with-query': '有命中（含问句）',
  'zero-hits': '零命中',
  'generic-done': '通用完成',
  intent: '意图',
  planner: '规划',
  modes: '各执行模式',
  plan: '计划',
  think: '推理',
  tool: '工具',
  generate: '生成',
  rag: '检索',
  node: '节点',
  agent: '智能体',
  'expert-convene': '召集专家(已退役)',
  'peer-collab': '多专家协作(已退役)',
  'read-after': '读取完成',
  'write-after': '写入完成',
  'edit-after': '编辑完成',
  'glob-after': 'glob 完成',
  'glob-after-with-path': 'glob 完成（含路径）',
  'grep-after': 'grep 完成',
  'exec-after': 'exec 完成',
  'read-active': '读取中',
  'write-active': '写入中',
  'edit-active': '编辑中',
  'glob-active': 'glob 中',
  'grep-active': 'grep 中',
  'exec-active': 'exec 中',
}

type FieldNode =
  | { type: 'string'; key: string; value: string }
  | { type: 'group'; key: string; children: FieldNode[] }
  | { type: 'raw'; key: string; value: string }

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return v != null && typeof v === 'object' && !Array.isArray(v)
}

function fieldLabel(key: string): string {
  return KEY_LABELS[key] ?? key
}

function toNodes(obj: Record<string, unknown>): FieldNode[] {
  return Object.entries(obj).map(([key, value]) => {
    if (typeof value === 'string') {
      return { type: 'string', key, value }
    }
    if (isPlainObject(value)) {
      const entries = Object.entries(value)
      if (entries.length === 0 || entries.every(([, v]) => typeof v === 'string' || isPlainObject(v))) {
        return { type: 'group', key, children: toNodes(value) }
      }
    }
    return { type: 'raw', key, value: JSON.stringify(value, null, 2) }
  })
}

function nodesToObject(nodes: FieldNode[]): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const node of nodes) {
    if (node.type === 'string') {
      out[node.key] = node.value
    } else if (node.type === 'group') {
      out[node.key] = nodesToObject(node.children)
    } else {
      try {
        out[node.key] = JSON.parse(node.value)
      } catch {
        out[node.key] = node.value
      }
    }
  }
  return out
}

const parseError = ref<string | null>(null)
const nodes = ref<FieldNode[]>([])
let syncing = false

function loadFromText(text: string) {
  const raw = text.trim()
  if (!raw) {
    nodes.value = []
    parseError.value = null
    return
  }
  try {
    const parsed = JSON.parse(raw) as unknown
    if (!isPlainObject(parsed)) {
      parseError.value = '内容须为 JSON 对象'
      nodes.value = []
      return
    }
    parseError.value = null
    nodes.value = toNodes(parsed)
  } catch {
    parseError.value = 'JSON 解析失败，请检查格式'
    nodes.value = []
  }
}

function emitFromNodes() {
  if (parseError.value) return
  syncing = true
  emit('update:modelValue', JSON.stringify(nodesToObject(nodes.value)))
  queueMicrotask(() => { syncing = false })
}

watch(
  () => props.modelValue,
  (v) => {
    if (syncing) return
    loadFromText(v ?? '')
  },
  { immediate: true },
)

const locked = computed(() => !!props.disabled)

function updateString(path: number[], value: string) {
  let list = nodes.value
  for (let i = 0; i < path.length - 1; i++) {
    const node = list[path[i]]
    if (!node || node.type !== 'group') return
    list = node.children
  }
  const leaf = list[path[path.length - 1]]
  if (!leaf || (leaf.type !== 'string' && leaf.type !== 'raw')) return
  leaf.value = value
  emitFromNodes()
}
</script>

<template>
  <div class="timeline-editor">
    <p v-if="parseError" class="parse-error">{{ parseError }}</p>
    <template v-else-if="nodes.length">
      <div
        v-for="(node, index) in nodes"
        :key="node.key"
        class="field-block"
      >
        <template v-if="node.type === 'string'">
          <NFormItem :show-feedback="false">
            <template #label>
              <span class="field-label">
                {{ fieldLabel(node.key) }}
                <span class="field-key">{{ node.key }}</span>
              </span>
            </template>
            <NInput
              :value="node.value"
              class="sun-field"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              :disabled="locked"
              @update:value="(v: string) => updateString([index], v)"
            />
          </NFormItem>
        </template>

        <template v-else-if="node.type === 'group'">
          <div class="group-card">
            <header class="group-head">
              <span class="group-title">{{ fieldLabel(node.key) }}</span>
              <span class="field-key">{{ node.key }}</span>
            </header>
            <div class="group-body">
              <div
                v-for="(child, cIndex) in node.children"
                :key="child.key"
                class="field-block"
              >
                <template v-if="child.type === 'string'">
                  <NFormItem :show-feedback="false">
                    <template #label>
                      <span class="field-label">
                        {{ fieldLabel(child.key) }}
                        <span class="field-key">{{ child.key }}</span>
                      </span>
                    </template>
                    <NInput
                      :value="child.value"
                      class="sun-field"
                      type="textarea"
                      :autosize="{ minRows: 1, maxRows: 4 }"
                      :disabled="locked"
                      @update:value="(v: string) => updateString([index, cIndex], v)"
                    />
                  </NFormItem>
                </template>
                <template v-else-if="child.type === 'group'">
                  <div class="group-card nested">
                    <header class="group-head">
                      <span class="group-title">{{ fieldLabel(child.key) }}</span>
                      <span class="field-key">{{ child.key }}</span>
                    </header>
                    <div class="group-body">
                      <NFormItem
                        v-for="(grand, gIndex) in child.children"
                        :key="grand.key"
                        :show-feedback="false"
                      >
                        <template #label>
                          <span class="field-label">
                            {{ fieldLabel(grand.key) }}
                            <span class="field-key">{{ grand.key }}</span>
                          </span>
                        </template>
                        <NInput
                          v-if="grand.type === 'string' || grand.type === 'raw'"
                          :value="grand.value"
                          class="sun-field"
                          type="textarea"
                          :autosize="{ minRows: 1, maxRows: 6 }"
                          :disabled="locked"
                          @update:value="(v: string) => updateString([index, cIndex, gIndex], v)"
                        />
                      </NFormItem>
                    </div>
                  </div>
                </template>
                <template v-else>
                  <NFormItem :show-feedback="false">
                    <template #label>
                      <span class="field-label">
                        {{ fieldLabel(child.key) }}
                        <span class="field-key">{{ child.key }}</span>
                      </span>
                    </template>
                    <NInput
                      :value="child.value"
                      class="sun-field mono"
                      type="textarea"
                      :autosize="{ minRows: 3, maxRows: 12 }"
                      :disabled="locked"
                      @update:value="(v: string) => updateString([index, cIndex], v)"
                    />
                  </NFormItem>
                </template>
              </div>
            </div>
          </div>
        </template>

        <template v-else>
          <NFormItem :show-feedback="false">
            <template #label>
              <span class="field-label">
                {{ fieldLabel(node.key) }}
                <span class="field-key">{{ node.key }}</span>
              </span>
            </template>
            <NInput
              :value="node.value"
              class="sun-field mono"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 12 }"
              :disabled="locked"
              @update:value="(v: string) => updateString([index], v)"
            />
          </NFormItem>
        </template>
      </div>
    </template>
    <p v-else class="empty-hint">暂无字段</p>
  </div>
</template>

<style scoped>
.timeline-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.field-block {
  width: 100%;
}

.field-block :deep(.n-form-item) {
  margin-bottom: 0;
}

.field-block :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
  font-weight: 500;
  padding-bottom: 8px;
}

.field-label {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
}

.field-key {
  font-size: 11px;
  font-weight: 400;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono, monospace);
}

.group-card {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.group-card.nested {
  margin-top: 4px;
}

.group-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
}

.group-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.parse-error,
.empty-hint {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.parse-error {
  color: #d03050;
}

.mono :deep(.n-input__textarea-el) {
  font-family: var(--sun-font-mono, monospace);
  font-size: 12px;
}
</style>
