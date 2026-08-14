import { describe, expect, it } from 'vitest'
import { linkifyWebSearchText } from './webSearchLinkify'

const LINK = ' target="_blank" rel="noopener noreferrer"'
const TITLE = (url: string, label: string) =>
  `<a class="smd-search-title" href="${url}"${LINK}>${label}</a>`

describe('linkifyWebSearchText', () => {
  it('单条结果（无空行）：标题变链接，原始 URL 行移除', () => {
    const text = [
      '1. DeepSeek Harness developer preview: Everything is a plugin',
      '   https://www.deepseek.com/harness/en/',
      '   13 小时之前 · source code included. …',
    ].join('\n')
    expect(linkifyWebSearchText(text)).toBe(
      `${TITLE('https://www.deepseek.com/harness/en/', '1. DeepSeek Harness developer preview: Everything is a plugin')}\n`
      + '   13 小时之前 · source code included. …',
    )
  })

  it('多条结果（空行分隔）逐条标题链接化且 URL 行移除', () => {
    const text = [
      '1. DeepSeek Harness - GitHub',
      '   https://github.com/deepseek-ai/deepseek-harness',
      '   GitHub 仓库',
      '',
      '2. DeepSeek | 深度求索',
      '   https://www.deepseek.com/',
    ].join('\n')
    const html = linkifyWebSearchText(text)
    expect(html).toContain(TITLE('https://github.com/deepseek-ai/deepseek-harness', '1. DeepSeek Harness - GitHub'))
    expect(html).toContain(TITLE('https://www.deepseek.com/', '2. DeepSeek | 深度求索'))
    expect(html).toContain('   GitHub 仓库')
    expect(html).not.toContain('>https://github.com/deepseek-ai/deepseek-harness</a>')
    expect(html).not.toContain('>https://www.deepseek.com/</a>')
  })

  it('标题即 URL 时整行链接且不删除标题行', () => {
    const html = linkifyWebSearchText('1. https://example.com/page\n   摘要')
    expect(html).toContain(TITLE('https://example.com/page', '1. https://example.com/page'))
    expect(html).toContain('   摘要')
  })

  it('标题行不会被二次链接化（无嵌套 a 标签）', () => {
    const html = linkifyWebSearchText([
      '1. DeepSeek Harness - GitHub',
      '   https://github.com/deepseek-ai/deepseek-harness',
    ].join('\n'))
    expect(html).not.toContain('href="<a ')
    expect(html).not.toContain('<a href="https://github.com/deepseek-ai/deepseek-harness"')
    expect(html.match(/class="smd-search-title"/g)).toHaveLength(1)
  })

  it('无标题行的纯文本仅链接化裸 URL，尾部标点保留在链接外', () => {
    expect(linkifyWebSearchText('参考 https://a.com/docs 的内容')).toBe(
      `参考 <a href="https://a.com/docs"${LINK}>https://a.com/docs</a> 的内容`,
    )
    expect(linkifyWebSearchText('见 https://a.com/docs/guide.html。')).toBe(
      `见 <a href="https://a.com/docs/guide.html"${LINK}>https://a.com/docs/guide.html</a>。`,
    )
  })

  it('query 内符号保留在链接内', () => {
    const html = linkifyWebSearchText('1. 标题\n   https://a.com/p?a=1!b')
    expect(html).toContain(`href="https://a.com/p?a=1!b"${LINK}>1. 标题</a>`)
  })

  it('非 URL 文本与 HTML 特殊字符转义', () => {
    expect(linkifyWebSearchText('plain text')).toBe('plain text')
    expect(linkifyWebSearchText('a <b> & c')).toBe('a &lt;b&gt; &amp; c')
  })

  it('空文本返回空串', () => {
    expect(linkifyWebSearchText('')).toBe('')
  })
})
