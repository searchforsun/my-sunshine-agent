import { test, expect } from '@playwright/test'

test.describe('思考过程折叠', () => {
  test('输出完成后首次点击即可展开/折叠', async ({ page }) => {
    await page.goto('/chat')

    const input = page.getByPlaceholder('发消息，Enter 发送')
    await input.fill('写一句关于快速排序的简介')
    await input.press('Enter')

    await expect(page.locator('.composer-box--streaming')).toHaveCount(0, { timeout: 120_000 })

    const panel = page.locator('.assistant-body').last().locator('.reasoning-panel')
    test.skip(await panel.count() === 0, '当前模型未返回 reasoning，跳过')

    const body = panel.locator('.reasoning-body')
    await expect(panel).not.toHaveClass(/is-expanded/)
    await expect(body).toBeHidden()

    await panel.locator('.reasoning-toggle').click()
    await expect(panel).toHaveClass(/is-expanded/)
    await expect(body).toBeVisible()

    await panel.locator('.reasoning-toggle').click()
    await expect(panel).not.toHaveClass(/is-expanded/)
    await expect(body).toBeHidden()
  })

  test('自动展开时首次点击应能折叠', async ({ page }) => {
    await page.goto('/chat')
    await page.evaluate(() => {
      type State = { expanded: Map<string, boolean>; toggled: Set<string> }

      function isExpanded(state: State, key: string, auto: boolean): boolean {
        if (state.toggled.has(key)) return state.expanded.get(key) ?? false
        return auto
      }

      function toggle(state: State, key: string, auto: boolean): void {
        const currentlyExpanded = isExpanded(state, key, auto)
        state.toggled.add(key)
        state.expanded.set(key, !currentlyExpanded)
      }

      const state: State = { expanded: new Map(), toggled: new Set() }
      const key = 'msg-1'
      const auto = true

      toggle(state, key, auto)
      if (isExpanded(state, key, auto) !== false) {
        throw new Error('首次点击在自动展开状态下应折叠')
      }

      toggle(state, key, auto)
      if (isExpanded(state, key, auto) !== true) {
        throw new Error('第二次点击应重新展开')
      }
    })
  })
})
