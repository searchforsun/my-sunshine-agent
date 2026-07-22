<script setup lang="ts">
import { inject } from 'vue'
import { NDropdown, NEmpty, NIcon, NInput, NSpin, NSwitch, NTag } from 'naive-ui'
import { EllipsisHorizontal, SearchOutline } from '@vicons/ionicons5'
import { WORKFLOWS_PAGE_KEY, type WorkflowsPageApi } from '../../composables/useWorkflowsPage'
import {
  isWorkflowSwitchDisabled,
  listCardActiveVersionLine,
} from '../../utils/workflows/workflowsVersionUtils'

const page = inject(WORKFLOWS_PAGE_KEY) as WorkflowsPageApi
</script>

<template>
  <aside class="list-panel">
    <div class="panel-head">
      <span class="panel-title">列表</span>
      <NTag :bordered="false" size="tiny" round>{{ page.filteredWorkflows.length }}</NTag>
    </div>
    <div class="list-search">
      <NInput
        v-model:value="page.workflowSearch"
        placeholder="搜索名称或 ID…"
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
        <div v-if="page.filteredWorkflows.length === 0 && !page.loading" class="empty-wrap">
          <NEmpty size="small" description="暂无工作流" />
        </div>
        <div
          v-for="wf in page.filteredWorkflows"
          :key="wf.id"
          class="wf-card"
          :class="{ active: wf.id === page.selectedId, disabled: !wf.enabled }"
        >
          <button type="button" class="wf-card-hit" @click="void page.selectWorkflow(wf.id)">
            <div class="wf-card-top">
              <div class="wf-card-names">
                <span class="wf-title">{{ wf.id }}</span>
                <span v-if="wf.displayName && wf.displayName !== wf.id" class="wf-subtitle">{{ wf.displayName }}</span>
                <span class="wf-version-line">{{ listCardActiveVersionLine(wf) }}</span>
              </div>
              <NSwitch
                :value="wf.enabled"
                :disabled="page.isWorkflowSwitchDisabled(wf)"
                size="small"
                @click.stop
                @update:value="(v: boolean) => page.toggleEnabled(wf, v)"
              />
            </div>
            <p v-if="wf.description" class="wf-desc">{{ wf.description }}</p>
          </button>
          <NDropdown
            trigger="click"
            size="small"
            :options="page.cardMenuOptions"
            @select="(key) => page.handleCardMenuSelect(wf, String(key))"
          >
            <button
              type="button"
              class="wf-card-more-btn"
              title="工作流操作"
              aria-label="工作流操作"
              @click.stop
            >
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
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px 0;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--sun-text-secondary);
}

.list-search {
  padding: 10px 12px;
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
  min-height: 0;
  overflow-y: auto;
  padding: 0 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.empty-wrap {
  padding: 24px 8px;
}

.wf-card {
  position: relative;
  border-radius: var(--radius-md);
  border: 1px solid var(--sun-border);
  background: var(--sun-black);
  transition: border-color 0.15s;
}

.wf-card:hover {
  border-color: var(--sun-text-muted);
}

.wf-card.active {
  font-weight: 600;
  border-color: var(--sun-text);
}

.wf-card.disabled {
  opacity: 0.85;
}

.wf-card-hit {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  text-align: left;
  padding: 10px 36px 10px 12px;
  border: none;
  background: transparent;
  color: var(--sun-text);
  cursor: pointer;
}

.wf-card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.wf-card-names {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.wf-title {
  font-size: 13px;
  font-weight: 600;
  font-family: var(--sun-font-mono);
}

.wf-subtitle {
  font-size: 12px;
  color: var(--sun-text-secondary);
}

.wf-version-line {
  font-size: 11px;
  color: var(--sun-text-muted);
}

.wf-desc {
  margin: 0;
  font-size: 12px;
  color: var(--sun-text-muted);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.wf-card-more-btn {
  position: absolute;
  right: 6px;
  bottom: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--sun-text-muted);
  cursor: pointer;
}

.wf-card-more-btn:hover {
  color: var(--sun-text);
}
</style>
