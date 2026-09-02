import type { AgentCatalogIndexEntry } from '../api/agents'

export function findAgentByToken(
  token: string,
  catalog: AgentCatalogIndexEntry[],
): AgentCatalogIndexEntry | undefined {
  const lower = token.toLowerCase()
  return catalog.find(e => e.enabled && (
    e.id.toLowerCase() === lower
    || e.displayName.toLowerCase() === lower
  ))
}
