/** 知识库 / RAG 租户选项（与 verify_tenant_live、rag_ingest_bulk 对齐） */

export type TenantId = string

export interface TenantOption {
  value: TenantId
  label: string
  description?: string
}

export const TENANT_OPTIONS: TenantOption[] = [
  { value: 'default', label: 'default', description: '默认租户' },
  { value: 'tenant-a', label: 'tenant-a', description: '租户 A（评测语料）' },
  { value: 'tenant-b', label: 'tenant-b', description: '租户 B（隔离验证）' },
]

export const TENANT_PREFERENCE_STORAGE_KEY = 'sunshine-knowledge-tenant'

export function findTenantOption(value: TenantId): TenantOption {
  return TENANT_OPTIONS.find((o) => o.value === value) ?? {
    value,
    label: value,
    description: '自定义租户',
  }
}

export function isKnownTenant(value: string | null | undefined): value is TenantId {
  return typeof value === 'string' && value.trim().length > 0
}
