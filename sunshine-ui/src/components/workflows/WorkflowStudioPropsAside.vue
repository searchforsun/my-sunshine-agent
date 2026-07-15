<script setup lang="ts">
import { computed, inject } from 'vue'
import { NForm, NFormItem, NInput, NInputNumber, NSelect } from 'naive-ui'
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'
import WorkflowNodeExecutionPolicy from './WorkflowNodeExecutionPolicy.vue'
import WorkflowNodeConfigSection from './WorkflowNodeConfigSection.vue'
import WorkflowNodeIoSection from './WorkflowNodeIoSection.vue'
import PlanNodeIcon from '../plan/PlanNodeIcon.vue'
import { formatPlanNodeType } from '../../api/executionPlans'
import { workflowFlowFieldHelp, workflowNodeFieldHelp } from './workflowFieldHelp'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import {
  defaultCatalogIntentAfter,
  formatAgentToolsParam,
  mergeToolExtraParams,
  parseAgentToolsParam,
  patchKbIdFromSelect,
  patchNodeParams,
  ragKbIdEmptyLabel,
  readAgentMaxIters,
  readRagTopK,
  resolveKbSelectValue,
  SESSION_KB_VALUE,
  toolExtraParamsLines,
} from '../../utils/workflowNodeParams'
import {
  parseToolSchemaFields,
  readToolParamValue,
  type ToolOutputMode,
} from '../../utils/workflowNodeIo'
import { countNodeDegree } from '../../utils/workflowPlanValidation'
import { exclusiveGatewayConditionLeft, loopConditionLeft } from '../../utils/workflowPlan'

defineProps<{
  open: boolean
  showExpandBtn?: boolean
}>()

const emit = defineEmits<{
  'update:open': [value: boolean]
}>()

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const toolSelectOptions = computed(() =>
  page.toolOptions.map(t => ({
    label: `${t.displayName || t.id} (${t.id})`,
    value: t.id,
  })),
)

const skillSelectOptions = computed(() =>
  page.skillOptions.map(s => ({
    label: `${s.displayName} (${s.id})`,
    value: s.id,
  })),
)

const selectedToolCatalog = computed(() => {
  const toolId = String(page.selectedNode?.params?.tool ?? '').trim()
  if (!toolId) return null
  return page.toolOptions.find(t => t.id === toolId) ?? null
})

const selectedToolSchemaFields = computed(() => parseToolSchemaFields(selectedToolCatalog.value))

const kbSelectOptions = computed(() => {
  const sessionLabel = ragKbIdEmptyLabel(page.nodeDefaults)
  const options = [{ label: sessionLabel, value: SESSION_KB_VALUE }]
  for (const kb of page.kbOptions) {
    const suffix = kb.isDefault ? '（默认）' : ''
    options.push({
      label: `${kb.displayName}${suffix}`,
      value: kb.kbId,
    })
  }
  return options
})

const answerNode = computed(() =>
  page.plan?.nodes.find(n => n.type === 'answer') ?? null,
)

const isAnswerSelected = computed(() => page.selectedNode?.type === 'answer')

const propsSectionTitle = computed(() => {
  if (page.isFlowConfigSelected) return '流程配置'
  if (isAnswerSelected.value) return '终态配置'
  return '节点属性'
})

const headerTitle = computed(() => {
  if (page.isFlowConfigSelected) return page.selectedWorkflow?.displayName || '流程配置'
  if (isAnswerSelected.value) {
    return answerNode.value?.displayName?.trim() || '回答'
  }
  const node = page.selectedNode
  if (!node) return propsSectionTitle.value
  return node.displayName?.trim() || node.id
})

const headerSubtitle = computed(() => {
  if (page.isFlowConfigSelected) return '元数据与路由配置'
  if (isAnswerSelected.value) return formatPlanNodeType('answer')
  const node = page.selectedNode
  if (!node) return '点击画布节点以编辑'
  return formatPlanNodeType(node.type)
})

const headerNodeType = computed(() => {
  if (page.isFlowConfigSelected) return 'start'
  if (isAnswerSelected.value) return 'answer'
  return page.selectedNode?.type ?? 'llm'
})

const readOnly = computed(() => !page.canEditPlan)

const joinTopology = computed(() => {
  const node = page.selectedNode
  if (!node || node.type !== 'join' || !page.plan) return null
  const degree = countNodeDegree(page.plan, node.id)
  const okIn = degree.in >= 2
  const okOut = degree.out === 1
  return { ...degree, okIn, okOut }
})

