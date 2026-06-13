/**
 * 平滑 Markdown 渲染 — 实时流式输出，隐藏未闭合的标记字符
 *
 * 策略：
 * 1. 扫描文本，用栈追踪内联标记的开闭（** * ` ~~ [）
 * 2. 末尾未闭合的标记之前的内容 = "安全部分" → 交给 markdown-it
 * 3. 未闭合标记及之后的内容 = "尾部" → HTML 转义原样追加
 * 4. 下一帧新数据到达时重新计算，更新 DOM（配合 replacePrev）
 */

const ESC_MAP: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;' }
function escape(s: string): string {
  return s.replace(/[&<>]/g, c => ESC_MAP[c] || c)
}

interface Marker {
  marker: string  // '**' | '*' | '`' | '~~' | '['
  pos: number
}

/** 找到"安全渲染"的截止位置——在此之前的标记全都正确闭合 */
export function findSafeCutPosition(text: string): number {
  const stack: Marker[] = []
  let i = 0

  while (i < text.length) {
    // 反斜杠转义
    if (text[i] === '\\') { i += 2; continue }

    // HTML 标签跳过
    if (text[i] === '<') {
      const end = text.indexOf('>', i)
      if (end >= 0) { i = end + 1; continue }
    }

    // ** 粗体
    if (text[i] === '*' && text[i + 1] === '*') {
      closeOrOpen(stack, '**', i)
      i += 2; continue
    }

    // * 斜体（单星号，后面不能紧跟 *）
    if (text[i] === '*' && text[i + 1] !== '*') {
      closeOrOpen(stack, '*', i)
      i += 1; continue
    }

    // ` 行内代码（行首 ``` / ~~~ 围栏不参与内联栈）
    if (text[i] === '`') {
      const lineStart = text.lastIndexOf('\n', i - 1) + 1
      const linePrefix = text.slice(lineStart, i)
      if (/^\s*$/.test(linePrefix)) {
        const fenceMatch = text.slice(i).match(/^(`{3,}|~{3,})/)
        if (fenceMatch) {
          i += fenceMatch[0].length
          continue
        }
      }
      closeOrOpen(stack, '`', i)
      i += 1; continue
    }

    // ~~ 删除线
    if (text[i] === '~' && text[i + 1] === '~') {
      closeOrOpen(stack, '~~', i)
      i += 2; continue
    }

    // 任务列表 checkbox [ ] / [x] — 非链接，整段跳过
    if (text[i] === '$') {
      if (text[i + 1] === '$') {
        if (closeOrOpenMathBlock(stack, i)) { i += 2; continue }
        stack.push({ marker: '$$', pos: i })
        i += 2
        continue
      }
      if (closeOrOpenMathInline(stack, i)) { i += 1; continue }
      stack.push({ marker: '$', pos: i })
      i += 1
      continue
    }

    if (text[i] === '[') {
      const taskMatch = text.slice(i).match(/^\[([ xX])\]/)
      if (taskMatch) {
        i += taskMatch[0].length
        continue
      }
      stack.push({ marker: '[', pos: i })
      i += 1; continue
    }
    if (text[i] === ']') {
      const after = text.slice(i + 1)
      const m = after.match(/^\([^)]*\)/)
      if (m) {
        // 闭合 [
        closeLast(stack, '[')
        i += 1 + m[0].length; continue
      }
      i += 1; continue
    }

    i++
  }

  if (stack.length === 0) return text.length
  // 最早未闭合标记之前都是安全的
  return Math.min(...stack.map(s => s.pos))
}

function closeOrOpenMathBlock(stack: Marker[], pos: number): boolean {
  const idx = lastIndexOf(stack, '$$')
  if (idx >= 0) {
    stack.splice(idx, 1)
    return true
  }
  return false
}

function closeOrOpenMathInline(stack: Marker[], pos: number): boolean {
  if (lastIndexOf(stack, '$$') >= 0) return false
  const idx = lastIndexOf(stack, '$')
  if (idx >= 0) {
    stack.splice(idx, 1)
    return true
  }
  return false
}

function closeOrOpen(stack: Marker[], marker: string, pos: number): void {
  const idx = lastIndexOf(stack, marker)
  if (idx >= 0) stack.splice(idx, 1)  // 闭合
  else stack.push({ marker, pos })     // 开启
}

function closeLast(stack: Marker[], marker: string): void {
  const idx = lastIndexOf(stack, marker)
  if (idx >= 0) stack.splice(idx, 1)
}

function lastIndexOf(stack: Marker[], marker: string): number {
  for (let i = stack.length - 1; i >= 0; i--) {
    if (stack[i].marker === marker) return i
  }
  return -1
}

function injectTailInline(html: string, tail: string): string {
  if (!tail) return html
  const span = `<span class="smd-h-fade-tail">${escape(tail)}</span>`
  if (!html) return span
  // 插入最后一个块级闭合标签前，避免 tail 换行到下一行
  const hosts = ['</p>', '</li>', '</h1>', '</h2>', '</h3>', '</h4>', '</h5>', '</h6>']
  for (const tag of hosts) {
    const idx = html.lastIndexOf(tag)
    if (idx >= 0) return html.slice(0, idx) + span + html.slice(idx)
  }
  return html + span
}

/** 平滑渲染：安全部分 → markdown-it，未闭合尾部 inline 渐隐 */
export function smoothRender(
  text: string,
  renderMd: (md: string) => string,
): string {
  if (!text) return ''

  const cut = findSafeCutPosition(text)
  const safe = text.slice(0, cut)
  const tail = text.slice(cut)

  const html = safe ? renderMd(safe) : ''
  return injectTailInline(html, tail)
}
