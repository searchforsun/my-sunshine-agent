<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  NButton,
  NEmpty,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NModal,
  NSelect,
  NSpace,
  NTabPane,
  NTabs,
  NTag,
  useMessage,
} from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import {
  createBizScene,
  createBizScenePolicy,
  listBizScenePolicies,
  listBizScenes,
  updateBizScene,
  type BizSceneEntry,
  type BizScenePolicyEntry,
} from '../api/bizScenes'
import { friendlyErrorMessage } from '../api/apiError'

const message = useMessage()

const scenes = ref<BizSceneEntry[]>([])
const policies = ref<BizScenePolicyEntry[]>([])
const loading = ref(false)
const saving = ref(false)
const selectedCode = ref<string | null>(null)

const selectedScene = computed(() =>
  scenes.value.find(s => s.bizScene === selectedCode.value) ?? null,
)
/** description 可空；v-model 目标须为可写成员，用 getter/setter 归一化 */
const sceneDescription = computed({
  get: () => selectedScene.value?.description ?? '',
  set: (v: string) => {
    if (selectedScene.value) selectedScene.value.description = v
  },
})
const scenePolicies = computed(() =>
  policies.value.filter(p => p.bizScene === selectedCode.value),
)

const statusOptions = [
  { label: '启用', value: 'active' },
  { label: '退役', value: 'retired' },
]

async function refreshAll() {
  loading.value = true
  try {
    scenes.value = await listBizScenes()
    policies.value = await listBizScenePolicies()
    if (selectedCode.value && !scenes.value.some(s => s.bizScene === selectedCode.value)) {
      selectedCode.value = null
    }
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

// ---- 码表 CRUD ----
const showCreate = ref(false)
const createDraft = ref({ bizScene: '', displayName: '', description: '' })
const creating = ref(false)

async function handleCreate() {
  if (!createDraft.value.bizScene.trim() || !createDraft.value.displayName.trim()) return
  creating.value = true
  try {
    await createBizScene({
      bizScene: createDraft.value.bizScene.trim(),
      displayName: createDraft.value.displayName.trim(),
      description: createDraft.value.description.trim(),
    })
    showCreate.value = false
    createDraft.value = { bizScene: '', displayName: '', description: '' }
    await refreshAll()
    message.success('业务场景已创建')
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '创建失败'))
  } finally {
    creating.value = false
  }
}

