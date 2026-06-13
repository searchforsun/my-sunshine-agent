import { test, expect } from '@playwright/test'
import hljs from 'highlight.js/lib/core'
import { createMarkdownIt } from '../src/utils/markdown/createMarkdownIt'
import {
  prepareStreamingMarkdown,
  streamSafeMarkdownRender,
} from '../src/utils/stream-markdown/streamSafeMarkdown'

const md = createMarkdownIt(hljs)

test.describe('流式安全渲染', () => {
  test('未闭合块级公式不触发 KaTeX 红字', () => {
    const partial = '前缀\n\n$$\n\\sum_{i=1}^{n}'
    const prepared = prepareStreamingMarkdown(partial)
    expect(prepared).not.toContain('\\sum')
    const html = streamSafeMarkdownRender(partial, (t) => md.render(t))
    expect(html).not.toContain('katex-error')
  })

  test('表格 markdown 直接渲染为 table 元素', () => {
    const table = '| 服务 | 端口 |\n|------|------|\n| BFF | 8001 |'
    const html = md.render(table)
    expect(html).toContain('<table')
    expect(html).toContain('BFF')
  })

  test('完整公式流式截断后可安全渲染', () => {
    const text = '行内 $E=mc^2$ 结束'
    const html = streamSafeMarkdownRender(text, (t) => md.render(t))
    expect(html).toContain('katex')
    expect(html).not.toContain('katex-error')
  })

  test('误闭合块级公式在流式前缀中被隐藏', () => {
    const chunk = '块级公式：\n\n$$\\int_a^b f$$'
    const full = '块级公式：\n\n$$\\int_a^b f$$(x)dx$$'
    const prepared = prepareStreamingMarkdown(chunk, full)
    expect(prepared).not.toContain('\\int_a^b f')
    expect(prepared).toContain('块级公式')
    const html = streamSafeMarkdownRender(chunk, (t) => md.render(t), full)
    expect(html).not.toContain('katex-display')
    expect(html).not.toContain('(x)dx')
  })

  test('同行误闭合块级公式被整体隐藏', () => {
    const partial = '$$\\int_a^b f$$(x)dx'
    const prepared = prepareStreamingMarkdown(partial)
    expect(prepared).not.toContain('$$')
    expect(prepared).not.toContain('(x)dx')
  })

  test('完整块级公式可渲染', () => {
    const full = '$$\\int_a^b f(x)dx$$'
    const html = streamSafeMarkdownRender(full, (t) => md.render(t), full)
    expect(html).toContain('katex-display')
    expect(html).toContain('∫')
  })
})