const gatewayTopology = computed(() => {
  const node = page.selectedNode
  if (!node || !page.plan) return null
  if (node.type !== 'parallel-gateway' && node.type !== 'exclusive-gateway') return null
  const degree = countNodeDegree(page.plan, node.id)
  const okOut = degree.out >= 2
  return { ...degree, okOut }
})

const exclusiveOutEdges = computed(() => {
  const node = page.selectedNode
  if (!node || node.type !== 'exclusive-gateway' || !page.plan) return []
  const labelById = new Map(
    (page.plan.nodes ?? []).map(n => [
      n.id,
      n.displayName?.trim() || formatPlanNodeType(n.type) || n.id,
    ]),
  )
  const autoLeft = exclusiveGatewayConditionLeft(page.plan, node.id)
  return (page.plan.edges ?? [])
    .filter(e => e.from === node.id)
    .map(e => ({
      ...e,
      toLabel: labelById.get(e.to) || e.to,
      condition: e.default
        ? e.condition
        : {
            left: autoLeft,
            op: e.condition?.op ?? 'contains',
            right: e.condition?.right ?? '',
          },
    }))
})

const CONDITION_OP_OPTIONS = [
  { label: '为空 empty', value: 'empty' },
  { label: '非空 not_empty', value: 'not_empty' },
  { label: '包含 contains', value: 'contains' },
  { label: '等于 eq', value: 'eq' },
]

const ON_MAX_ITERATIONS_OPTIONS = [
  { label: '失败终止 fail_fast', value: 'fail_fast' },
  { label: '出框继续 exit', value: 'exit' },
  { label: '降级 ReAct fallback_react', value: 'fallback_react' },
]

function updateExclusiveEdge(
  to: string,
  patch: { default?: boolean; condition?: { left: string; op: string; right?: string } | null },
) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const from = page.selectedNode.id
  const autoLeft = exclusiveGatewayConditionLeft(page.plan, from)
  const edges = (page.plan.edges ?? []).map(e => {
    if (e.from !== from) return e
    if (patch.default === true && e.to !== to && e.default) {
      const { default: _d, ...rest } = e
      return {
        ...rest,
        condition: rest.condition
          ? { ...rest.condition, left: autoLeft }
          : { left: autoLeft, op: 'contains', right: '' },
      }
    }
    if (e.to !== to) return e
    const next = { ...e }
    if (patch.default === true) {
      next.default = true
      delete next.condition
      return next
    }
    if (patch.default === false) {
      delete next.default
      next.condition = {
        left: autoLeft,
        op: next.condition?.op ?? 'contains',
        right: next.condition?.right ?? '',
      }
      return next
    }
    if (patch.condition === null) {
      delete next.condition
    } else if (patch.condition) {
      next.condition = { ...patch.condition, left: autoLeft }
      delete next.default
    }
    return next
  })
  page.plan = { ...page.plan, edges }
}

function updateExclusiveEdgeField(
  to: string,
  field: 'op' | 'right',
  value: string,
) {
  if (!page.plan || readOnly.value || !page.selectedNode) return
  const from = page.selectedNode.id
  const edge = (page.plan.edges ?? []).find(e => e.from === from && e.to === to)
  const condition = {
    left: exclusiveGatewayConditionLeft(page.plan, from),
    op: edge?.condition?.op ?? 'contains',
    right: edge?.condition?.right ?? '',
    [field]: value,
  }
  updateExclusiveEdge(to, { condition })
}

const defaultIntentAfter = computed(() => defaultCatalogIntentAfter(page.nodeDefaults))

const catalogIntentAfterDisplay = computed({
  get: () => page.catalogIntentAfter.trim() || defaultIntentAfter.value,
  set: (val: string) => {
    const trimmed = val.trim()
    page.catalogIntentAfter = trimmed === defaultIntentAfter.value ? '' : val
  },
})

function updateCatalogIntentAfter(val: string) {
  if (readOnly.value) return
  catalogIntentAfterDisplay.value = val
}

function updateRagKbId(val: string) {
  if (readOnly.value || !page.selectedNode) return
  updateNodeParams(patchNodeParams(page.selectedNode.params, {
    kbId: patchKbIdFromSelect(val),
  }))
}

function updateAgentKbId(val: string) {
  if (readOnly.value || !page.selectedNode) return
  updateNodeParams(patchNodeParams(page.selectedNode.params, {
    kbId: patchKbIdFromSelect(val),
  }))
}

