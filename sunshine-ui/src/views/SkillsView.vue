<script setup lang="ts">
import { NButton, NCard, NEmpty, NIcon, NSpace, NSpin } from 'naive-ui'
import { AddOutline, RefreshOutline } from '@vicons/ionicons5'
import SidebarToggle from '../components/SidebarToggle.vue'
import SkillsListPanel from '../components/skills/SkillsListPanel.vue'
import SkillDetailPanel from '../components/skills/SkillDetailPanel.vue'
import SkillFormModals from '../components/skills/SkillFormModals.vue'
import { useSkillsPage, type SkillsPageApi } from '../composables/useSkillsPage'

const page = useSkillsPage()
const skillsPage = page as unknown as SkillsPageApi
</script>

<template>
  <div class="skills-root">
    <input
      id="skill-folder-picker"
      :ref="(el) => { skillsPage.folderInputRef = el as HTMLInputElement | null }"
      type="file"
      webkitdirectory
      directory
      multiple
      class="folder-picker-input"
      @change="skillsPage.onFolderPicked"
    />
    <header class="page-header">
      <div class="page-header-main">
        <SidebarToggle />
        <h2>Skills 管理</h2>
      </div>
      <NSpace :size="8">
        <NButton round secondary @click="skillsPage.showCreate = true">
          <template #icon><NIcon :component="AddOutline" /></template>
          新建
        </NButton>
        <NButton round type="primary" class="action-btn" :loading="skillsPage.loading" @click="skillsPage.refreshPage">
          <template #icon><NIcon :component="RefreshOutline" /></template>
          刷新
        </NButton>
      </NSpace>
    </header>

    <div class="skills-layout">
      <SkillsListPanel :page="skillsPage" />
      <SkillDetailPanel v-if="skillsPage.selectedSkill" :page="skillsPage" />
      <NCard v-else class="detail-empty" size="small">
        <NSpin :show="skillsPage.loading">
          <NEmpty />
        </NSpin>
      </NCard>
    </div>

    <SkillFormModals :page="skillsPage" />
  </div>
</template>

<style scoped>
.skills-root {
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
  gap: 16px;
  flex-shrink: 0;
  min-height: 36px;
}

.page-header-main {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.4px;
  line-height: 36px;
  color: var(--sun-text);
}

.skills-layout {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(280px, 320px) 1fr;
  gap: 16px;
}

.detail-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-lg) !important;
  border: 1px solid var(--sun-border) !important;
  background: var(--sun-black) !important;
  min-height: 0;
  overflow: hidden;
}

.folder-picker-input {
  position: fixed;
  left: -10000px;
  top: 0;
  width: 1px;
  height: 1px;
  opacity: 0;
}

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

:deep(.more-menu-delete) {
  color: var(--n-color-error);
}

@media (max-width: 960px) {
  .skills-layout {
    grid-template-columns: 1fr;
    grid-template-rows: auto 1fr;
  }
}
</style>
