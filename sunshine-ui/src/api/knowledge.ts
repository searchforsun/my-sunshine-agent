/** @deprecated 请使用 ragAdmin.ts；本模块保留兼容包装 */
import type { TenantId } from './tenants'
import { ingestText, searchKnowledgePublic } from './ragAdmin'

export async function uploadDocument(
  content: string,
  tenantId: TenantId,
  docName?: string,
): Promise<{ chunks: number; docName?: string }> {
  const result = await ingestText(tenantId, 'default', content, docName)
  return { chunks: result.chunks, docName: result.docName }
}

export interface KnowledgeHit {
  docName: string
  content: string
  score: number
}

export async function searchKnowledge(
  query: string,
  tenantId: TenantId,
  topK = 5,
): Promise<KnowledgeHit[]> {
  return searchKnowledgePublic(query, tenantId, 'default', topK)
}