function updatePlanReason(val: string) {
  if (!page.plan || readOnly.value) return
  page.plan = { ...page.plan, reason: val }
}

function updateAnswerPrompt(val: string) {
  if (!page.plan || readOnly.value) return
  const nodes = page.plan.nodes.map(n =>
    n.type === 'answer' ? { ...n, params: { ...n.params, prompt: val } } : n,
  )
  page.plan = { ...page.plan, nodes }
}

function updateNodeParams(params: Record<string, unknown>) {
  if (readOnly.value) return
  const node = page.selectedNode
  if (!node) return
  page.updateSelectedNode({ params })
}

function updateNodeParam(key: string, val: string | number) {
  if (readOnly.value) return
  const node = page.selectedNode
  if (!node) return
  page.updateSelectedNode({
    params: { ...node.params, [key]: val },
  })
}

function updateAnswerParams(params: Record<string, unknown>) {
  if (!page.plan || readOnly.value || !answerNode.value) return
  const nodes = page.plan.nodes.map(n =>
    n.type === 'answer' ? { ...n, params } : n,
  )
  page.plan = { ...page.plan, nodes }
}

function updateToolExtraParams(text: string) {
  if (readOnly.value || !page.selectedNode) return
  updateNodeParams(mergeToolExtraParams(page.selectedNode.params, text))
}

function updateToolSchemaParam(name: string, val: string) {
  if (readOnly.value || !page.selectedNode) return
  updateNodeParam(name, val)
}

function updateToolOutputMode(mode: ToolOutputMode) {
  if (readOnly.value || !page.selectedNode) return
  updateNodeParams(patchNodeParams(page.selectedNode.params, {
    'output.mode': mode === 'full' ? null : mode,
  }))
}

function updateToolOutputExtract(json: string) {
  if (readOnly.value || !page.selectedNode) return
  updateNodeParams(patchNodeParams(page.selectedNode.params, {
    'output.extract': json.trim() ? json : null,
  }))
}

function onToolSelect(toolId: string) {
  if (readOnly.value || !page.selectedNode) return
  updateNodeParam('tool', toolId ?? '')
}

function collapse() {
  emit('update:open', false)
}

function expand() {
  emit('update:open', true)
}
</script>

<template>
  <aside v-show="open" class="studio-props">
    <header class="studio-props-head">
      <div class="studio-props-head-main">
        <div class="studio-props-title-row">
          <span class="studio-props-type-icon" aria-hidden="true">
            <PlanNodeIcon :type="headerNodeType" :size="16" />
          </span>
          <div class="studio-props-title-block">
            <h3 class="studio-props-title">{{ headerTitle }}</h3>
            <p class="studio-props-subtitle">{{ headerSubtitle }}</p>
          </div>
        </div>
        <button
          type="button"
          class="studio-props-close"
          title="收起属性面板"
          aria-label="收起属性面板"
          @click="collapse"
        >
          ×
        </button>
      </div>
    </header>
    <div class="studio-props-scroll">
