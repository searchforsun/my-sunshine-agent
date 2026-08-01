<script setup lang="ts">
import { ref, watch } from 'vue'
import { NModal, NInput, NButton, useMessage } from 'naive-ui'
import type { WorkspaceVO } from '../../api/workspaces'
import { getProjectGuide, saveProjectGuide } from '../../api/workspaces'
import { friendlyErrorMessage } from '../../api/apiError'
import StaticMarkdown from '../StaticMarkdown.vue'

const props = defineProps<{
  show: boolean
  workspace: WorkspaceVO | null
}>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const message = useMessage()
const content = ref('')
const loading = ref(false)
const saving = ref(false)
const updatedAt = ref('')
const mode = ref<'edit' | 'preview'>('edit')

const GUIDE_TEMPLATE = `# 项目规范

## 技术栈
- 后端：Java 17 / Spring Boot 3.x
- 前端：Vue 3 + TypeScript

## 架构约定
- 分层：controller → service → repository
- 禁止硬编码提示词，统一走 Prompt Catalog

## 编码规范
- 错误处理走 BizException + FixedErrorCode
- 新增依赖用包管理器最新版本

## 常用命令
- 构建：mvn clean package
- 测试：mvn test`

watch(() => props.show, (open) => {
  if (!open || !props.workspace) return
  void load()
})

async function load() {
  if (!props.workspace) return
  loading.value = true
  try {
    const g = await getProjectGuide(props.workspace.id)
    content.value = g.content ?? ''
    updatedAt.value = g.updatedAt ?? ''
    mode.value = 'edit'
  } catch (e) {
    message.error(friendlyErrorMessage(e, '加载项目规范失败'))
  } finally {
    loading.value = false
  }
}

function insertTemplate() {
  if (!content.value) {
    content.value = GUIDE_TEMPLATE
  } else {
    content.value += '\n\n' + GUIDE_TEMPLATE
  }
  mode.value = 'edit'
}

async function handleSave() {
  if (!props.workspace) return
  saving.value = true
  try {
    await saveProjectGuide(props.workspace.id, content.value)
    message.success('项目规范已保存')
    emit('update:show', false)
  } catch (e) {
    message.error(friendlyErrorMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <NModal
    :show="props.show"
    preset="card"
    :title="workspace ? `项目规范 · ${workspace.name}` : '项目规范'"
    style="width: 720px"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="guide-toolbar">
      <div class="guide-tabs">
        <button type="button" class="guide-tab" :class="{ 'is-active': mode === 'edit' }" @click="mode = 'edit'">编辑</button>
        <button type="button" class="guide-tab" :class="{ 'is-active': mode === 'preview' }" @click="mode = 'preview'">预览</button>
        <button type="button" class="guide-tab" @click="insertTemplate">插入示例模板</button>
      </div>
      <span v-if="updatedAt" class="guide-updated">上次保存 {{ updatedAt }}</span>
    </div>
    <NInput
      v-if="mode === 'edit'"
      v-model:value="content"
      class="guide-input"
      type="textarea"
      :autosize="{ minRows: 18, maxRows: 30 }"
      placeholder="类 CLAUDE.md 的项目级规范：技术栈 / 架构约束 / 编码约定 / 常用命令 / 构建验证方式…"
      :disabled="loading || saving"
    />
    <div v-else-if="mode === 'preview'" class="guide-preview">
      <StaticMarkdown :source="content" />
    </div>
    <p v-if="mode === 'edit'" class="guide-tip">
      保存后，该工作区下所有任务会话会自动读取此规范（类似 CLAUDE.md 的项目级文件），未保存时不生效。
    </p>
    <template #footer>
      <NButton quaternary :disabled="saving" @click="emit('update:show', false)">取消</NButton>
      <NButton type="primary" :loading="saving" @click="handleSave">保存</NButton>
    </template>
  </NModal>
</template>

<style scoped>
.guide-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.guide-tabs {
  display: inline-flex;
  gap: 4px;
}
.guide-tab {
  border: 1px solid var(--sun-border);
  background: transparent;
  color: var(--sun-text-muted);
  font-size: 12px;
  padding: 3px 12px;
  border-radius: 4px;
  cursor: pointer;
}
.guide-tab:hover {
  color: var(--sun-text);
}
.guide-tab.is-active {
  color: var(--sun-text);
  border-color: var(--sun-accent, #4098ff);
}
.guide-updated {
  margin-left: auto;
  font-size: 12px;
  color: var(--sun-text-muted);
}
.guide-input :deep(.n-input__textarea-el) {
  font-family: var(--sun-font-mono, monospace);
  font-size: 13px;
  line-height: 1.6;
}
.guide-preview {
  border: 1px solid var(--sun-border);
  border-radius: 4px;
  padding: 4px 14px;
  min-height: 360px;
  max-height: 560px;
  overflow: auto;
}
.guide-tip {
  margin-top: 8px;
  font-size: 12px;
  color: var(--sun-text-muted);
}
</style>
