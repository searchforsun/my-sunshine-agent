/** 统一本地墙钟展示：解析 ISO/Instant 后按浏览器时区格式化 */

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function parseDate(iso?: string | null): Date | null {
  if (!iso) return null
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? null : d
}

/** yyyy/MM/dd HH:mm:ss（Skill / Workflow / Prompt / 配置版本号） */
export function formatSkillVersionTime(iso?: string | null): string {
  const d = parseDate(iso)
  if (!d) return '—'
  return `${d.getFullYear()}/${pad2(d.getMonth() + 1)}/${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

/** yyyy-MM-dd HH:mm:ss（评测记录等） */
export function formatDateTimeLocal(iso?: string | null): string {
  const d = parseDate(iso)
  if (!d) return '—'
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`
}

/** 文档版本：后端 yyyyMMddHHmmss（按东八区墙钟）→ 前端本地展示 */
export function formatDocumentVersionKey(key?: string | null): string {
  if (!key || key.length !== 14 || !/^\d{14}$/.test(key)) return key?.trim() || '—'
  const iso = `${key.slice(0, 4)}-${key.slice(4, 6)}-${key.slice(6, 8)}T${key.slice(8, 10)}:${key.slice(10, 12)}:${key.slice(12, 14)}+08:00`
  return formatSkillVersionTime(iso)
}

/** 下载文件名用：yyyyMMdd_HHmmss */
export function formatSkillVersionTimeForFilename(iso?: string | null): string {
  const d = parseDate(iso)
  if (!d) return 'unknown'
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}_${pad2(d.getHours())}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`
}
