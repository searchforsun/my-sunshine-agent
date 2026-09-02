import type { TenantId } from '../tenants'
import { resolveApiBase } from '../config'
import { apiHeaders } from '../../stores/authStore'

const ADMIN_TOKEN = import.meta.env.VITE_RAG_ADMIN_TOKEN ?? 'sunshine-rag-admin-dev'

export function ragApiBase(): string {
  const configured = import.meta.env.VITE_RAG_API_BASE?.trim()
  if (configured) return configured.replace(/\/$/, '')
  return resolveApiBase()
}

export function adminHeaders(tenantId: TenantId): Record<string, string> {
  const tid = tenantId.trim() || 'default'
  return {
    ...apiHeaders(),
    'x-tenant-id': tid,
    'X-Admin-Token': ADMIN_TOKEN,
  }
}
