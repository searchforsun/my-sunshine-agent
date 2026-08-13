<script setup lang="ts">
import { inject } from 'vue'
import { NDropdown, NEmpty, NIcon, NInput, NSpin, NSwitch, NTag } from 'naive-ui'
import { EllipsisHorizontal, SearchOutline } from '@vicons/ionicons5'
import { BIZ_SCENES_PAGE_KEY, type BizScenesPageApi } from '../../composables/useBizScenesPage'
import type { BizSceneEntry } from '../../api/bizScenes'

const page = inject(BIZ_SCENES_PAGE_KEY) as BizScenesPageApi

const cardMenuOptions = [
  { label: '编辑', key: 'edit' },
  { label: '删除', key: 'delete' },
]

function handleCardMenuSelect(scene: BizSceneEntry, key: string) {
  page.selectScene(scene.bizScene)
  if (key === 'edit') page.openEdit(scene)
  else if (key === 'delete') page.handleDelete()
}
</script>

<template>
  <aside class="list-panel">
    <div class="panel-head">
      <span class="panel-title">业务场景</span>
      <NTag :bordered="false" size="tiny" round>{{ page.scenes.length }}</NTag>
    </div>
    <div class="list-search">
      <NInput
        v-model:value="page.sceneSearch"
        placeholder="搜索名称或码…"
        size="small"
        round
        clearable
        class="search-input"
        :disabled="page.loading"
      >
        <template #prefix>
          <NIcon :component="SearchOutline" :size="14" />
        </template>
      </NInput>
    </div>
    <NSpin :show="page.loading" size="small" class="list-spin">
      <div class="list-body">
        <div v-if="page.filteredScenes.length === 0 && !page.loading" class="empty-wrap">
          <NEmpty size="small" description="暂无业务场景" />
        </div>
        <div
          v-for="scene in page.filteredScenes"
          :key="scene.bizScene"
          class="scene-card"
          :class="{ active: scene.bizScene === page.selectedCode, disabled: scene.status !== 'active' }"
        >
          <button type="button" class="scene-card-hit" @click="page.selectScene(scene.bizScene)">
            <div class="scene-card-top">
              <div class="scene-card-names">
                <span class="scene-title">{{ scene.bizScene }}</span>
                <span v-if="scene.displayName && scene.displayName !== scene.bizScene" class="scene-subtitle">{{ scene.displayName }}</span>
              </div>
              <NSwitch
                :value="scene.status === 'active'"
                size="small"
                :disabled="page.saving"
                @click.stop
                @update:value="(v: boolean) => page.toggleEnabled(scene, v)"
              />
            </div>
            <p v-if="scene.description" class="scene-desc">{{ scene.description }}</p>
          </button>
          <NDropdown
            trigger="click"
            size="small"
            :options="cardMenuOptions"
            @select="(key) => handleCardMenuSelect(scene, String(key))"
          >
            <button type="button" class="scene-card-more-btn" title="场景操作" aria-label="场景操作" @click.stop>
              <NIcon :component="EllipsisHorizontal" :size="14" />
            </button>
          </NDropdown>
        </div>
      </div>
    </NSpin>
  </aside>
</template>

<style scoped>
.list-panel {
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-lg);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  min-height: 0;
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 16px 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--sun-text);
}

.list-search {
  padding: 10px 12px;
}

.search-input {
  --n-color: var(--sun-black) !important;
  --n-color-focus: var(--sun-black) !important;
  --n-color-disabled: var(--sun-black) !important;
  --n-text-color: var(--sun-text) !important;
  --n-text-color-disabled: var(--sun-text-muted) !important;
  --n-placeholder-color: var(--sun-text-muted) !important;
  --n-border: 1px solid var(--sun-border) !important;
  --n-border-focus: 1px solid var(--sun-border-light) !important;
  --n-border-hover: 1px solid var(--sun-border-light) !important;
  --n-box-shadow-focus: none !important;
}

.list-spin {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.list-spin :deep(.n-spin-content) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.list-body {
  flex: 1;
  overflow-y: auto;
  padding: 0 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.scene-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  padding: 12px 12px 30px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  transition: border-color 0.15s ease;
}

.scene-card:hover {
  border-color: var(--sun-border-light);
}

.scene-card.active {
  border-color: var(--sun-text);
}

.scene-card.disabled {
  opacity: 0.72;
}

.scene-card-hit {
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}

.scene-card-more-btn {
  position: absolute;
  right: 6px;
  bottom: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--sun-text-secondary);
  cursor: pointer;
  transition: background 0.2s, color 0.2s;
}

.scene-card-more-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.scene-card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.scene-card-names {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.scene-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--sun-text);
  line-height: 1.3;
  font-family: 'JetBrains Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scene-subtitle {
  font-size: 12px;
  color: var(--sun-text-secondary);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scene-desc {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-secondary);
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.empty-wrap {
  padding: 24px 0;
}

@media (max-width: 960px) {
  .list-panel {
    max-height: 240px;
  }
}
</style>
