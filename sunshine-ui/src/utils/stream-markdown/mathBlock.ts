/**
 * 块级公式 $$...$$ 流式安全检测
 */

/** 文本中是否存在未闭合的 $$ 块级公式 */
export function hasUnclosedBlockMath(text: string): boolean {
  let i = 0
  let inBlock = false
  while (i < text.length) {
    if (text[i] === '$' && text[i + 1] === '$') {
      inBlock = !inBlock
      i += 2
      continue
    }
    i++
  }
  return inBlock
}

/** 去掉末尾未闭合的 $$...$$ 块 */
export function trimIncompleteBlockMath(text: string): string {
  let i = 0
  let inBlock = false
  let blockStart = -1

  while (i < text.length) {
    if (text[i] === '$' && text[i + 1] === '$') {
      if (inBlock) {
        inBlock = false
        i += 2
      } else {
        inBlock = true
        blockStart = i
        i += 2
      }
      continue
    }
    i++
  }

  if (inBlock && blockStart >= 0) return text.slice(0, blockStart)
  return text
}

/**
 * SSE 分块可能在 `$$\int_a^b f$$` 处误闭合，后续 `(x)dx$$` 被当作正文。
 * 若 chunk 是 fullText 的前缀且后面还有同行内容，则隐藏 chunk 中的块级公式。
 */
export function trimPrematureBlockMathClose(chunk: string, fullText?: string): string {
  if (!fullText || !chunk || chunk.length >= fullText.length) {
    return chunk
  }
  const idx = fullText.lastIndexOf(chunk)
  if (idx < 0) {
    return chunk
  }
  const after = fullText.slice(idx + chunk.length)
  if (after.length > 0 && !after.startsWith('\n')) {
    const blockStart = chunk.indexOf('$$')
    if (blockStart >= 0) return chunk.slice(0, blockStart)
  }
  return chunk
}

/** 流式 pending 是否为块级公式续行（如 `(x)dx$$`） */
export function isBlockMathContinuation(pending: string): boolean {
  const t = pending.trimStart()
  if (!t || t.startsWith('$$')) return false
  if (/^\([^)]*\)[a-zA-Z\\^_{}\s]*\$\$?$/.test(t)) return true
  if (/^[a-zA-Z(\\^_{}\s]+\$\$?$/.test(t) && !/^#{1,6}\s/.test(t)) return true
  return false
}

/**
 * 检测 `$$\int_a^b f$$(x)dx` 这类中间误闭合：closing $$ 后仍有同行正文。
 * 此时隐藏从首个 $$ 开始的块级公式。
 */
export function trimFalseClosedBlockMath(text: string): string {
  let i = 0
  while (i < text.length) {
    if (text[i] === '$' && text[i + 1] === '$') {
      const blockStart = i
      i += 2
      while (i < text.length) {
        if (text[i] === '$' && text[i + 1] === '$') {
          const after = text.slice(i + 2)
          if (after.length > 0 && !after.startsWith('\n')) {
            return text.slice(0, blockStart)
          }
          return text
        }
        i++
      }
      return text.slice(0, blockStart)
    }
    i++
  }
  return text
}

export function prepareStreamingMarkdown(text: string, fullText?: string): string {
  let prepared = trimIncompleteBlockMath(text)
  prepared = trimFalseClosedBlockMath(prepared)
  if (fullText) {
    prepared = trimPrematureBlockMathClose(prepared, fullText)
  }
  return prepared
}
