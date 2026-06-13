/**
 * SSE 事件解析 — 按规范还原 data 行中的隐藏字符（含独立 \\n / \\t / 空格 chunk）
 */
import { logStreamChunk, normalizeStreamChunk } from './streamInvisible'

export interface ParsedSseEvent {
  id: string | null
  /** null = 无有效 payload（meta 边界 / [DONE] / 真·空 chunk） */
  payload: string | null
}

/** 解析单行 data:（WHATWG：data: 后可选一个空格，其余原样保留） */
export function parseSseDataLine(line: string): string | null {
  if (!line.startsWith('data:')) return null
  let payload = line.slice(5)
  if (payload.startsWith(' ')) payload = payload.slice(1)
  return payload
}

/** 多行 data: 用 \\n 拼接（独立换行 chunk 通常为两行空 data: → "\\n"） */
export function joinSseDataLines(lines: string[]): string {
  return lines.join('\n')
}

export function parseSseEvent(rawEvent: string): ParsedSseEvent {
  let id: string | null = null
  const dataLines: string[] = []

  for (const line of rawEvent.split('\n')) {
    if (line.startsWith('id:')) {
      id = line.startsWith('id: ') ? line.slice(4) : line.slice(3)
      continue
    }
    const dataLine = parseSseDataLine(line)
    if (dataLine !== null) dataLines.push(dataLine)
  }

  if (dataLines.length === 0) return { id, payload: null }

  const joined = joinSseDataLines(dataLines)
  if (joined === '[DONE]') return { id, payload: null }
  // 仅跳过真·空字符串；\n \t 空格等 length≥1 的隐藏 chunk 必须保留
  if (joined.length === 0) return { id, payload: null }

  const payload = normalizeStreamChunk(joined)
  logStreamChunk(id ?? 'event', payload, { dataLineCount: dataLines.length })
  return { id, payload }
}
