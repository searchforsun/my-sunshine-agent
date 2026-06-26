import { apiHeaders, authHeaders } from '../stores/authStore'
import { BFF_STREAM_BASE } from './config'
import { apiHttpError, parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${import.meta.env.VITE_BFF_API_BASE ?? ''}${path}`
}

/** multipart 上传直连 Gateway，避免 Vite proxy 破坏 FormData */
function uploadUrl(path: string): string {
  return `${BFF_STREAM_BASE}${path}`
}

export interface SkillEntry {
  id: string
  displayName: string
  description: string
  systemOverlay: string
  version: number
  enabled: boolean
  activeVersionCreatedAt?: string | null
  activeVersionMaintainer?: string | null
  activeVersionMaintainerName?: string | null
  /** 当前 active 版本是否已发布 — 未发布草稿不可开启 Skill */
  activeVersionPublished?: boolean
}

export interface SkillVersion {
  id: number
  skillId: string
  version: number
  systemOverlay: string
  toolsJson: string
  maxIters: number
  sideEffect: string
  sandbox: string
  referencesJson: string
  scriptsJson: string
  storagePath: string | null
  status: string
  maintainer?: string | null
  maintainerName?: string | null
  createdAt?: string | null
}

export interface SkillFileEntry {
  path: string
  size: number
  directory: boolean
}

export interface SkillFileContent {
  path: string
  contentType: string
  content: string
  binary: boolean
}

export async function listSkills(): Promise<SkillEntry[]> {
  const res = await fetch(apiUrl('/api/skills'), { headers: apiHeaders() })
  return parseApiResponse<SkillEntry[]>(res)
}

export async function createSkill(id: string, displayName: string, description?: string): Promise<SkillEntry> {
  const res = await fetch(apiUrl('/api/skills'), {
    method: 'POST',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, displayName, description: description ?? '' }),
  })
  return parseApiResponse<SkillEntry>(res)
}

export async function setSkillEnabled(id: string, enabled: boolean): Promise<SkillEntry> {
  const res = await fetch(apiUrl(`/api/skills/${encodeURIComponent(id)}/enable`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled }),
  })
  return parseApiResponse<SkillEntry>(res)
}

export async function updateSkill(
  id: string,
  displayName: string,
  description?: string,
): Promise<SkillEntry> {
  const res = await fetch(apiUrl(`/api/skills/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ displayName, description: description ?? '' }),
  })
  return parseApiResponse<SkillEntry>(res)
}

export async function uploadSkillPackage(
  id: string,
  file: Blob,
  filename: string,
): Promise<SkillEntry> {
  const form = new FormData()
  form.append('file', file, filename)
  const res = await fetch(uploadUrl(`/api/skills/${encodeURIComponent(id)}/upload`), {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  })
  return parseApiResponse<SkillEntry>(res)
}

export async function listSkillVersions(id: string): Promise<SkillVersion[]> {
  const res = await fetch(apiUrl(`/api/skills/${encodeURIComponent(id)}/versions`), {
    headers: apiHeaders(),
  })
  return parseApiResponse<SkillVersion[]>(res)
}

export async function publishSkillVersion(id: string, version: number): Promise<SkillEntry> {
  const res = await fetch(
    apiUrl(`/api/skills/${encodeURIComponent(id)}/publish?version=${version}`),
    { method: 'POST', headers: apiHeaders() },
  )
  return parseApiResponse<SkillEntry>(res)
}

/** 基于指定版本复制为新草稿，便于在线编辑 */
export async function forkSkillVersion(id: string, version: number): Promise<SkillEntry> {
  const res = await fetch(
    apiUrl(`/api/skills/${encodeURIComponent(id)}/versions/${version}/fork`),
    { method: 'POST', headers: apiHeaders() },
  )
  return parseApiResponse<SkillEntry>(res)
}

export async function deleteSkill(id: string): Promise<void> {
  const res = await fetch(apiUrl(`/api/skills/${encodeURIComponent(id)}`), {
    method: 'DELETE',
    headers: apiHeaders(),
  })
  await parseApiResponse<null>(res, { allowEmptyData: true })
}

