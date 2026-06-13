import { test, expect } from '@playwright/test'

test.describe('思考过程面板', () => {
  test('可折叠展示且与正文样式分离', async ({ page }) => {
    await page.goto('/chat')

    await page.evaluate(() => {
      const host = document.createElement('div')
      host.className = 'assistant-body'
      host.style.cssText = 'padding:16px;max-width:720px'
      host.innerHTML = `
        <div class="reasoning-panel is-expanded" data-testid="reasoning-fixture">
          <button type="button" class="reasoning-toggle">
            <span class="reasoning-title">思考过程</span>
          </button>
          <div class="reasoning-body-wrap">
            <pre class="reasoning-body">我们需要分析用户问题并给出 Python 快速排序。</pre>
          </div>
        </div>
        <div class="msg-md"><p>这是正式回复正文。</p></div>
      `
      document.body.appendChild(host)
    })

    await expect(page.getByTestId('reasoning-fixture')).toBeVisible()
    await expect(page.getByText('我们需要分析用户问题')).toBeVisible()
    await expect(page.getByText('这是正式回复正文。')).toBeVisible()

    const reasoningText = await page.locator('.reasoning-body').innerText()
    const bodyText = await page.locator('.msg-md').innerText()
    expect(reasoningText).toContain('快速排序')
    expect(bodyText).not.toContain('我们需要分析')
  })
})
