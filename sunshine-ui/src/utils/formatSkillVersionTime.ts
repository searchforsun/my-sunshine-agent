/** Skill 版本时间展示：yyyy/MM/dd HH:mm:ss */
export function formatSkillVersionTime(iso?: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 文档版本：后端 yyyyMMddHHmmss → 前端 yyyy/MM/dd HH:mm:ss */
export function formatDocumentVersionKey(key?: string | null): string {
  if (!key || key.length !== 14 || !/^\d{14}$/.test(key)) return key?.trim() || '—'
  const iso = `${key.slice(0, 4)}-${key.slice(4, 6)}-${key.slice(6, 8)}T${key.slice(8, 10)}:${key.slice(10, 12)}:${key.slice(12, 14)}+08:00`
  return formatSkillVersionTime(iso)
}

/** 下载文件名用：yyyyMMdd_HHmmss */
export function formatSkillVersionTimeForFilename(iso?: string | null): string {
  if (!iso) return 'unknown'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return 'unknown'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
}
