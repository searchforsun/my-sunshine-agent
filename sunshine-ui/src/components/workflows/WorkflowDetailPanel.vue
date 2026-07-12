<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import {
  NButton,
  NDropdown,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NSelect,
  NSpin,
  NTag,
} from 'naive-ui'
import { AddOutline, EllipsisHorizontal, TrashOutline } from '@vicons/ionicons5'
import PlanDagGraph from '../plan/PlanDagGraph.vue'
import ConfigFieldHelp from '../knowledge/ConfigFieldHelp.vue'
import WorkflowNodeExecutionPolicy from './WorkflowNodeExecutionPolicy.vue'
import WorkflowNodeConfigSection from './WorkflowNodeConfigSection.vue'
import WorkflowNodeIoSection from './WorkflowNodeIoSection.vue'
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
import { FLOW_CONFIG_SELECTION, type WorkflowBusinessNodeType } from '../../utils/workflowPlan'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi

const validationAlertExpanded = ref(true)

watch(
  () => page.validationIssues.length,
  (count, prev) => {
    if (count > 0 && count !== prev) {
      validationAlertExpanded.value = true
    }
  },
)

const validationCollapsedLine = computed(() => {
  const issues = page.validationIssues
  if (issues.length === 0) return ''
  const head = `DAG 校验问题（${issues.length}）`
  return issues.length === 1 ? `${head} · ${issues[0]}` : `${head} · ${issues[0]}…`
})

function toggleValidationAlert(): void {
  validationAlertExpanded.value = !validationAlertExpanded.value
}

const nodeTypeOptions = [
  { label: '知识检索 (rag)', value: 'rag' },
  { label: '工具 (tool)', value: 'tool' },
  { label: '智能体 (agent)', value: 'agent' },
]

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

const readOnly = computed(() => !page.canEditPlan)

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

