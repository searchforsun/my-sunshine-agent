import { test, expect } from '@playwright/test'

const BROKEN_MERMAID = `flowchart TB
    B1[随机初始化 Transformer 参数] -->输入 B2[ token 序列]
    D4[采样
/束搜索选择下一个 token]
    D5 --        D5 -- 是 --> D6[输出完整回复]`

test.describe('Mermaid 语法错误', () => {
  test('失败时不向 document.body 泄漏炸弹 SVG，错误仅在容器内', async ({ page }) => {
    await page.goto('/chat')

    await page.evaluate(async (source) => {
      const host = document.createElement('div')
      host.className = 'msg-md'
      host.dataset.testid = 'mermaid-fixture'
      host.innerHTML = `<pre><code class="language-mermaid">${source.replace(/</g, '&lt;')}</code></pre>`
      document.body.appendChild(host)

      const mod = await import('/src/utils/stream-markdown/StaticEnhancer.ts')
      mod.enhanceStaticMarkdown(host)
    }, BROKEN_MERMAID)

    const wrapper = page.locator('[data-testid="mermaid-fixture"] .smd-mermaid-wrapper')
    await expect(wrapper).toBeVisible({ timeout: 15_000 })

    const inlineError = wrapper.locator('.smd-mermaid-error')
    await expect(inlineError).toBeVisible({ timeout: 15_000 })
    await expect(inlineError).toContainText('图表语法有误')

    const bodyBomb = page.locator('body > .error-text, body > div .error-text, body .error-icon')
    await expect(bodyBomb).toHaveCount(0)

    const bodySyntaxError = page.getByText('Syntax error in text', { exact: true })
    await expect(bodySyntaxError).toHaveCount(0)
  })
})
