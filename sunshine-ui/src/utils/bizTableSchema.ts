import type { SelectOption } from 'naive-ui'
import type { BizDomain, BizTable } from '../api/bizData'

export type FieldKind =
  | 'text'
  | 'number'
  | 'user'
  | 'textarea'
  | 'select'
  | 'date'
  | 'month'
  | 'year'

export interface FieldDef {
  key: string
  label: string
  kind: FieldKind
  required?: boolean
  options?: SelectOption[]
  /** 复合主键字段：编辑时若改动则 delete+create */
  compositeKey?: boolean
}

export interface TableDef {
  key: BizTable
  label: string
  fields: FieldDef[]
  /** 请求体不含这些键 */
  excludeFromBody?: string[]
}

export const BIZ_DOMAINS: { key: BizDomain; label: string }[] = [
  { key: 'finance', label: '财务' },
  { key: 'hr', label: '人事' },
  { key: 'oa', label: 'OA' },
]

const STATUS_OPTIONS: SelectOption[] = [
  { label: '待处理 pending', value: 'pending' },
  { label: '已通过 approved', value: 'approved' },
  { label: '已驳回 rejected', value: 'rejected' },
]

const TENANT_OPTIONS: SelectOption[] = [
  { label: 'default', value: 'default' },
]

const EXPENSE_CATEGORY_OPTIONS: SelectOption[] = [
  { label: '市内交通', value: '市内交通' },
  { label: '差旅住宿', value: '差旅住宿' },
  { label: '差旅交通', value: '差旅交通' },
  { label: '餐饮', value: '餐饮' },
  { label: '办公用品', value: '办公用品' },
  { label: '其他', value: '其他' },
]

const LEAVE_TYPE_OPTIONS: SelectOption[] = [
  { label: '年假 annual', value: 'annual' },
  { label: '青松假 qingsong', value: 'qingsong' },
  { label: '调休 compensatory', value: 'compensatory' },
]

const OA_CATEGORY_OPTIONS: SelectOption[] = [
  { label: '行政 admin', value: 'admin' },
  { label: '请假 leave', value: 'leave' },
  { label: '合同 contract', value: 'contract' },
  { label: '其他 other', value: 'other' },
]

export const BIZ_TABLE_DEFS: Record<BizDomain, TableDef[]> = {
  finance: [
    {
      key: 'expenses',
      label: '报销单',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'category', label: '类别', kind: 'select', required: true, options: EXPENSE_CATEGORY_OPTIONS },
        { key: 'amount', label: '金额', kind: 'number', required: true },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
        { key: 'occurredOn', label: '发生日', kind: 'date', required: true },
        { key: 'remark', label: '备注', kind: 'textarea' },
      ],
      excludeFromBody: ['id'],
    },
    {
      key: 'inbox',
      label: '财务待办',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'title', label: '标题', kind: 'text', required: true },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
        { key: 'amount', label: '金额', kind: 'number', required: true },
      ],
      excludeFromBody: ['id'],
    },
  ],
  hr: [
    {
      key: 'leave-balances',
      label: '假期余额',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true, compositeKey: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'year', label: '年份', kind: 'year', required: true, compositeKey: true },
        { key: 'annual', label: '年假', kind: 'number', required: true },
        { key: 'qingsong', label: '青松假', kind: 'number', required: true },
        { key: 'compensatory', label: '调休', kind: 'number', required: true },
      ],
    },
    {
      key: 'leave-requests',
      label: '请假单',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'leaveType', label: '假别', kind: 'select', required: true, options: LEAVE_TYPE_OPTIONS },
        { key: 'startDate', label: '开始日', kind: 'date', required: true },
        { key: 'endDate', label: '结束日', kind: 'date', required: true },
        { key: 'reason', label: '事由', kind: 'textarea' },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
      ],
      excludeFromBody: ['id'],
    },
    {
      key: 'attendance-months',
      label: '考勤月报',
      fields: [
        { key: 'userId', label: '用户', kind: 'user', required: true, compositeKey: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'yearMonth', label: '年月', kind: 'month', required: true, compositeKey: true },
        { key: 'lateCount', label: '迟到次数', kind: 'number', required: true },
        { key: 'overtimeHours', label: '加班小时', kind: 'number', required: true },
        { key: 'frostLedgerSummary', label: '霜冻台账', kind: 'textarea' },
      ],
    },
  ],
  oa: [
    {
      key: 'tasks',
      label: 'OA 待办',
      fields: [
        { key: 'assigneeUserId', label: '负责人', kind: 'user', required: true },
        { key: 'tenantId', label: '租户', kind: 'select', required: true, options: TENANT_OPTIONS },
        { key: 'title', label: '标题', kind: 'text', required: true },
        { key: 'category', label: '类别', kind: 'select', required: true, options: OA_CATEGORY_OPTIONS },
        { key: 'status', label: '状态', kind: 'select', required: true, options: STATUS_OPTIONS },
      ],
      excludeFromBody: ['id'],
    },
  ],
}

export const BIZ_TENANT_ID = 'default'
