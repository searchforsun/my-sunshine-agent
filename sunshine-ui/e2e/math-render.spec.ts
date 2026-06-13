import { test, expect } from '@playwright/test'
import hljs from 'highlight.js/lib/core'
import { createMarkdownIt } from '../src/utils/markdown/createMarkdownIt'

const md = createMarkdownIt(hljs)

test.describe('数学公式 KaTeX 渲染', () => {
  test('行内与块级公式在聊天样式下可见', async ({ page }) => {
    const html = md.render(`行内公式：$E = mc^2$

块级公式：

$$
\\sum_{i=1}^{n} i = \\frac{n(n+1)}{2}
$$`)

    expect(html).toContain('katex-display')
    expect(html).toContain('E = mc^2')

    await page.goto('/chat')
    await page.evaluate((inner) => {
      const host = document.createElement('div')
      host.className = 'msg-md'
      host.innerHTML = inner
      host.setAttribute('data-testid', 'math-fixture')
      document.body.appendChild(host)
    }, html)

    await expect(page.getByTestId('math-fixture').locator('.katex')).toHaveCount(2)
    await expect(page.getByTestId('math-fixture').locator('.katex-display')).toHaveCount(1)
    await expect(page.getByTestId('math-fixture')).toContainText('E')
    await expect(page.getByTestId('math-fixture')).toContainText('∑')
  })
})
