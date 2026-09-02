<script setup lang="ts">
import { NIcon, NTooltip } from 'naive-ui'
import { MicOutline } from '@vicons/ionicons5'
import { useSpeechRecognition } from '../../composables/useSpeechRecognition'

const { isSupported, isListening, start } = useSpeechRecognition()

function handleClick() {
  // 先释放 contenteditable 的光标，避免 | 残留在页面上
  ;(document.activeElement as HTMLElement)?.blur()
  start()
}
</script>

<template>
  <NTooltip v-if="isSupported && !isListening" trigger="hover" :delay="500">
    <template #trigger>
      <button
        type="button"
        class="voice-btn"
        aria-label="开始语音输入"
        @click="handleClick"
      >
        <NIcon :size="18" :component="MicOutline" />
      </button>
    </template>
    <span>点击开始语音输入</span>
  </NTooltip>
</template>

<style scoped>
.voice-btn {
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  border: 1px solid var(--sun-border);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: transparent;
  color: var(--sun-text-secondary);
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}

.voice-btn:hover {
  background: var(--sun-row-hover);
  border-color: var(--sun-text-muted);
}
</style>