<template v-if="page.isFlowConfigSelected">
              <NForm label-placement="top" size="small" class="props-form">
                <NFormItem>
                  <template #label>
                    <span class="field-label-row">展示名<ConfigFieldHelp :text="workflowFlowFieldHelp('displayName')" /></span>
                  </template>
                  <NInput
                    v-model:value="page.definitionDisplayName"
                    class="sun-field"
                    :disabled="readOnly"
                  />
                </NFormItem>
                <NFormItem>
                  <template #label>
                    <span class="field-label-row">描述<ConfigFieldHelp :text="workflowFlowFieldHelp('description')" /></span>
                  </template>
                  <NInput
                    v-model:value="page.definitionDescription"
                    class="sun-field"
                    type="textarea"
                    :disabled="readOnly"
                    :autosize="{ minRows: 2, maxRows: 5 }"
                  />
                </NFormItem>
                <NFormItem>
                  <template #label>
                    <span class="field-label-row">规划说明<ConfigFieldHelp :text="workflowFlowFieldHelp('planReason')" /></span>
                  </template>
                  <NInput
                    class="sun-field"
                    type="textarea"
                    :disabled="readOnly"
                    :autosize="{ minRows: 2, maxRows: 4 }"
                    :value="page.plan?.reason ?? ''"
                    @update:value="updatePlanReason"
                  />
                </NFormItem>
                <NFormItem>
                  <template #label>
                    <span class="field-label-row">路由示例<ConfigFieldHelp :text="workflowFlowFieldHelp('catalogExamples')" /></span>
                  </template>
                  <NInput
                    v-model:value="page.catalogExamples"
                    class="sun-field"
                    type="textarea"
                    :disabled="readOnly"
                    :autosize="{ minRows: 3, maxRows: 8 }"
                  />
                </NFormItem>
                <NFormItem>
                  <template #label>
                    <span class="field-label-row">意图终态文案<ConfigFieldHelp :text="workflowFlowFieldHelp('catalogIntentAfter')" /></span>
                  </template>
                  <NInput
                    class="sun-field"
                    type="textarea"
                    :disabled="readOnly"
                    :autosize="{ minRows: 2, maxRows: 4 }"
                    :value="catalogIntentAfterDisplay"
                    @update:value="updateCatalogIntentAfter"
                  />
                </NFormItem>
              </NForm>
            </template>
            <template v-else-if="isAnswerSelected && answerNode">
              <NForm label-placement="top" size="small" class="props-form">
                <NFormItem>
                  <template #label>
                    <span class="field-label-row">终态 prompt<ConfigFieldHelp :text="workflowFlowFieldHelp('answerPrompt')" /></span>
                  </template>
                  <NInput
                    class="sun-field"
                    type="textarea"
                    :disabled="readOnly"
                    :autosize="{ minRows: 6, maxRows: 16 }"
                    :value="String(answerNode.params?.prompt ?? '')"
                    @update:value="updateAnswerPrompt"
                  />
                </NFormItem>
                <WorkflowNodeExecutionPolicy
                  node-type="answer"
                  :params="answerNode.params"
                  :read-only="readOnly"
                  :node-defaults="page.nodeDefaults"
                  @update:params="updateAnswerParams"
                />
              </NForm>
            </template>
            <template v-else-if="page.selectedNode">
              <NForm label-placement="top" size="small" class="props-form node-props-form">
                <WorkflowNodeConfigSection title="基本信息">
                  <NFormItem>
                    <template #label>
                      <span class="field-label-row">节点 ID<ConfigFieldHelp :text="workflowNodeFieldHelp('nodeId')" /></span>
                    </template>
                    <NInput class="sun-field" :value="page.selectedNode.id" disabled />
                  </NFormItem>
                  <NFormItem>
                    <template #label>
                      <span class="field-label-row">展示名<ConfigFieldHelp :text="workflowNodeFieldHelp('displayName')" /></span>
                    </template>
                    <NInput
                      class="sun-field"
                      :value="page.selectedNode.displayName ?? ''"
                      :disabled="readOnly"
                      @update:value="v => page.updateSelectedNode({ displayName: v })"
                    />
                  </NFormItem>
                </WorkflowNodeConfigSection>
                <template v-if="page.selectedNode.type === 'rag'">
                  <WorkflowNodeConfigSection title="输入" :help="workflowNodeFieldHelp('nodeInputs')">
                    <NFormItem>
                      <template #label>
                        <span class="wf-param-label"><code class="wf-param-name">query</code></span>
                      </template>
                      <NInput
                        class="sun-field wf-mono-field"
                        type="textarea"
                        :disabled="readOnly"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                        :value="String(page.selectedNode.params?.query ?? '')"
                        @update:value="v => updateNodeParam('query', v)"
                      />
                    </NFormItem>
                    <NFormItem>
                      <template #label>
                        <span class="wf-param-label"><code class="wf-param-name">context</code></span>
                      </template>
                      <NInput
                        class="sun-field wf-mono-field"
                        type="textarea"
                        :disabled="readOnly"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                        :value="String(page.selectedNode.params?.context ?? '')"
                        @update:value="v => updateNodeParam('context', v)"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                  <WorkflowNodeConfigSection title="检索配置">
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">topK<ConfigFieldHelp :text="workflowNodeFieldHelp('topK')" /></span>
                      </template>
                      <NInputNumber
                        class="sun-field"
                        :disabled="readOnly"
                        :value="readRagTopK(page.selectedNode.params, page.nodeDefaults)"
                        :min="1"
                        :max="20"
                        @update:value="v => updateNodeParam('topK', String(v ?? readRagTopK(page.selectedNode!.params, page.nodeDefaults)))"
                      />
                    </NFormItem>
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">知识库<ConfigFieldHelp :text="workflowNodeFieldHelp('kbId')" /></span>
                      </template>
                      <NSelect
                        class="sun-field"
                        filterable
                        :disabled="readOnly"
                        :value="resolveKbSelectValue(page.selectedNode.params)"
                        :options="kbSelectOptions"
                        @update:value="updateRagKbId"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                  <WorkflowNodeIoSection :node="page.selectedNode" :read-only="readOnly" />
                </template>
                <template v-else-if="page.selectedNode.type === 'tool'">
                  <WorkflowNodeConfigSection title="工具">
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">Catalog 工具<ConfigFieldHelp :text="workflowNodeFieldHelp('tool')" /></span>
                      </template>
                      <NSelect
                        class="sun-field"
                        filterable
                        :disabled="readOnly"
                        :value="String(page.selectedNode.params?.tool ?? '')"
                        :options="toolSelectOptions"
                        @update:value="onToolSelect"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                  <WorkflowNodeConfigSection title="入参" :help="workflowNodeFieldHelp('nodeInputs')">
                    <template v-if="selectedToolSchemaFields.length">
                      <NFormItem v-for="field in selectedToolSchemaFields" :key="field.name">
                        <template #label>
                          <span class="wf-param-label">
                            <code class="wf-param-name">{{ field.name }}</code>
                            <span v-if="field.required" class="required-mark">*</span>
                          </span>
                        </template>
                        <div class="wf-param-field">
                          <p v-if="field.description" class="wf-param-hint">{{ field.description }}</p>
                          <NInput
                            class="sun-field wf-mono-field"
                            :disabled="readOnly"
                            placeholder="如 pending 或 {{start.userQuery}}"
                            :value="readToolParamValue(page.selectedNode.params, field.name)"
                            @update:value="v => updateToolSchemaParam(field.name, v)"
                          />
                        </div>
                      </NFormItem>
                    </template>
                    <NFormItem v-else>
                      <template #label>
                        <span class="field-label-row">工具入参<ConfigFieldHelp :text="workflowNodeFieldHelp('toolExtra')" /></span>
                      </template>
                      <NInput
                        class="sun-field wf-mono-field"
                        type="textarea"
                        :disabled="readOnly"
                        :autosize="{ minRows: 3, maxRows: 8 }"
                        :value="toolExtraParamsLines(page.selectedNode.params)"
                        @update:value="updateToolExtraParams"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                  <WorkflowNodeIoSection
                    :node="page.selectedNode"
                    :read-only="readOnly"
                    :tool-catalog="selectedToolCatalog"
                    @update:output-mode="updateToolOutputMode"
                    @update:output-extract="updateToolOutputExtract"
                  />
                </template>
                <template v-else-if="page.selectedNode.type === 'agent'">
                  <WorkflowNodeConfigSection title="技能绑定">
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">Skill<ConfigFieldHelp :text="workflowNodeFieldHelp('skill')" /></span>
                      </template>
                      <NSelect
                        class="sun-field"
                        filterable
                        :disabled="readOnly"
                        :value="String(page.selectedNode.params?.skill ?? '')"
                        :options="skillSelectOptions"
                        @update:value="v => updateNodeParam('skill', v ?? '')"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                  <WorkflowNodeConfigSection title="输入" :help="workflowNodeFieldHelp('nodeInputs')">
                    <NFormItem>
                      <template #label>
                        <span class="wf-param-label"><code class="wf-param-name">query</code></span>
                      </template>
                      <NInput
                        class="sun-field wf-mono-field"
                        type="textarea"
                        :disabled="readOnly"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                        :value="String(page.selectedNode.params?.query ?? '')"
                        @update:value="v => updateNodeParam('query', v)"
                      />
                    </NFormItem>
                    <NFormItem>
                      <template #label>
                        <span class="wf-param-label"><code class="wf-param-name">context</code></span>
                      </template>
                      <NInput
                        class="sun-field wf-mono-field"
                        type="textarea"
                        :disabled="readOnly"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                        :value="String(page.selectedNode.params?.context ?? '')"
                        @update:value="v => updateNodeParam('context', v)"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                  <WorkflowNodeConfigSection title="运行配置">
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">知识库<ConfigFieldHelp :text="workflowNodeFieldHelp('agentKbId')" /></span>
                      </template>
                      <NSelect
                        class="sun-field"
                        filterable
                        :disabled="readOnly"
                        :value="resolveKbSelectValue(page.selectedNode.params)"
                        :options="kbSelectOptions"
                        @update:value="updateAgentKbId"
                      />
                    </NFormItem>
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">附加 tools<ConfigFieldHelp :text="workflowNodeFieldHelp('agentTools')" /></span>
                      </template>
                      <NSelect
                        class="sun-field"
                        multiple
                        filterable
                        :disabled="readOnly"
                        :value="parseAgentToolsParam(page.selectedNode.params?.tools)"
                        :options="toolSelectOptions"
                        @update:value="v => updateNodeParam('tools', formatAgentToolsParam(v))"
                      />
                    </NFormItem>
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">maxIters<ConfigFieldHelp :text="workflowNodeFieldHelp('maxIters')" /></span>
                      </template>
                      <NInputNumber
                        class="sun-field"
                        :disabled="readOnly"
                        :value="readAgentMaxIters(page.selectedNode.params, page.nodeDefaults)"
                        :min="1"
                        :max="12"
                        @update:value="v => updateNodeParam('maxIters', String(v ?? readAgentMaxIters(page.selectedNode!.params, page.nodeDefaults)))"
                      />
                    </NFormItem>
                    <NFormItem>
                      <template #label>
                        <span class="field-label-row">systemOverlay<ConfigFieldHelp :text="workflowNodeFieldHelp('systemOverlay')" /></span>
                      </template>
                      <NInput
                        class="sun-field wf-mono-field"
                        type="textarea"
                        :disabled="readOnly"
                        :autosize="{ minRows: 2, maxRows: 4 }"
                        :value="String(page.selectedNode.params?.systemOverlay ?? '')"
                        @update:value="v => updateNodeParam('systemOverlay', v)"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                  <WorkflowNodeIoSection :node="page.selectedNode" :read-only="readOnly" />
                </template>
                <template v-else-if="page.selectedNode.type === 'join'">
                  <WorkflowNodeConfigSection title="并行拓扑" :help="workflowNodeFieldHelp('joinTopology')">
                    <p v-if="joinTopology" class="join-topology-lines">
                      <span :class="{ 'join-ok': joinTopology.okIn, 'join-warn': !joinTopology.okIn }">
                        入边 {{ joinTopology.in }} 条{{ joinTopology.okIn ? '' : '（须 ≥ 2）' }}
                      </span>
                      <span :class="{ 'join-ok': joinTopology.okOut, 'join-warn': !joinTopology.okOut }">
                        出边 {{ joinTopology.out }} 条{{ joinTopology.okOut ? '' : '（须 = 1）' }}
                      </span>
                    </p>
                    <p class="join-topology-hint">多条并行路线在此汇合，再进入后续步骤。</p>
                  </WorkflowNodeConfigSection>
                </template>
                <template v-else-if="page.selectedNode.type === 'parallel-gateway'">
                  <WorkflowNodeConfigSection title="并行分叉" :help="workflowNodeFieldHelp('parallelGatewayTopology')">
                    <p v-if="gatewayTopology" class="join-topology-lines">
                      <span :class="{ 'join-ok': gatewayTopology.okOut, 'join-warn': !gatewayTopology.okOut }">
                        出边 {{ gatewayTopology.out }} 条{{ gatewayTopology.okOut ? '' : '（须 ≥ 2）' }}
                      </span>
                    </p>
                    <p class="join-topology-hint">将流程拆成多条可同时执行的路线，每条路线后接具体步骤。</p>
                  </WorkflowNodeConfigSection>
                </template>
                <template v-else-if="page.selectedNode.type === 'exclusive-gateway'">
                  <WorkflowNodeConfigSection title="条件分支" :help="workflowNodeFieldHelp('exclusiveGatewayTopology')">
                    <p v-if="gatewayTopology" class="join-topology-lines">
                      <span :class="{ 'join-ok': gatewayTopology.okOut, 'join-warn': !gatewayTopology.okOut }">
                        出边 {{ gatewayTopology.out }} 条{{ gatewayTopology.okOut ? '' : '（须 ≥ 2）' }}
                      </span>
                    </p>
                    <p class="join-topology-hint">按条件选择其中一条路继续；须恰好一条默认分支。</p>
                    <div
                      v-for="edge in exclusiveOutEdges"
                      :key="`${edge.from}->${edge.to}`"
                      class="exclusive-edge-card"
                    >
                      <div class="exclusive-edge-head">
                        <div class="exclusive-edge-target" :title="edge.to">
                          <span class="exclusive-edge-to-label">→ {{ edge.toLabel }}</span>
                          <span class="exclusive-edge-to-id">{{ edge.to }}</span>
                        </div>
                        <label class="exclusive-default">
                          <input
                            type="checkbox"
                            :checked="!!edge.default"
                            :disabled="readOnly"
                            @change="updateExclusiveEdge(edge.to, { default: ($event.target as HTMLInputElement).checked })"
                          >
                          默认
                        </label>
                      </div>
                      <template v-if="!edge.default">
                        <NFormItem label="左值（随上游自动填入）" :show-feedback="false">
                          <NInput
                            :value="edge.condition?.left ?? ''"
                            disabled
                            placeholder="{{start.userQuery}}"
                          />
                        </NFormItem>
                        <NFormItem label="算子" :show-feedback="false">
                          <NSelect
                            :value="edge.condition?.op || 'contains'"
                            :options="CONDITION_OP_OPTIONS"
                            :disabled="readOnly"
                            @update:value="v => updateExclusiveEdgeField(edge.to, 'op', String(v))"
                          />
                        </NFormItem>
                        <NFormItem
                          v-if="edge.condition?.op !== 'empty' && edge.condition?.op !== 'not_empty'"
                          label="右值"
                          :show-feedback="false"
                        >
                          <NInput
                            :value="edge.condition?.right ?? ''"
                            :disabled="readOnly"
                            placeholder="比较值"
                            @update:value="v => updateExclusiveEdgeField(edge.to, 'right', v)"
                          />
                        </NFormItem>
                      </template>
                    </div>
                  </WorkflowNodeConfigSection>
                </template>
                <template v-else-if="page.selectedNode.type === 'loop'">
                  <WorkflowNodeConfigSection title="循环" :help="workflowNodeFieldHelp('loopTopology')">
                    <NFormItem label="继续条件 · 左值（随上游自动填入）" :show-feedback="false">
                      <NInput
                        :value="loopConditionLeft(page.plan!, page.selectedNode.id)"
                        disabled
                        placeholder="{{start.userQuery}}"
                      />
                    </NFormItem>
                    <NFormItem label="继续条件 · 算子" :show-feedback="false">
                      <NSelect
                        :value="String(page.selectedNode.params?.['condition.op'] || 'contains')"
                        :options="CONDITION_OP_OPTIONS"
                        :disabled="readOnly"
                        @update:value="v => updateNodeParam('condition.op', String(v))"
                      />
                    </NFormItem>
                    <NFormItem
                      v-if="page.selectedNode.params?.['condition.op'] !== 'empty'
                        && page.selectedNode.params?.['condition.op'] !== 'not_empty'"
                      label="继续条件 · 右值"
                      :show-feedback="false"
                    >
                      <NInput
                        :value="String(page.selectedNode.params?.['condition.right'] ?? '')"
                        :disabled="readOnly"
                        placeholder="比较值"
                        @update:value="v => updateNodeParam('condition.right', v)"
                      />
                    </NFormItem>
                    <NFormItem label="最大轮次" :show-feedback="false">
                      <NInputNumber
                        :value="Number(page.selectedNode.params?.maxIterations ?? 3)"
                        :min="1"
                        :max="5"
                        :disabled="readOnly"
                        @update:value="v => updateNodeParam('maxIterations', String(v ?? 3))"
                      />
                    </NFormItem>
                    <NFormItem label="超限策略" :show-feedback="false">
                      <NSelect
                        :value="String(page.selectedNode.params?.onMaxIterations || 'fail_fast')"
                        :options="ON_MAX_ITERATIONS_OPTIONS"
                        :disabled="readOnly"
                        @update:value="v => updateNodeParam('onMaxIterations', String(v))"
                      />
                    </NFormItem>
                  </WorkflowNodeConfigSection>
                </template>
                <WorkflowNodeConfigSection
                  v-if="page.selectedNode.type !== 'start' && page.selectedNode.type !== 'loop'"
                  title="执行策略"
                >
                  <WorkflowNodeExecutionPolicy
                    :node-type="page.selectedNode.type"
                    :params="page.selectedNode.params"
                    :read-only="readOnly"
                    :node-defaults="page.nodeDefaults"
                    @update:params="updateNodeParams"
                  />
                </WorkflowNodeConfigSection>
              </NForm>
            </template>
            <p v-else class="props-empty">点击画布节点以编辑属性</p>
    </div>
  </aside>
  <button
    v-if="showExpandBtn !== false && !open"
    type="button"
    class="props-expand-btn"
    title="展开属性面板"
    @click="expand"
  >
    ‹ 属性
  </button>