function onAddNode(type: WorkflowBusinessNodeType) {
  page.addNode(type)
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
</script>

<template>
  <section v-if="page.selectedWorkflow && page.plan" class="detail-panel">
    <input
      :ref="page.bindImportInputRef"
      type="file"
      accept="application/json,.json"
      class="hidden-import"
      @change="page.handleImportFile"
    >
    <header class="detail-toolbar">
      <div class="detail-title-block">
        <h3>{{ page.selectedWorkflow.displayName }}</h3>
        <div class="detail-meta-inline">
          <span class="detail-id">#{{ page.selectedWorkflow.id }}</span>
        </div>
      </div>
      <div class="detail-actions">
        <div v-if="page.versionOptions.length" class="version-row">
          <span class="version-label">当前版本</span>
          <NTag
            size="small"
            :bordered="false"
            round
            :type="page.versionStatusTagType(page.versionStatus)"
          >
            {{ page.versionStatusLabel(page.versionStatus) }}
          </NTag>
          <NSelect
            :value="page.selectedVersion"
            :options="page.versionOptions"
            size="small"
            class="version-select"
            placeholder="选择版本"
            :disabled="page.detailLoading || page.saving"
            :menu-props="{ class: 'version-select-menu' }"
            @update:value="v => { page.selectedVersion = v as number }"
          />
        </div>
        <NButton
          v-if="page.canEditPlan"
          size="small"
          round
          secondary
          :loading="page.validating"
          @click="void page.validatePlan()"
        >
          验证 DAG
        </NButton>
        <NButton
          v-if="page.showSaveDraftButton"
          size="small"
          round
          secondary
          :loading="page.saving"
          @click="void page.saveDraft()"
        >
          保存草稿
        </NButton>
        <NButton
          v-if="page.showPublishButton"
          size="small"
          round
          type="primary"
          class="action-btn"
          :loading="page.publishing"
          @click="void page.publish()"
        >
          {{ page.workflowPhase === 'history' ? '设为此生效版' : '发布' }}
        </NButton>
        <NDropdown
          trigger="click"
          size="small"
          :options="page.moreMenuOptions"
          @select="(key) => page.handleMoreMenuSelect(String(key))"
        >
          <NButton
            size="small"
            quaternary
            class="more-menu-btn"
            title="版本操作"
            aria-label="版本操作"
            :loading="page.saving || page.publishing"
          >
            <template #icon><NIcon :component="EllipsisHorizontal" :size="16" /></template>
          </NButton>
        </NDropdown>
      </div>
    </header>

    <div
      v-if="page.validationIssues.length"
      class="validation-alert"
      :class="{ 'is-collapsed': !validationAlertExpanded, 'is-expanded': validationAlertExpanded }"
    >
      <button
        type="button"
        class="validation-alert-head"
        :aria-expanded="validationAlertExpanded"
        aria-label="展开或收起 DAG 校验问题"
        @click="toggleValidationAlert"
      >
        <svg
          class="validation-chevron"
          width="10"
          height="10"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2.5"
          stroke-linecap="round"
          aria-hidden="true"
        >
          <polyline points="9 18 15 12 9 6" />
        </svg>
        <span v-if="validationAlertExpanded" class="validation-title">DAG 校验问题</span>
        <span v-else class="validation-collapsed-line">{{ validationCollapsedLine }}</span>
      </button>
      <ul v-show="validationAlertExpanded" class="validation-list">
        <li v-for="(issue, idx) in page.validationIssues" :key="idx">{{ issue }}</li>
      </ul>
    </div>

    <NSpin :show="page.detailLoading" class="detail-spin">
      <div class="detail-body">
        <div class="preview-block">
          <div class="block-title">流程预览</div>
          <PlanDagGraph
            :nodes="page.previewNodes"
            :selected-id="page.isFlowConfigSelected ? undefined : page.selectedNodeId ?? undefined"
            fluid
            @select="n => { if (n.id !== 'start') page.selectedNodeId = n.id }"
          />
        </div>

        <div class="editor-grid">
          <div class="nodes-block">
            <div class="block-head">
              <span class="block-title">节点链（线性）</span>
              <div v-if="!readOnly" class="node-add-btns">
                <NButton
                  v-for="opt in nodeTypeOptions"
                  :key="opt.value"
                  size="tiny"
                  round
                  secondary
                  @click="onAddNode(opt.value as WorkflowBusinessNodeType)"
                >
                  <template #icon><NIcon :component="AddOutline" :size="12" /></template>
                  {{ opt.label }}
                </NButton>
              </div>
            </div>
            <div class="node-list">
              <button
                type="button"
                class="node-chip node-chip-flow"
                :class="{ active: page.isFlowConfigSelected }"
                @click="page.selectedNodeId = FLOW_CONFIG_SELECTION"
              >
                <span class="node-chip-type">flow</span>
                <span class="node-chip-label">流程配置</span>
              </button>
              <button
                v-for="node in page.businessNodes"
                :key="node.id"
                type="button"
                class="node-chip"
                :class="{ active: node.id === page.selectedNodeId }"
                @click="page.selectedNodeId = node.id"
              >
                <span class="node-chip-type">{{ node.type }}</span>
                <span class="node-chip-label">{{ node.displayName || node.id }}</span>
                <button
                  v-if="!readOnly"
                  type="button"
                  class="node-chip-del"
                  title="删除节点"
                  @click.stop="page.removeNode(node.id)"
                >
                  <NIcon :component="TrashOutline" :size="12" />
                </button>
              </button>
              <p v-if="!page.businessNodes.length" class="node-empty">点击上方按钮添加业务节点</p>
              <button
                v-if="answerNode"
                type="button"
                class="node-chip node-chip-terminal"
                :class="{ active: page.selectedNodeId === answerNode.id }"
                @click="page.selectedNodeId = answerNode.id"
              >
                <span class="node-chip-type">answer</span>
                <span class="node-chip-label">{{ answerNode.displayName || '生成回答' }}</span>
              </button>
            </div>
          </div>

          <div class="props-block">
            <div class="block-title">{{ propsSectionTitle }}</div>
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
                    :value="page.plan.reason"
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
                <WorkflowNodeConfigSection
                  v-if="page.selectedNode.type !== 'start'"
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
            <p v-else class="props-empty">选择左侧节点以编辑</p>
          </div>
        </div>
      </div>
    </NSpin>
  </section>
</template>

<style scoped>
.detail-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  overflow: hidden;
}

