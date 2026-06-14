const API_BASE = 'http://localhost:8400'

export async function uploadDocument(
  content: string,
  docName?: string,
): Promise<{ chunks: number; docName?: string }> {
  const response = await fetch(`${API_BASE}/api/rag/documents`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content, docName }),
  })
  return response.json()
}

export interface KnowledgeHit {
  docName: string
  content: string
  score: number
}

export async function searchKnowledge(query: string, topK = 5): Promise<KnowledgeHit[]> {
  const response = await fetch(`${API_BASE}/api/rag/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, topK }),
  })
  const data = await response.json()
  return data.results || []
}
