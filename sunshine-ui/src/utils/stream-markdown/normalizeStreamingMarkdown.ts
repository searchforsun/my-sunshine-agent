/**
 * 流式 Markdown 归一化 — 仅依据 Markdown 结构特征补换行/围栏，不解析代码语法
 *
 * 修复范围（输出内容自带的结构边界，非 if/def/public 等语法推断）：
 * - 「cpp#include<iostream>…」→ 补 ``` 围栏
 * - 「javapublicclassQuickSort」（语言名与代码粘连）
 * - 「```javapublicclass」（围栏行语言与代码同行）
 * - 「```python」被误拆为 ```py + thon（短语言名前缀误匹配）
 * - 行内出现的 ``` / # 标题前补换行（块级 Markdown 标记）
 *
 * 代码块内部的换行以模型/SSE 原文中的 \n 为准，不做语法 reflow。
 */

/** 长语言名优先匹配，避免 py→python、js→javascript 误拆 */
const LANGS_ORDERED = [
  'javascript', 'typescript', 'csharp', 'python', 'shell',
  'cpp', 'c++', 'java', 'rust', 'bash', 'json', 'yaml', 'html',
  'css', 'sql', 'xml', 'go', 'c', 'py', 'js', 'ts', 'sh', 'yml', 'cs',
]

const CODE_KW =
  'public|private|protected|class|interface|enum|import|package|#include|def|function|const|let|var|using|namespace|int|void|SELECT|<\\?|<!'

/** 语言别名 → highlight.js / markdown-it 语言 id */
const LANG_ALIAS: Record<string, string> = {
  py: 'python',
  js: 'javascript',
  ts: 'typescript',
  sh: 'bash',
  shell: 'bash',
  yml: 'yaml',
  cs: 'csharp',
  'c++': 'cpp',
  c: 'c',
}

function normalizeLang(raw: string): string {
  const l = raw.toLowerCase()
  return LANG_ALIAS[l] ?? l
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function langsByLengthDesc(): string[] {
  return [...LANGS_ORDERED].sort((a, b) => b.length - a.length)
}

function langPattern(): string {
  return langsByLengthDesc().map(l => l.replace(/\+/g, '\\+')).join('|')
}

/** 语言名后的剩余文本是否像代码（而非 python→thon 这类语言名续段） */
function looksLikeCodeStart(remainder: string): boolean {
  if (!remainder) return false
  return new RegExp(`^(${CODE_KW})`, 'i').test(remainder) || /^[#<(A-Z]/.test(remainder)
}

/**
 * 从围栏行解析语言名（处理 ```javapublicclass 粘连）
 * 必须最长前缀 + 剩余段像代码，避免 python → py + thon
 */
export function parseGluedFenceLang(raw: string): { lang: string; remainder: string } {
  const t = raw.trim()
  if (!t) return { lang: '', remainder: '' }
  const lower = t.toLowerCase()

  for (const lang of langsByLengthDesc()) {
    const l = lang.toLowerCase()
    if (lower === l) {
      return { lang: normalizeLang(lang), remainder: '' }
    }
    if (lower.startsWith(l) && lower.length > l.length) {
      const remainder = t.slice(l.length)
      if (looksLikeCodeStart(remainder)) {
        return { lang: normalizeLang(lang), remainder }
      }
    }
  }
  return { lang: normalizeLang(t), remainder: '' }
}

/**
 * 语言名 + 代码粘连（无围栏）→ 补 Markdown 围栏行
 * 例：javapublicclass → ```java\npublicclass
 */
function fixLangGluedToCode(text: string): string {
  const langs = langPattern()
  const re = new RegExp(
    `(^|[\\s。．.!?；;:，,\\n])(${langs})(?=${CODE_KW})`,
    'gi',
  )
  return text.replace(re, (_m, punct: string, lang: string) => {
    return `${punct}\n\n\`\`\`${normalizeLang(lang)}\n`
  })
}

/**
 * 围栏行语言与代码同行：```javapublicclass → ```java + 换行 + publicclass
 * 仅当围栏后紧跟代码关键字，避免 ```python 被拆成 ```py + thon
 */
function fixFenceLineGlued(text: string): string {
  let s = text
  for (const lang of langsByLengthDesc()) {
    const escaped = escapeRe(lang)
    const norm = normalizeLang(lang)
    s = s.replace(
      new RegExp(`\`\`\`${escaped}(?=(${CODE_KW}))`, 'gi'),
      `\`\`\`${norm}\n`,
    )
  }
  return s
}

/** 代码块内重复语言前缀：```java\njavapublic → ```java\npublic */
function stripRedundantLangPrefix(text: string): string {
  let s = text
  for (const lang of langsByLengthDesc()) {
    const norm = normalizeLang(lang)
    s = s.replace(
      new RegExp(`(\`\`\`${escapeRe(norm)}\\s*\\n)${escapeRe(lang)}(?=${CODE_KW})`, 'gi'),
      '$1',
    )
  }
  return s
}

/** 行内出现的块级 Markdown 标记前补换行 */
function insertBlockNewlines(text: string): string {
  let s = text
  s = s.replace(/([^\n])\s*(```)/g, '$1\n\n$2')
  s = s.replace(/([^\n#])(#{1,6})([^\n#\s])/g, '$1\n\n$2 $3')
  s = s.replace(/([^\n#])(#{1,6})\s+/g, '$1\n\n$2 ')
  return s
}

/** 未闭合的 ``` 在流结束时补全 */
function closeDanglingFence(text: string): string {
  const count = (text.match(/```/g) || []).length
  if (count % 2 === 1) return `${text}\n\`\`\``
  return text
}

/** 流式 / 静态渲染前统一归一化 */
export function normalizeStreamingMarkdown(text: string): string {
  if (!text) return text
  let s = text
  s = fixFenceLineGlued(s)
  s = fixLangGluedToCode(s)
  s = stripRedundantLangPrefix(s)
  s = insertBlockNewlines(s)
  s = closeDanglingFence(s)
  return s
}
