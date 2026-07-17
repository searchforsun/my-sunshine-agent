<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal, NSpace } from 'naive-ui'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi
</script>

<template>
  <NModal
    v-model:show="page.showCreate"
    preset="card"
    :title="page.isDuplicateCreate ? '另存为新工作流' : '新建工作流'"
    :style="{ width: '420px' }"
    :bordered="false"
    class="sun-modal"
  >
    <p v-if="page.isDuplicateCreate" class="create-hint">
      将复制当前版本的 Plan 与流程配置到新工作流，创建后可在 Studio 继续编辑。
    </p>
    <NForm label-placement="top" size="small">
      <NFormItem label="Workflow ID" required>
        <NInput
          v-model:value="page.createDraft.id"
          class="sun-field"
          placeholder="如 my-report-flow"
        />
      </NFormItem>
      <NFormItem label="展示名" required>
        <NInput v-model:value="page.createDraft.displayName" class="sun-field" placeholder="中文名称" />
      </NFormItem>
      <NFormItem label="描述" required>
        <NInput
          v-model:value="page.createDraft.description"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="用于路由命中，不可为空"
        />
      </NFormItem>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton round @click="page.closeCreateModal()">取消</NButton>
        <NButton
          round
          type="primary"
          class="action-btn"
          :disabled="!page.canConfirmCreate"
          @click="void page.confirmCreate()"
        >
          {{ page.isDuplicateCreate ? '另存' : '创建' }}
        </NButton>
      </NSpace>
    </template>
  </NModal>

  <NModal
    v-model:show="page.showEdit"
    preset="card"
    title="修改工作流"
    :style="{ width: '420px' }"
    :bordered="false"
    class="sun-modal"
  >
    <NForm label-placement="top" size="small">
      <NFormItem label="展示名" required>
        <NInput v-model:value="page.editForm.displayName" class="sun-field" />
      </NFormItem>
      <NFormItem label="描述" required>
        <NInput
          v-model:value="page.editForm.description"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="用于路由命中"
        />
      </NFormItem>
    </NForm>
    <template #footer>
      <NSpace justify="end">
        <NButton round @click="page.showEdit = false">取消</NButton>
        <NButton round type="primary" class="action-btn" @click="void page.confirmEdit()">保存</NButton>
      </NSpace>
    </template>
  </NModal>

  <NModal
    v-model:show="page.showDeleteConfirm"
    preset="card"
    title="删除工作流"
    :style="{ width: '400px' }"
    :bordered="false"
    class="sun-modal"
  >
    <p>确定删除该工作流及其所有版本？此操作不可恢复。</p>
    <template #footer>
      <NSpace justify="end">
        <NButton round @click="page.showDeleteConfirm = false">取消</NButton>
        <NButton round type="error" @click="void page.confirmDelete()">删除</NButton>
      </NSpace>
    </template>
  </NModal>

  <NModal
    v-model:show="page.showDeleteVersionConfirm"
    preset="card"
    title="删除版本"
    :style="{ width: '400px' }"
    :bordered="false"
    class="sun-modal"
  >
    <p>确定删除当前选中版本？</p>
    <template #footer>
      <NSpace justify="end">
        <NButton round @click="page.showDeleteVersionConfirm = false">取消</NButton>
        <NButton round type="error" @click="void page.confirmDeleteVersion()">删除</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.create-hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--sun-text-muted);
}
</style>
