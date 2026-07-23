import { test, expect } from '@playwright/test'

/**
 * AS2 P0-8 — peer-collab 顺序桥 e2e
 *
 * AgentScope 2.0 升级后 peer 模式走顺序桥（sequential bridge），专家步按顺序 append，
 * 不再断言反应式 hub 的并发交错。本用例验证：
 * 1. 底栏执行模式切换到「多专家协作」可正常发起会话；
 * 2. 时间线（默认折叠，需先展开）出现 expert-convene 召集步；
 * 3. ≥2 个专家步（.peer-line）依次出现（顺序即可，不要求交错）；
 * 4. Synthesizer 终态正文正常流出。
 *
 * 真实 DOM：专家步（stepId `expert-{id}-s{seq}`，phase=expert）在 OperationStack 中
 * 走 OperationCard 渲染，主行标签在 `.operation-card-title`（SSE step.label，
 * 如「费用报销分析专家」）；召集步 label 为「多专家协作」。
 */
import { ensureE2eLogin, fillComposer, waitForStreamComplete } from './helpers'

const E1_QUERY = '请人事制度分析专家和费用报销分析专家分别审查这笔报销是否合规，并互相验证'

test.describe('as2-p0 peer-collab 顺序桥', () => {
  test('专家步按顺序出现且终态正文正常', async ({ page }) => {
    test.setTimeout(300_000)

    await ensureE2eLogin(page)
    await page.goto('/chat')
    await expect(page.getByRole('heading', { name: '有什么可以帮你的？' })).toBeVisible({ timeout: 15_000 })

    // 执行模式选择器为按钮 + NPopover 菜单（非原生 select）：
    // 点击当前模式按钮（默认「自动」），再点「多专家协作」选项
    await page.locator('.composer-toolbar .mode-selector').click()
    const peerOption = page.locator('.mode-menu-item', { hasText: '多专家协作' })
    await expect(peerOption).toBeVisible({ timeout: 10_000 })
    await peerOption.click()
    await expect(page.locator('.composer-toolbar .mode-selector .mode-label')).toHaveText('多专家协作')

    await fillComposer(page, E1_QUERY)
    await page.keyboard.press('Enter')

    const lines = page.locator('.operation-lines').last()
    await expect(lines).toBeVisible({ timeout: 30_000 })

    // 时间线默认折叠：等流式出现专家步痕迹后展开（进行中折叠态只露最后一步预览，
    // peer 步可能不在预览里，故先等 stream 开始再点击概要行展开）
    const summaryRow = lines.locator('.timeline-summary .op-line-row')
    await expect(summaryRow).toBeVisible({ timeout: 30_000 })
    await summaryRow.click()
    await expect(lines.locator('.timeline-summary.is-expanded')).toBeVisible({ timeout: 10_000 })

    // 召集步（expert-convene，label「多专家协作」）出现
    await expect(
      lines.locator('.operation-card-title', { hasText: '多专家协作' }).first(),
    ).toBeVisible({ timeout: 60_000 })

    // ≥2 位专家依次出现（顺序桥：逐个 append，60–120s 属正常）
    // 专家步 label 即专家名（「…分析专家」），排除召集步「多专家协作」
    const expertSteps = lines.locator('.operation-card-title', { hasText: '分析专家' })
    await expect(expertSteps.first()).toBeVisible({ timeout: 120_000 })
    await expect(expertSteps).toHaveCount(2, { timeout: 240_000 })

    // Synthesizer 终态正文
    await waitForStreamComplete(page, 240_000)
    await expect(page.locator('.assistant-body .msg-md').last()).toBeVisible({ timeout: 30_000 })
    const content = await page.locator('.assistant-body .msg-md').last().innerText()
    expect(content.trim().length).toBeGreaterThan(20)
    expect(content).not.toMatch(/发生错误|租户请求过于频繁/)
  })
})