export async function deleteSkillVersion(id: string, version: number): Promise<SkillEntry> {
  const res = await fetch(
    apiUrl(`/api/skills/${encodeURIComponent(id)}/versions/${version}`),
    { method: 'DELETE', headers: apiHeaders() },
  )
  return parseApiResponse<SkillEntry>(res)
}

export async function listSkillFiles(id: string, version: number): Promise<SkillFileEntry[]> {
  const res = await fetch(
    apiUrl(`/api/skills/${encodeURIComponent(id)}/versions/${version}/files`),
    { headers: apiHeaders() },
  )
  return parseApiResponse<SkillFileEntry[]>(res)
}

export async function readSkillFile(
  id: string,
  version: number,
  path: string,
): Promise<SkillFileContent> {
  const q = new URLSearchParams({ path })
  const res = await fetch(
    apiUrl(`/api/skills/${encodeURIComponent(id)}/versions/${version}/file?${q}`),
    { headers: apiHeaders() },
  )
  return parseApiResponse<SkillFileContent>(res)
}

export async function writeSkillFile(
  id: string,
  version: number,
  path: string,
  content: string,
): Promise<SkillFileContent> {
  const q = new URLSearchParams({ path })
  const res = await fetch(
    apiUrl(`/api/skills/${encodeURIComponent(id)}/versions/${version}/file?${q}`),
    {
      method: 'PUT',
      headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    },
  )
  return parseApiResponse<SkillFileContent>(res)
}

/** 关闭标签页时尽力提交未保存编辑（fetch keepalive） */
export function writeSkillFileKeepalive(
  id: string,
  version: number,
  path: string,
  content: string,
): void {
  const q = new URLSearchParams({ path })
  const url = apiUrl(`/api/skills/${encodeURIComponent(id)}/versions/${version}/file?${q}`)
  fetch(url, {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
    keepalive: true,
  }).catch(() => {
    /* 页面卸载阶段无法可靠反馈错误 */
  })
}

export interface SkillDiffLine {
  type: 'unchanged' | 'added' | 'removed'
  text: string
  oldLineNo: number | null
  newLineNo: number | null
}

export interface SkillVersionDiffResponse {
  path: string
  fromVersion: number
  toVersion: number
  binary?: boolean
  fromMd5?: string | null
  toMd5?: string | null
  lines: SkillDiffLine[]
}

export async function diffSkillVersions(
  id: string,
  from: number,
  to: number,
  path = 'SKILL.md',
): Promise<SkillVersionDiffResponse> {
  const q = new URLSearchParams({ from: String(from), to: String(to), path })
  const res = await fetch(
    apiUrl(`/api/skills/${encodeURIComponent(id)}/versions/diff?${q}`),
    { headers: apiHeaders() },
  )
  return parseApiResponse<SkillVersionDiffResponse>(res)
}

/** 下载指定版本 Skill 包（zip），直连 Gateway 避免 proxy 破坏二进制 */
export async function downloadSkillPackage(id: string, version: number): Promise<Blob> {
  const res = await fetch(
    uploadUrl(`/api/skills/${encodeURIComponent(id)}/versions/${version}/download`),
    { headers: authHeaders() },
  )
  if (!res.ok) {
    throw apiHttpError(res.status)
  }
  return res.blob()
}

/** 将 webkitdirectory 选中的文件打包为 zip（保留相对路径） */
export { zipFolderFiles } from '../utils/zipStore'

/** Chat @ 补全 — 仅摘要，不含 overlay */
export interface SkillCatalogIndexEntry {
  id: string
  displayName: string
  description: string
  version: number
  enabled: boolean
}

export async function listSkillCatalogIndex(): Promise<SkillCatalogIndexEntry[]> {
  const res = await fetch(apiUrl('/api/skills/catalog/index'), { headers: apiHeaders() })
  return parseApiResponse<SkillCatalogIndexEntry[]>(res)
}
