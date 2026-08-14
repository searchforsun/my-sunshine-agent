/**
 * 网页搜索结果（sandbox__websearch）链接化：
 * - 结果块「序号. 标题 / URL / 摘要」→ 标题整体变为超链接（点击跳转该条 URL），
 *   原始 URL 行不再展示；
 * - 块内其余裸 URL 也转成可点击 <a>，非 URL 文本 HTML 转义后原样展示，
 *   避免 markdown 解析误伤标题里的 _ / * 等字符。
 */

const HTML_ESCAPE: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, ch => HTML_ESCAPE[ch] ?? ch)
}

const URL_CANDIDATE = /https?:\/\/[^\s<>"']+/g
/** 剥掉 URL 尾部闭合标点（含全角），保留路径扩展名与 query 内的 ? / ! */
const TRAILING_PUNCT = /[.,;:!?)\]}\uFF01\uFF1F\uFF09\uFF0C\u3001\u3002\uFF1B\uFF1A]+$/
/** 结果标题行：序号. 标题 */
const HEADING_LINE = /^(\d+\.\s+)(.*)$/

const LINK_ATTRS = ' target="_blank" rel="noopener noreferrer"'

function linkifyUrls(text: string): string {
  return text.replace(URL_CANDIDATE, match => {
    const stripped = match.replace(TRAILING_PUNCT, '')
    if (!stripped) return match
    const trailing = match.slice(stripped.length)
    return `<a href="${stripped}"${LINK_ATTRS}>${stripped}</a>${trailing}`
  })
}

/** 行内第一个裸 URL（剥尾标点后）；未命中返回 undefined */
function firstUrlIn(lines: string[], from = 0): string | undefined {
  for (let i = from; i < lines.length; i++) {
    const hit = lines[i].match(URL_CANDIDATE)
    if (!hit) continue
    const url = hit[0].replace(TRAILING_PUNCT, '')
    if (url) return url
  }
  return undefined
}

/** 该行 trim 后仅为一个 URL（±尾部闭合标点）：即后端下发的原始 URL 行 */
function isStandaloneUrlLine(line: string): boolean {
  const t = line.trim()
  if (!t) return false
  const hit = t.match(URL_CANDIDATE)
  if (!hit) return false
  return t.replace(TRAILING_PUNCT, '') === hit[0].replace(TRAILING_PUNCT, '')
}

interface SearchBlock {
  heading: RegExpExecArray
  lines: string[]
  start: number
}

export function linkifyWebSearchText(text: string): string {
  if (!text) return ''
  const escaped = escapeHtml(text)
  const lines = escaped.split('\n')

  // 按「序号. 标题」行切分结果条目，不依赖空行（单条结果无 \n\n 分隔）
  const blocks: SearchBlock[] = []
  let current: SearchBlock | null = null
  for (let i = 0; i < lines.length; i++) {
    const heading = HEADING_LINE.exec(lines[i])
    if (heading) {
      current = { heading, lines: [lines[i]], start: i }
      blocks.push(current)
    } else if (current) {
      current.lines.push(lines[i])
    }
  }
  if (!blocks.length) return linkifyUrls(escaped)

  const preLines = lines.slice(0, blocks[0].start)
  const body = blocks.map(({ heading, lines: blockLines }) => {
    const url = firstUrlIn(blockLines, 1) ?? firstUrlIn(blockLines, 0)
    const out = [...blockLines]
    if (url) {
      // 标题整体变链接（HTML 不允许嵌套 a，标题行不再二次链接化）
      out[0] = `<a class="smd-search-title" href="${url}"${LINK_ATTRS}>${heading[1]}${heading[2]}</a>`
    }
    // 标题已可点击，原始 URL 行不再展示
    const urlLineIdx = blockLines.findIndex((line, i) => i > 0 && isStandaloneUrlLine(line))
    if (urlLineIdx >= 0) out.splice(urlLineIdx, 1)
    return out
      .map((line, i) => {
        if (!line) return ''
        if (i === 0 && url) return line
        return linkifyUrls(line)
      })
      .join('\n')
  })

  const pre = preLines.map(linkifyUrls).filter(Boolean).join('\n')
  return [pre, ...body].filter(Boolean).join('\n')
}
