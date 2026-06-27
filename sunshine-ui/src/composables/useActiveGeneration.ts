export interface ActiveGeneration {
  generationId: string
  messageId: string
  conversationId: string
  lastSeq: number
}

const STORAGE_KEY = 'sunshine-active-generation'

export function saveActiveGeneration(g: ActiveGeneration): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(g))
  } catch { /* quota / private mode */ }
}

export function loadActiveGeneration(): ActiveGeneration | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const g = JSON.parse(raw) as ActiveGeneration
    if (!g.generationId || !g.messageId || !g.conversationId) return null
    return { ...g, lastSeq: g.lastSeq ?? 0 }
  } catch {
    return null
  }
}

export function clearActiveGeneration(): void {
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch { /* ignore */ }
}

/** 仅清除与指定会话匹配的 active generation，避免误伤其他会话 */
export function clearActiveGenerationIfMatch(conversationId: string): void {
  const g = loadActiveGeneration()
  if (g?.conversationId === conversationId) {
    clearActiveGeneration()
  }
}

export function updateLastSeq(seq: number): void {
  const g = loadActiveGeneration()
  if (!g || seq <= g.lastSeq) return
  saveActiveGeneration({ ...g, lastSeq: seq })
}
