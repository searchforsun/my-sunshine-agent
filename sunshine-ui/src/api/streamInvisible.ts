/**
 * 流式 chunk 中的不可见 / 控制字符识别与可视化
 * 用于 SSE 调试及保留 \n \t \r 等隐藏符号
 */

export interface InvisibleToken {
  /** 人类可读名称 */
  name: string
  /** Unicode 码点 */
  code: number
  /** 原始字符 */
  char: string
  /** 在 chunk 中的出现次数 */
  count: number
}

/** 常见隐藏字符 → 名称（DevTools 空白行对照用） */
const NAMED: Record<number, string> = {
  0x09: 'TAB',
  0x0a: 'LF',
  0x0b: 'VT',
  0x0c: 'FF',
  0x0d: 'CR',
  0x20: 'SPACE',
  0xa0: 'NBSP',
  0x200b: 'ZWSP',
  0x200c: 'ZWNJ',
  0x200d: 'ZWJ',
  0xfeff: 'BOM',
}

/** DevTools 空白行可视化符号 */
const GLYPH: Record<number, string> = {
  0x09: '⇥',
  0x0a: '␊',
  0x0d: '␍',
  0x20: '␠',
  0xa0: '·',
  0x200b: '⦀',
}

export function invisibleCharName(code: number): string {
  if (NAMED[code]) return NAMED[code]
  if (code <= 0x1f || code === 0x7f) return `CTRL_0x${code.toString(16).toUpperCase().padStart(2, '0')}`
  if (/\s/u.test(String.fromCharCode(code))) return `WS_U+${code.toString(16).toUpperCase().padStart(4, '0')}`
  return `U+${code.toString(16).toUpperCase().padStart(4, '0')}`
}

/** 是否为不可见 / 空白类字符（不含普通可打印字符） */
export function isInvisibleCode(code: number): boolean {
  if (code === 0x20) return true
  if (code <= 0x1f || code === 0x7f) return true
  if (code === 0xa0 || code === 0x200b || code === 0x200c || code === 0x200d || code === 0xfeff) return true
  return /\s/u.test(String.fromCharCode(code))
}

/** 扫描 chunk，汇总各隐藏字符出现次数 */
export function scanInvisible(text: string): InvisibleToken[] {
  if (!text) return []
  const counts = new Map<number, number>()
  for (const ch of text) {
    const code = ch.codePointAt(0)!
    if (!isInvisibleCode(code)) continue
    counts.set(code, (counts.get(code) ?? 0) + 1)
  }
  return [...counts.entries()]
    .sort(([a], [b]) => a - b)
    .map(([code, count]) => ({
      name: invisibleCharName(code),
      code,
      char: String.fromCodePoint(code),
      count,
    }))
}

/** 是否仅含隐藏/空白字符（DevTools 里显示为「空白行」的 chunk） */
export function isInvisibleOnly(text: string): boolean {
  if (!text) return false
  for (const ch of text) {
    if (!isInvisibleCode(ch.codePointAt(0)!)) return false
  }
  return true
}

/** 将 chunk 转为可读的「控制符可视化」字符串，如 "{\n" → "{␊" */
export function visualizeChunk(text: string): string {
  let out = ''
  for (const ch of text) {
    const code = ch.codePointAt(0)!
    if (GLYPH[code]) {
      out += GLYPH[code]
    } else if (isInvisibleCode(code)) {
      out += `[${invisibleCharName(code)}]`
    } else {
      out += ch
    }
  }
  return out
}

/** 统一换行符，保留 TAB / NBSP 等其余隐藏字符 */
export function normalizeStreamChunk(text: string): string {
  return text.replace(/\r\n?/g, '\n')
}

/** 调试摘要：JSON 安全 + 可视化 + 码点列表 */
export function describeChunk(text: string): string {
  const vis = visualizeChunk(text)
  const codes = [...text].map(ch => ch.codePointAt(0)!)
  const inv = scanInvisible(text)
  const invPart = inv.length
    ? inv.map(t => `${t.name}×${t.count}`).join(', ')
    : '(none)'
  return `"${vis}" len=${text.length} codes=[${codes.join(',')}] invisible={${invPart}}`
}

const DEBUG_KEY = 'sunshine:sse-debug'

export function setStreamDebugEnabled(on: boolean): void {
  if (typeof localStorage === 'undefined') return
  if (on) localStorage.setItem(DEBUG_KEY, '1')
  else localStorage.removeItem(DEBUG_KEY)
}

export function isStreamDebugEnabled(): boolean {
  if (!import.meta.env?.DEV) return false
  if (typeof localStorage === 'undefined') return false
  return localStorage.getItem(DEBUG_KEY) === '1'
}

export function logStreamChunk(label: string, text: string, extra?: Record<string, unknown>): void {
  if (!isStreamDebugEnabled()) return
  console.debug(`[SSE chunk] ${label}`, describeChunk(text), extra ?? '')
}

/** 开发时在控制台可用：__sunshineStreamDebug.describe('{\\n') */
if (import.meta.env?.DEV && typeof window !== 'undefined') {
  ;(window as unknown as { __sunshineStreamDebug?: object }).__sunshineStreamDebug = {
    describe: describeChunk,
    visualize: visualizeChunk,
    scan: scanInvisible,
    isInvisibleOnly,
    enable: () => setStreamDebugEnabled(true),
    disable: () => setStreamDebugEnabled(false),
  }
}
