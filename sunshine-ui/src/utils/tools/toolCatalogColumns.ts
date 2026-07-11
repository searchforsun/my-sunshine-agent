import { h } from 'vue'
import {
  NButton,
  NIcon,
  NSpace,
  NSwitch,
  NTag,
  NTooltip,
  type DataTableColumns,
} from 'naive-ui'
import { CreateOutline } from '@vicons/ionicons5'
import type { Ref } from 'vue'
import type { ToolCatalogEntry } from '../../api/tools'
import {
  formatTimelineExtractHint,
  formatTimelineTemplateLabel,
} from '../toolTimelineDisplay'

export interface ToolCatalogColumnHandlers {
  enabledMap: Ref<Map<string, boolean>>
  onToggleTool: (row: ToolCatalogEntry, enabled: boolean) => void
  onToggleConfirmation: (row: ToolCatalogEntry, requireConfirmation: boolean) => void
  onOpenSchema: (row: ToolCatalogEntry) => void
  onOpenEdit: (row: ToolCatalogEntry) => void
}

function sideEffectLabel(sideEffect: string): string {
  return sideEffect === 'write' ? '写' : '读'
}

function sideEffectTagType(sideEffect: string): 'warning' | 'default' {
  return sideEffect === 'write' ? 'warning' : 'default'
}

export function createToolCatalogColumns(handlers: ToolCatalogColumnHandlers): DataTableColumns<ToolCatalogEntry> {
  function renderTimelineTemplate(row: ToolCatalogEntry) {
    const label = formatTimelineTemplateLabel(row.timelineSummaryTemplate)
    const extract = formatTimelineExtractHint(row.timelineSummaryExtract)
    return h('div', { class: 'tool-timeline-cell' }, [
      extract
        ? h(NTooltip, { trigger: 'hover', placement: 'top-start' }, {
            trigger: () => h('span', { class: 'tool-timeline-template' }, label),
            default: () => h('pre', { class: 'tool-timeline-extract-tip' }, extract),
          })
        : h('span', { class: 'tool-timeline-template' }, label),
    ])
  }

  return [
    { title: '工具 ID', key: 'id', ellipsis: { tooltip: true } },
    { title: '展示名', key: 'displayName', ellipsis: { tooltip: true } },
    {
      title: '读写',
      key: 'sideEffect',
      width: 64,
      render: (row) => h(NTag, {
        size: 'small',
        bordered: false,
        type: sideEffectTagType(row.sideEffect),
      }, { default: () => sideEffectLabel(row.sideEffect) }),
    },
    {
      title: '时间线摘要',
      key: 'timelineSummaryTemplate',
      minWidth: 220,
      render: (row) => renderTimelineTemplate(row),
    },
    {
      title: '人工确认',
      key: 'requireConfirmation',
      width: 88,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: row.requireConfirmation,
        disabled: row.idValid === false,
        onUpdateValue: (v: boolean) => handlers.onToggleConfirmation(row, v),
      }),
    },
    {
      title: '状态',
      key: 'idValid',
      width: 88,
      render: (row) => row.idValid === false
        ? h(NTag, { type: 'error', size: 'small', bordered: false }, { default: () => '非法 ID' })
        : null,
    },
    {
      title: '启用',
      key: 'enabled',
      width: 72,
      render: (row) => h(NSwitch, {
        size: 'small',
        value: handlers.enabledMap.value.get(row.id) ?? false,
        disabled: row.idValid === false,
        onUpdateValue: (v: boolean) => handlers.onToggleTool(row, v),
      }),
    },
    {
      title: '操作',
      key: 'actions',
      width: 148,
      render: (row) => h(NSpace, { size: 4, align: 'center' }, {
        default: () => [
          h(NButton, {
            size: 'tiny',
            quaternary: true,
            onClick: () => handlers.onOpenSchema(row),
          }, { default: () => 'Schema' }),
          h(NButton, {
            size: 'tiny',
            quaternary: true,
            onClick: () => handlers.onOpenEdit(row),
          }, {
            icon: () => h(NIcon, { component: CreateOutline, size: 14 }),
            default: () => '配置',
          }),
        ],
      }),
    },
  ]
}