</template>

<style scoped>
.studio-props {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--sun-black);
}

.studio-props-head {
  flex-shrink: 0;
  padding: 14px 14px 12px;
  border-bottom: 1px solid var(--sun-border);
  background: var(--sun-black);
}

.studio-props-head-main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.studio-props-title-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  flex: 1;
  min-width: 0;
}

.studio-props-type-icon {
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  color: var(--sun-text-secondary);
}

.studio-props-title-block {
  flex: 1;
  min-width: 0;
}

.studio-props-title {
  margin: 0;
  padding-top: 1px;
  font-size: 15px;
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.35;
  word-break: break-word;
}

.studio-props-subtitle {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  line-height: 1.35;
}

.studio-props-close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--sun-text-muted);
  font-size: 20px;
  line-height: 1;
  cursor: pointer;
}

.studio-props-close:hover {
  color: var(--sun-text);
  background: color-mix(in srgb, var(--sun-border) 35%, transparent);
}

.studio-props-head .block-title {
  margin-bottom: 0;
}

.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-secondary);
  margin-bottom: 10px;
}

.props-expand-btn {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-right: none;
  border-radius: var(--radius-md) 0 0 var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text-secondary);
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
  z-index: 2;
}

.props-expand-btn:hover {
  color: var(--sun-text);
  border-color: var(--sun-border-light);
}

