import type { Component } from 'vue'
import {
  BulbOutline,
  FlashOutline,
  LayersOutline,
} from '@vicons/ionicons5'
import type { ExecutionMode } from './executionModes'

/** 执行模式图标 — 与 EXECUTION_MODE_OPTIONS 一一对应 */
export const EXECUTION_MODE_ICONS: Record<ExecutionMode, Component> = {
  fast: FlashOutline,
  pro: BulbOutline,
  workflow: LayersOutline,
}

export function executionModeIcon(value: ExecutionMode): Component {
  return EXECUTION_MODE_ICONS[value] ?? FlashOutline
}
