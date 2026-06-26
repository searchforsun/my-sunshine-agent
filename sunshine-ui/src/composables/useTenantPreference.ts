import { ref } from 'vue'
import {
  TENANT_OPTIONS,
  TENANT_PREFERENCE_STORAGE_KEY,
  type TenantId,
  isKnownTenant,
} from '../api/tenants'

function loadStoredTenant(): TenantId {
  try {
    const raw = localStorage.getItem(TENANT_PREFERENCE_STORAGE_KEY)
    if (isKnownTenant(raw)) return raw.trim()
  } catch { /* ignore */ }
  return TENANT_OPTIONS[0]?.value ?? 'default'
}

const tenantId = ref<TenantId>(loadStoredTenant())

/** 登录 / me 拉取后同步账号租户，供知识库页与 Chat JWT 一致 */
export function syncTenantFromAuth(value: TenantId) {
  const tid = value.trim() || 'default'
  tenantId.value = tid
  try {
    localStorage.setItem(TENANT_PREFERENCE_STORAGE_KEY, tid)
  } catch { /* ignore */ }
}

export function useTenantPreference() {
  function setTenantId(next: TenantId) {
    const tid = next.trim() || 'default'
    tenantId.value = tid
    try {
      localStorage.setItem(TENANT_PREFERENCE_STORAGE_KEY, tid)
    } catch { /* ignore */ }
  }

  return { tenantId, setTenantId }
}