async function handleSaveScene() {
  const scene = selectedScene.value
  if (!scene || !scene.displayName.trim()) return
  saving.value = true
  try {
    await updateBizScene(scene.bizScene, {
      displayName: scene.displayName.trim(),
      description: scene.description?.trim() ?? '',
      status: scene.status,
    })
    await refreshAll()
    message.success('已保存')
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

// ---- Policy ----
const policyDraft = ref('')
const savingPolicy = ref(false)

async function handleSavePolicy() {
  const scene = selectedScene.value
  if (!scene || !policyDraft.value.trim()) return
  savingPolicy.value = true
  try {
    await createBizScenePolicy('default', {
      bizScene: scene.bizScene,
      rulesJson: policyDraft.value,
    })
    policyDraft.value = ''
    await refreshAll()
    message.success('Policy 已创建')
  } catch (e: unknown) {
    message.error(friendlyErrorMessage(e, '创建 Policy 失败'))
  } finally {
    savingPolicy.value = false
  }
}

onMounted(() => {
  void refreshAll()
})
</script>

<template>
  <div class="biz-scenes-root">
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>业务场景</h2>
      </div>
      <NSpace :size="8">
        <NButton round type="primary" class="action-btn" :loading="loading" @click="refreshAll()">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
        <NButton round type="primary" class="action-btn" @click="showCreate = true">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建场景
        </NButton>
      </NSpace>
    </header>

    <div class="biz-layout">
      <aside class="scene-list-panel">
        <NEmpty v-if="!loading && scenes.length === 0" description="暂无业务场景" size="small" />
        <div v-for="scene in scenes" :key="scene.bizScene" class="scene-item" :class="{ active: scene.bizScene === selectedCode }" @click="selectedCode = scene.bizScene">
          <div class="scene-item-title">{{ scene.bizScene }}</div>
          <div class="scene-item-sub">{{ scene.displayName }}</div>
          <NTag v-if="scene.status === 'retired'" size="tiny" type="warning" class="scene-status-tag">退役</NTag>
        </div>
      </aside>

      <section v-if="selectedScene" class="scene-detail-panel">
        <NTabs type="line" :animated="false">
          <NTabPane name="meta" tab="码表信息">
            <NForm class="scene-form" label-placement="top" :show-feedback="false">
              <NFormItem label="场景码">
                <NInput class="sun-field" :value="selectedScene.bizScene" disabled />
              </NFormItem>
              <NFormItem label="名称">
                <NInput v-model:value="selectedScene.displayName" class="sun-field" />
              </NFormItem>
              <NFormItem label="描述">
                <NInput v-model:value="sceneDescription" class="sun-field" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
              </NFormItem>
              <NFormItem label="状态">
                <NSelect v-model:value="selectedScene.status" class="sun-field" :options="statusOptions" size="small" />
              </NFormItem>
              <div class="form-actions">
                <NButton type="primary" class="action-btn" :loading="saving" @click="handleSaveScene()">保存</NButton>
              </div>
            </NForm>
          </NTabPane>
          <NTabPane name="policy" tab="Policy">
            <div class="policy-block">
              <div class="policy-list">
                <NEmpty v-if="scenePolicies.length === 0" description="暂无 Policy" size="small" />
                <div v-for="policy in scenePolicies" :key="policy.policyId" class="policy-item">
                  <div class="policy-item-head">
                    <span class="policy-item-title">v{{ policy.version }}</span>
                    <span class="policy-item-time">{{ policy.updatedAt ? new Date(policy.updatedAt).toLocaleString() : '' }}</span>
                  </div>
                  <pre class="policy-item-json">{{ policy.rulesJson }}</pre>
                </div>
              </div>
              <div class="policy-editor">
                <NInput v-model:value="policyDraft" class="sun-field" type="textarea" :autosize="{ minRows: 4, maxRows: 12 }" placeholder='{"hitl_confirm_before_submit": true}' />
                <NButton type="primary" class="action-btn" :loading="savingPolicy" :disabled="!policyDraft.trim()" @click="handleSavePolicy()">新增 Policy</NButton>
              </div>
            </div>
          </NTabPane>
        </NTabs>
      </section>

      <div v-else class="scene-detail-empty">选择左侧业务场景以编辑</div>
    </div>

    <NModal v-model:show="showCreate" preset="dialog" title="新建业务场景" class="sunshine-dialog">
      <NForm class="modal-form" label-placement="top" :show-feedback="false">
        <NFormItem label="场景码" required>
          <NInput v-model:value="createDraft.bizScene" class="sun-field" placeholder="compliance-review" />
        </NFormItem>
        <NFormItem label="名称" required>
          <NInput v-model:value="createDraft.displayName" class="sun-field" placeholder="费用合规审查" />
        </NFormItem>
        <NFormItem label="描述">
          <NInput v-model:value="createDraft.description" class="sun-field" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
        </NFormItem>
      </NForm>
      <template #action>
        <NButton @click="showCreate = false">取消</NButton>
        <NButton type="primary" class="action-btn" :loading="creating" @click="handleCreate()">创建</NButton>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.biz-scenes-root {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  gap: 12px;
  box-sizing: border-box;
  overflow: hidden;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-shrink: 0;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--sun-text);
}

.biz-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(260px, 300px) 1fr;
  gap: 16px;
}

.scene-list-panel {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--sun-black);
}

.scene-item {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.15s, border-color 0.15s;
}

.scene-item:hover {
  background: var(--sun-row-hover);
}

.scene-item.active {
  background: var(--sun-row-hover);
  border-color: var(--sun-border);
}

.scene-item-title {
  font-size: var(--sun-font-base);
  font-weight: 600;
  color: var(--sun-text);
  word-break: break-all;
}

.scene-item-sub {
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.scene-status-tag {
  position: absolute;
  top: 8px;
  right: 8px;
}

.scene-detail-panel {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  overflow: auto;
  padding: 12px 16px;
  background: var(--sun-black);
}

.scene-detail-empty {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-lg);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sun-text-muted);
  font-size: var(--sun-font-sm);
}

.scene-form {
  max-width: 560px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
}

.policy-block {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 720px;
}

.policy-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.policy-item {
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  padding: 8px 10px;
}

.policy-item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.policy-item-title {
  font-weight: 600;
  font-size: var(--sun-font-sm);
  color: var(--sun-text);
}

.policy-item-time {
  font-size: var(--sun-font-xs);
  color: var(--sun-text-muted);
}

.policy-item-json {
  margin: 0;
  font-size: var(--sun-font-xs);
  font-family: var(--sun-font-mono, ui-monospace, 'JetBrains Mono', monospace);
  color: var(--sun-text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}

.policy-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
}

.action-btn {
  --n-color: var(--sun-accent) !important;
}

.modal-form :deep(.n-form-item-label) {
  color: var(--sun-text-secondary);
  font-size: 13px;
}
</style>
