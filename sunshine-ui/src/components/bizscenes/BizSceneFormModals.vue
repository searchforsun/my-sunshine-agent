<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal, NSelect } from 'naive-ui'
import { BIZ_SCENES_PAGE_KEY, type BizScenesPageApi } from '../../composables/useBizScenesPage'

const page = inject(BIZ_SCENES_PAGE_KEY) as BizScenesPageApi

const statusOptions = [
  { label: '启用', value: 'active' },
  { label: '退役', value: 'retired' },
]
</script>

<template>
  <NModal v-model:show="page.showCreate" preset="dialog" title="新建业务场景" class="sunshine-dialog">
    <NForm label-placement="left" label-width="90">
      <NFormItem label="场景码" required>
        <NInput v-model:value="page.createDraft.bizScene" class="sun-field" placeholder="compliance-review" />
      </NFormItem>
      <NFormItem label="名称" required>
        <NInput v-model:value="page.createDraft.displayName" class="sun-field" placeholder="费用合规审查" />
      </NFormItem>
      <NFormItem label="描述">
        <NInput
          v-model:value="page.createDraft.description"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
        />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="page.showCreate = false">取消</NButton>
      <NButton type="primary" class="action-btn" :loading="page.creating" @click="page.handleCreate()">创建</NButton>
    </template>
  </NModal>

  <NModal v-model:show="page.showEdit" preset="dialog" title="编辑业务场景" class="sunshine-dialog">
    <NForm label-placement="left" label-width="90">
      <NFormItem label="场景码">
        <NInput :value="page.editDraft.bizScene" class="sun-field" disabled />
      </NFormItem>
      <NFormItem label="名称" required>
        <NInput v-model:value="page.editDraft.displayName" class="sun-field" placeholder="场景名称" />
      </NFormItem>
      <NFormItem label="描述">
        <NInput
          v-model:value="page.editDraft.description"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
        />
      </NFormItem>
      <NFormItem label="状态">
        <NSelect v-model:value="page.editDraft.status" class="sun-field" :options="statusOptions" />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="page.showEdit = false">取消</NButton>
      <NButton type="primary" class="action-btn" :loading="page.editing" @click="page.handleEdit()">保存</NButton>
    </template>
  </NModal>

  <NModal
    v-model:show="page.showCreateRule"
    preset="dialog"
    title="新增规则"
    class="sunshine-dialog"
    @after-leave="page.ruleDraft = ''"
  >
    <NForm label-placement="left" label-width="90">
      <NFormItem label="规则提示词" required>
        <NInput
          v-model:value="page.ruleDraft"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 10 }"
          placeholder="该场景下的行为规则提示词，作为一项参与场景执行…"
        />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="page.showCreateRule = false">取消</NButton>
      <NButton
        type="primary"
        class="action-btn"
        :loading="page.savingRule"
        :disabled="!page.ruleDraft.trim()"
        @click="page.handleSaveRule()"
      >添加</NButton>
    </template>
  </NModal>
</template>

<style scoped>
.action-btn {
  --n-color: var(--sun-accent) !important;
  --n-color-hover: var(--sun-accent-hover) !important;
  --n-color-pressed: var(--sun-accent-hover) !important;
  --n-color-focus: var(--sun-accent-hover) !important;
  --n-text-color: var(--btn-primary-text) !important;
  --n-text-color-hover: var(--btn-primary-text) !important;
  --n-text-color-pressed: var(--btn-primary-text) !important;
  --n-text-color-focus: var(--btn-primary-text) !important;
  --n-border: none !important;
}

:deep(.sun-field .n-input),
:deep(.sun-field .n-input-wrapper),
:deep(.sun-field .n-base-selection),
:deep(.sun-field .n-input-number) {
  background: var(--sun-black) !important;
}
</style>
