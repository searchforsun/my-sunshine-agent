/** 侧栏平台/对话/任务分区排布偏好 */
export type SidebarSectionsLayout = 'vertical' | 'horizontal'

export const SIDEBAR_SECTIONS_LAYOUT_OPTIONS: Array<{
  value: SidebarSectionsLayout
  label: string
}> = [
  { value: 'vertical', label: '纵向' },
  { value: 'horizontal', label: '横向' },
]

export function isSidebarSectionsLayout(value: unknown): value is SidebarSectionsLayout {
  return value === 'vertical' || value === 'horizontal'
}

export function normalizeSidebarSectionsLayout(value: unknown): SidebarSectionsLayout {
  return isSidebarSectionsLayout(value) ? value : 'vertical'
}
