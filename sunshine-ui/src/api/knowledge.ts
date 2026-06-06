const API_BASE = 'http://localhost:8400'

export async function uploadDocument(content: string): Promise<{ chunks: number }> {
  const response = await fetch(`${API_BASE}/api/rag/documents`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })
  return response.json()
}

export async function searchKnowledge(query: string, topK = 5): Promise<string[]> {
  const response = await fetch(`${API_BASE}/api/rag/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query, topK }),
  })
  const data = await response.json()
  return data.results || []
}
