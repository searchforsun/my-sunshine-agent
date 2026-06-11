const STORAGE_KEY = 'sunshine-user-id'

export function useUserId(): string {
  let id = localStorage.getItem(STORAGE_KEY)
  if (!id) {
    id = 'user-' + crypto.randomUUID().slice(0, 8)
    localStorage.setItem(STORAGE_KEY, id)
  }
  return id
}

export function apiHeaders(): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    'x-user-id': useUserId(),
    'x-tenant-id': 'default',
  }
}
