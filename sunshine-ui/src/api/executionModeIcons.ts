import type { Component } from 'vue'
import {
  BulbOutline,
  ChatbubbleOutline,
  FlashOutline,
  GitNetworkOutline,
  LayersOutline,
} from '@vicons/ionicons5'
import type { ExecutionPreference } from './executionModes'

/** 执行模式图标 — 与 EXECUTION_MODE_OPTIONS 一一对应 */
export const EXECUTION_MODE_ICONS: Record<ExecutionPreference, Component> = {
  auto: FlashOutline,
  'simple-llm': ChatbubbleOutline,
  react: BulbOutline,
  workflow: LayersOutline,
  'plan-workflow': GitNetworkOutline,
}

export function executionModeIcon(value: ExecutionPreference): Component {
  return EXECUTION_MODE_ICONS[value] ?? FlashOutline
}
