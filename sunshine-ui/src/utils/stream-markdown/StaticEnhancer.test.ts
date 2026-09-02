// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { enhanceStaticMarkdown } from './StaticEnhancer'

const ROOT = '/workspace/wt-test'

function mountContainer(className: string, html: string): HTMLElement {
  const div = document.createElement('div')
  div.className = className
  div.innerHTML = html
  document.body.appendChild(div)
  return div
}

describe('enhanceStaticMarkdown sandbox path links', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    ;(window as any).__smd_sandboxRoot = ROOT
    ;(window as any).__smd_sandboxIndex = new Set([
      '/workspace/wt-test/README.md',
      '/workspace/wt-test/scripts/README.md',
      '/workspace/wt-test/example',
      '/workspace/wt-test/example/sub.md',
    ])
  })

  it('enhances paths in settled (non-streaming) containers', () => {
    const el = mountContainer('msg-md static-md', '<p>读 <code>scripts/README.md</code> 文件</p>')
    enhanceStaticMarkdown(el)
    const code = el.querySelector('code.smd-sandbox-path')
    expect(code).not.toBeNull()
    expect(code?.getAttribute('data-sandbox-path')).toBe('/workspace/wt-test/scripts/README.md')
  })

  it('skips path enhancement while the container is streaming (avoids flicker)', () => {
    const el = mountContainer('msg-md streaming', '<p>读 <code>scripts/README.md</code> 文件</p>')
    enhanceStaticMarkdown(el)
    expect(el.querySelector('code.smd-sandbox-path')).toBeNull()
    expect(el.querySelector('code')?.getAttribute('data-sandbox-path')).toBeNull()
  })

  it('skips path enhancement when a streaming ancestor wraps the container', () => {
    const shell = document.createElement('div')
    shell.className = 'msg-md streaming'
    shell.innerHTML = '<div class="msg-md static-md"><p>读 <code>scripts/README.md</code></p></div>'
    document.body.appendChild(shell)
    const inner = shell.querySelector('.msg-md') as HTMLElement
    enhanceStaticMarkdown(inner)
    expect(inner.querySelector('code.smd-sandbox-path')).toBeNull()
  })

  it('enhances after streaming finishes (container no longer has .streaming)', () => {
    const el = mountContainer('msg-md streaming', '<p>读 <code>scripts/README.md</code></p>')
    enhanceStaticMarkdown(el)
    expect(el.querySelector('code.smd-sandbox-path')).toBeNull()
    el.classList.remove('streaming')
    enhanceStaticMarkdown(el)
    expect(el.querySelector('code.smd-sandbox-path')).not.toBeNull()
  })

  it('does not re-enhance an already enhanced container on repeat calls', () => {
    const el = mountContainer('msg-md static-md', '<p>读 <code>scripts/README.md</code></p>')
    enhanceStaticMarkdown(el)
    expect(el.querySelectorAll('code.smd-sandbox-path')).toHaveLength(1)
    // 重复增强（如流式中多次 v-html 重建后重新调用）不产生重复/闪烁
    enhanceStaticMarkdown(el)
    expect(el.querySelectorAll('code.smd-sandbox-path')).toHaveLength(1)
    expect(el.querySelector('code.smd-sandbox-path')?.getAttribute('data-sandbox-path'))
      .toBe('/workspace/wt-test/scripts/README.md')
  })

  it('skips path enhancement in expanded think reasoning while live (OperationCard op-detail)', () => {
    // 模拟 OperationCard 展开区：live 时 StaticMarkdown 容器带 streaming class
    const el = mountContainer(
      'msg-md static-md streaming',
      '<div class="op-detail-thinking"><p>先读 <code>scripts/README.md</code> 再继续</p></div>',
    )
    enhanceStaticMarkdown(el)
    expect(el.querySelector('code.smd-sandbox-path')).toBeNull()
  })

  it('反解 linkify 补协议的纯文本文件名 (http://README.md) 为沙箱路径', () => {
    // markdown-it linkify:true 把正文裸文件名 README.md 渲染为 <a href="http://README.md">
    const el = mountContainer('msg-md static-md', '<p>见 <a href="http://README.md">README.md</a></p>')
    enhanceStaticMarkdown(el)
    const a = el.querySelector('a[data-sandbox-path]')
    expect(a).not.toBeNull()
    expect(a?.getAttribute('data-sandbox-path')).toBe('/workspace/wt-test/README.md')
    expect(a?.hasAttribute('href')).toBe(false)
  })

  it('反解 linkify 补协议的多段相对路径 (http://scripts/README.md) 为沙箱路径', () => {
    const el = mountContainer(
      'msg-md static-md',
      '<p>见 <a href="http://scripts/README.md">scripts/README.md</a></p>',
    )
    enhanceStaticMarkdown(el)
    const a = el.querySelector('a[data-sandbox-path]')
    expect(a).not.toBeNull()
    expect(a?.getAttribute('data-sandbox-path')).toBe('/workspace/wt-test/scripts/README.md')
  })

  it('真外链 http://example.com/doc.md 不反解，保持外部链接', () => {
    const el = mountContainer(
      'msg-md static-md',
      '<p>见 <a href="http://example.com/doc.md">外部文档</a></p>',
    )
    enhanceStaticMarkdown(el)
    expect(el.querySelector('a[data-sandbox-path]')).toBeNull()
    expect(el.querySelector('a')?.getAttribute('href')).toBe('http://example.com/doc.md')
  })
})