.detail-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
  flex-shrink: 0;
  padding: 12px 16px;
  border-bottom: 1px solid var(--sun-border);
}

.validation-alert {
  margin: 0;
  border-bottom: 1px solid var(--sun-border);
  border-radius: 0;
  background: var(--sun-amber-glow);
}

.validation-alert-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 10px 16px;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: var(--sun-text);
}

.validation-alert.is-collapsed .validation-alert-head {
  padding: 8px 16px;
}

.validation-chevron {
  flex-shrink: 0;
  color: var(--sun-amber);
  opacity: 0.85;
  transition: transform 0.15s ease;
}

.validation-alert.is-expanded .validation-chevron {
  transform: rotate(90deg);
}

.validation-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.validation-collapsed-line {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  line-height: 1.4;
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.validation-list {
  margin: 0;
  padding: 0 16px 12px 38px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--sun-text-muted);
}

.detail-title-block h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--sun-text);
}

.detail-meta-inline {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.detail-id {
  font-size: 12px;
  color: var(--sun-text-muted);
  font-family: var(--sun-font-mono);
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  flex-shrink: 0;
  margin-left: auto;
}

.version-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.version-label {
  font-size: 13px;
  color: var(--sun-text-secondary);
  white-space: nowrap;
}

.version-select {
  width: min(200px, 36vw);
}

.version-select :deep(.n-base-selection) {
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

.more-menu-btn {
  width: 28px;
  height: 28px;
  padding: 0;
}

.hidden-import {
  display: none;
}

.detail-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.detail-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.detail-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px 20px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.preview-block,
.nodes-block,
.props-block {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 12px;
  background: var(--sun-black);
}

.block-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-secondary);
  margin-bottom: 10px;
}

.field-label-row {
  display: inline-flex;
  align-items: center;
  gap: 2px;
}

.io-input-block {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 10px 12px 2px;
  margin-bottom: 12px;
  background: var(--sun-black);
}

.io-block-title {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--sun-text-muted);
  margin-bottom: 8px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

/* 业务节点配置 — 组间距由 node-props-form gap 统一 */
.node-props-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
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

.field-desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.node-chip-flow {
  border-style: dashed;
  margin-bottom: 4px;
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.block-head .block-title {
  margin-bottom: 0;
}

.node-add-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(220px, 280px) 1fr;
  gap: 12px;
  min-height: 280px;
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.node-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  color: var(--sun-text);
  cursor: pointer;
  text-align: left;
}

.node-chip.active {
  border-color: var(--sun-text);
  box-shadow: inset 0 0 0 1px var(--sun-text);
}

.node-chip-type {
  font-size: 11px;
  font-family: var(--sun-font-mono);
  color: var(--sun-text-muted);
  flex-shrink: 0;
}

.node-chip-label {
  flex: 1;
  font-size: 13px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-chip-del {
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
  padding: 2px;
}

.node-chip-del:hover {
  color: var(--sun-danger, #e88080);
}

.node-empty,
.props-empty {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
}

.node-chip-terminal {
  margin-top: 4px;
  border-style: dashed;
}

.props-form {
  margin-bottom: 0;
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

@media (max-width: 960px) {
  .editor-grid {
    grid-template-columns: 1fr;
  }
}
</style>

<style>
.version-select-menu.n-base-select-menu {
  --n-color: var(--sun-black) !important;
}
</style>
