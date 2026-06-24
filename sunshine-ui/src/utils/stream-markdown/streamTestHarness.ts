import hljs from 'highlight.js/lib/core'
import { createMarkdownIt } from '../markdown/createMarkdownIt'
import { StreamMarkdownRenderer } from './StreamMarkdownRenderer'

const md = createMarkdownIt(hljs)

export interface StreamMarkdownStats {
  listBlocks: number
  liCount: number
  tables: number
  blockCount: number
}

/** 供 E2E 在浏览器内验证流式 DOM 结构 */
export function runStreamMarkdownTest(content: string): StreamMarkdownStats {
  const container = document.createElement('div')
  const renderer = new StreamMarkdownRenderer(container, {
    debounceMs: 0,
    renderMarkdown: (t) => md.render(t),
  })
  renderer.syncFromContent(content)
  renderer.finish()

  const blocks = container.querySelectorAll('.smd-markdown-block')
  let listBlocks = 0
  blocks.forEach((b) => {
    if (b.querySelector('ul, ol')) listBlocks++
  })

  return {
    listBlocks,
    liCount: container.querySelectorAll('li').length,
    tables: container.querySelectorAll('table').length,
    blockCount: blocks.length,
  }
}

/** 逐字增量同步，统计过程中列表块峰值 */
export function runStreamMarkdownIncrementalTest(content: string): {
  maxListBlocks: number
  firstDupAt: number
} {
  const container = document.createElement('div')
  const renderer = new StreamMarkdownRenderer(container, {
    debounceMs: 0,
    renderMarkdown: (t) => md.render(t),
  })
  let maxListBlocks = 0
  let firstDupAt = -1
  for (let i = 1; i <= content.length; i++) {
    renderer.syncFromContent(content.slice(0, i))
    const blocks = container.querySelectorAll('.smd-markdown-block')
    let listBlocks = 0
    blocks.forEach((b) => {
      if (b.querySelector('ul, ol')) listBlocks++
    })
    if (listBlocks > 1 && firstDupAt < 0) firstDupAt = i
    maxListBlocks = Math.max(maxListBlocks, listBlocks)
  }
  return { maxListBlocks, firstDupAt }
}

function hasMathTailLeak(container: HTMLElement): boolean {
  for (const tail of container.querySelectorAll('.smd-h-fade-tail')) {
    if (tail.textContent?.includes('(x)dx')) return true
  }
  for (const block of container.querySelectorAll('.smd-markdown-block')) {
    if (block.querySelector('.katex-display')) continue
    if ((block.textContent ?? '').includes('(x)dx')) return true
  }
  return false
}

/** 标题后接表格流式输出时，已落盘标题不应保留 smd-h-fade-tail */
export function runStreamHeadingBeforeTableFadeTest(): boolean {
  const container = document.createElement('div')
  const renderer = new StreamMarkdownRenderer(container, {
    debounceMs: 0,
    renderMarkdown: (t) => md.render(t),
  })
  const head = '### 小节标题\n\n'
  const table = '| a | b |\n| --- | --- |\n| 1 | 2 |\n\n'
  renderer.syncFromContent(head + table)
  renderer.syncFromContent(head + table + '后续正文仍在输出…')
  const heading = container.querySelector('h3')
  if (!heading) return false
  const block = heading.closest('.smd-markdown-block')
  if (!block) return false
  return block.querySelectorAll('.smd-h-fade-tail').length === 0
}

/** 逐字增量同步，检测块级公式是否分裂 */
export function runStreamMathIncrementalTest(content: string): {
  leaked: boolean
  leakAt: number
  katexBlocks: number
} {
  const container = document.createElement('div')
  const renderer = new StreamMarkdownRenderer(container, {
    debounceMs: 0,
    renderMarkdown: (t) => md.render(t),
  })
  let leaked = false
  let leakAt = -1
  for (let i = 1; i <= content.length; i++) {
    renderer.syncFromContent(content.slice(0, i))
    if (hasMathTailLeak(container)) {
      leaked = true
      leakAt = i
      break
    }
  }
  renderer.finish()
  return {
    leaked,
    leakAt,
    katexBlocks: container.querySelectorAll('.katex-display').length,
  }
}
