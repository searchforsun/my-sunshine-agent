<script setup lang="ts">
import { inject } from 'vue'
import { NButton, NForm, NFormItem, NInput, NModal } from 'naive-ui'
import { SKILLS_PAGE_KEY, type SkillsPageApi } from '../../composables/useSkillsPage'

const page = inject(SKILLS_PAGE_KEY) as SkillsPageApi
</script>

<template>
  <NModal v-model:show="page.showCreate" preset="dialog" title="新建 Skill" class="sunshine-dialog">
    <NForm label-placement="left" label-width="90">
      <NFormItem label="ID" required>
        <NInput v-model:value="page.createForm.id" class="sun-field" placeholder="finance-analysis" />
      </NFormItem>
      <NFormItem label="显示名" required>
        <NInput v-model:value="page.createForm.displayName" class="sun-field" placeholder="财务合规分析" />
      </NFormItem>
      <NFormItem label="描述">
        <NInput v-model:value="page.createForm.description" class="sun-field" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="可选；上传 SKILL.md 后将以其 frontmatter description 为准" />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="page.showCreate = false">取消</NButton>
      <NButton type="primary" class="action-btn" :loading="page.creating" @click="page.handleCreateConfirm">创建</NButton>
    </template>
  </NModal>
  <NModal
    v-model:show="page.showEdit"
    preset="dialog"
    title="修改 Skill"
    class="sunshine-dialog"
    @after-leave="page.editTargetSkill = null"
  >
    <NForm label-placement="left" label-width="90">
      <NFormItem label="ID">
        <NInput class="sun-field" :value="page.editTargetSkill?.id ?? ''" disabled />
      </NFormItem>
      <NFormItem label="显示名" required>
        <NInput v-model:value="page.editForm.displayName" class="sun-field" placeholder="财务合规分析" />
      </NFormItem>
      <NFormItem label="描述">
        <NInput
          v-model:value="page.editForm.description"
          class="sun-field"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 4 }"
          placeholder="可选"
        />
      </NFormItem>
    </NForm>
    <template #action>
      <NButton @click="page.showEdit = false">取消</NButton>
      <NButton type="primary" class="action-btn" :loading="page.savingEdit" @click="page.handleEditConfirm">保存</NButton>
    </template>
  </NModal>
  <NModal
    v-model:show="page.showDeleteConfirm"
    preset="dialog"
    title="删除 Skill"
    class="sunshine-dialog"
    @after-leave="page.deleteTargetSkill = null"
  >
    <p>确定删除整个 Skill「{{ page.deleteTargetSkill?.id }}」（{{ page.deleteTargetSkill?.displayName }}）？此操作不可恢复。</p>
    <template #action>
      <NButton @click="page.showDeleteConfirm = false">取消</NButton>
      <NButton type="error" :loading="page.deleting" @click="page.handleDeleteConfirm">删除</NButton>
    </template>
  </NModal>
  <NModal v-model:show="page.showDeleteVersionConfirm" preset="dialog" title="删除该版本" class="sunshine-dialog">
    <p>
      确定删除版本「{{ page.formatSkillVersionTime(page.selectedVersionEntry?.createdAt) }}」？
      仅删除该版本文件，Skill 本身保留。
    </p>
    <template #action>
      <NButton @click="page.showDeleteVersionConfirm = false">取消</NButton>
      <NButton type="error" :loading="page.deletingVersion" @click="page.handleDeleteVersionConfirm">删除该版本</NButton>
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
</style>
