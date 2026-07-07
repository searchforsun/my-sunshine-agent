<script setup lang="ts">
import { NDropdown, NEmpty, NIcon, NInput, NSpin, NSwitch, NTag } from 'naive-ui'
import { EllipsisHorizontal, SearchOutline } from '@vicons/ionicons5'
import type { SkillsPageApi } from '../../composables/useSkillsPage'
import {
  isSkillSwitchDisabled,
  listCardActiveVersionLine,
  listCardMaintainer,
} from '../../utils/skills/skillsVersionUtils'

defineProps<{ page: SkillsPageApi }>()
</script>

<template>
  <aside class="list-panel">
    <div class="panel-head">
      <span class="panel-title">列表</span>
      <NTag :bordered="false" size="tiny" round>{{ page.filteredSkills.length }}</NTag>
    </div>
    <div class="list-search">
      <NInput
        v-model:value="page.skillSearch"
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
        <div v-if="page.filteredSkills.length === 0 && !page.loading" class="empty-wrap">
          <NEmpty size="small" description="暂无 Skill" />
        </div>
        <div
          v-for="skill in page.filteredSkills"
          :key="skill.id"
          class="skill-card"
          :class="{ active: skill.id === page.selectedId, disabled: !skill.enabled }"
        >
          <button type="button" class="skill-card-hit" @click="void page.selectSkill(skill.id)">
            <div class="skill-card-top">
              <div class="skill-card-names">
                <span class="skill-title">{{ skill.id }}</span>
                <span v-if="skill.displayName && skill.displayName !== skill.id" class="skill-subtitle">{{ skill.displayName }}</span>
                <span class="skill-version-line">{{ listCardActiveVersionLine(skill) }}</span>
                <span v-if="listCardMaintainer(skill)" class="skill-maintainer">{{ listCardMaintainer(skill) }}</span>
              </div>
              <NSwitch
                :value="skill.enabled"
                :disabled="isSkillSwitchDisabled(skill)"
                size="small"
                @click.stop
                @update:value="(v: boolean) => page.toggleEnabled(skill, v)"
              />
            </div>
            <p v-if="skill.description" class="skill-desc">{{ skill.description }}</p>
          </button>
          <NDropdown
            trigger="click"
            size="small"
            :options="page.cardMenuOptions"
            @select="(key) => page.handleCardMenuSelect(skill, String(key))"
          >
            <button
              type="button"
              class="skill-card-more-btn"
              title="Skill 操作"
              aria-label="Skill 操作"
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
  --n-text-color: var(--sun-text) !important;
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

.skill-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  padding: 12px 12px 30px;
  border: 1px solid var(--sun-border);
  border-radius: var(--radius-md);
  background: var(--sun-black);
  transition: border-color 0.2s, box-shadow 0.2s;
}

.skill-card-hit {
  width: 100%;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}

.skill-card-more-btn {
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

.skill-card-more-btn:hover {
  background: var(--sun-row-hover);
  color: var(--sun-text);
}

.skill-card:hover {
  border-color: var(--sun-border-light);
}

.skill-card.active {
  border-color: var(--sun-border-light);
  outline: 1px solid color-mix(in srgb, var(--sun-text-muted) 45%, transparent);
  outline-offset: -2px;
}

.skill-card.disabled {
  opacity: 0.72;
}

.skill-card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.skill-card-names {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.skill-title {
  font-weight: 600;
  font-size: 14px;
  color: var(--sun-text);
  line-height: 1.3;
  font-family: 'JetBrains Mono', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-subtitle {
  font-size: 12px;
  color: var(--sun-text-secondary);
  line-height: 1.35;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-version-line {
  font-size: 11px;
  color: var(--sun-text-secondary);
  font-family: 'JetBrains Mono', monospace;
  line-height: 1.35;
}

.skill-maintainer {
  font-size: 11px;
  color: var(--sun-text-muted);
  line-height: 1.35;
}

.skill-desc {
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