.studio-props-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px 16px;
}

.field-label-row {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.node-props-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.node-props-form :deep(.wf-sec) {
  margin-top: 0;
}
.wf-param-label {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.wf-param-name {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  font-weight: 500;
  color: var(--sun-text);
  background: none;
  padding: 0;
}
.wf-param-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}
.wf-param-hint {
  margin: 0;
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  line-height: 1.4;
}
.wf-mono-field :deep(.n-input__textarea-el),
.wf-mono-field :deep(.n-input__input-el) {
  font-family: var(--sun-font-mono);
  font-size: var(--sun-font-sm);
  line-height: 1.5;
}
.required-mark {
  color: var(--sun-amber);
  font-size: var(--sun-font-sm);
  line-height: 1;
}

.props-empty {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.join-topology-lines {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.join-topology-lines .join-ok {
  color: var(--sun-text-secondary);
}

.join-topology-lines .join-warn {
  color: var(--sun-amber);
}

.join-topology-hint {
  margin: 0;
  font-size: 11px;
  color: var(--sun-text-muted);
  line-height: 1.45;
}

.join-topology-lines {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.join-topology-lines .join-ok {
  color: var(--sun-text-secondary);
}

.join-topology-lines .join-warn {
  color: var(--sun-amber);
}

.exclusive-edge-card {
  margin-top: 10px;
  padding: 10px;
  border: 1px solid var(--sun-border);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--sun-black);
}

.exclusive-edge-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.exclusive-edge-target {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.exclusive-edge-to-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text);
  line-height: 1.35;
  word-break: break-word;
}

.exclusive-edge-to-id {
  font-size: 11px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono);
  word-break: break-all;
}

.exclusive-default {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--sun-text-secondary);
  cursor: pointer;
  user-select: none;
}

