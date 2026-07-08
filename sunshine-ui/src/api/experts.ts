import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface ExpertCatalogIndexEntry {
  id: string
  displayName: string
  description: string
  enabled: boolean
}

export interface ExpertEntry {
  id: string
  displayName: string
  description: string
  systemPrompt: string
  skillIds: string[]
  tags: string[]
  toolsJson: string
  enabled: boolean
}

export async function listExperts(): Promise<ExpertEntry[]> {
  const res = await fetch(apiUrl('/api/experts'), { headers: apiHeaders() })
  return parseApiResponse<ExpertEntry[]>(res)
}

export async function listExpertCatalogIndex(): Promise<ExpertCatalogIndexEntry[]> {
  const res = await fetch(apiUrl('/api/experts/catalog/index'), { headers: apiHeaders() })
  return parseApiResponse<ExpertCatalogIndexEntry[]>(res)
}

export async function createExpert(
  id: string,
  displayName: string,
  systemPrompt: string,
  description?: string,
  skillIds?: string[],
): Promise<ExpertEntry> {
  const res = await fetch(apiUrl('/api/experts'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      id,
      displayName,
      systemPrompt,
      description: description ?? '',
      skillIds: skillIds ?? [],
    }),
  })
  return parseApiResponse<ExpertEntry>(res)
}

export async function updateExpert(
  id: string,
  displayName: string,
  systemPrompt: string,
  description?: string,
  skillIds?: string[],
): Promise<ExpertEntry> {
  const res = await fetch(apiUrl(`/api/experts/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({
      displayName,
      systemPrompt,
      description: description ?? '',
      skillIds: skillIds ?? [],
    }),
  })
  return parseApiResponse<ExpertEntry>(res)
}

export async function setExpertEnabled(id: string, enabled: boolean): Promise<ExpertEntry> {
  const res = await fetch(apiUrl(`/api/experts/${encodeURIComponent(id)}/enable`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  return parseApiResponse<ExpertEntry>(res)
}

export async function deleteExpert(id: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/experts/${encodeURIComponent(id)}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<void>(res, { allowEmptyData: true })
}