.join-topology-hint {
  margin: 0;
  font-size: 11px;
  color: var(--sun-text-muted);
  line-height: 1.45;
}

.props-form {
  margin-bottom: 0;
}

.props-form :deep(.n-form-item) {
  margin-bottom: 14px;
}

.props-form :deep(.n-form-item:last-child) {
  margin-bottom: 0;
}

.props-form :deep(.n-form-item-blank) {
  display: block;
  min-width: 0;
}

.props-form :deep(.n-form-item-feedback-wrapper) {
  min-height: 0;
}

.node-props-form :deep(.n-form-item) {
  margin-bottom: 0;
}

.props-form :deep(.n-input),
.props-form :deep(.n-input-number .n-input) {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.props-form :deep(.n-input-number .n-input.n-input--disabled) {
  background-color: var(--sun-black) !important;
}

.props-form :deep(.n-base-selection) {
  --n-color: var(--sun-black) !important;
  --n-color-active: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-arrow-color: var(--sun-text-secondary) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-border-active: 1px solid var(--sun-border-light) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.catalog-bind-hint {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--sun-text-muted);
  line-height: 1.45;
  word-break: break-all;
}

.catalog-bind-hint code {
  font-family: var(--sun-font-mono, ui-monospace, monospace);
  color: var(--sun-text-secondary);
}

@media (max-width: 960px) {
  .studio-props {
    width: 100% !important;
    max-height: 42vh;
    border-left: none;
    border-top: 1px solid var(--sun-border);
  }


  .props-expand-btn {
    top: auto;
    bottom: 12px;
    transform: none;
  }
}
</style>
