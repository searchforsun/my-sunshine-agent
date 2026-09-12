import { describe, expect, it } from 'vitest'
import type { ProcessingStep } from './processingSteps'
import {
  extractSandboxExecCommand,
  extractSandboxSearchRoot,
  formatExecCommandHeader,
  formatExecCommandHeaderText,
  hasExpandableContent,
  inferSandboxSearchRoot,
  isSandboxExecStep,
  isSandboxFetchStep,
  isSandboxReadStep,
  isSandboxToolStep,
  parseSandboxPathList,
  resolveLatestChildStepBody,
  resolveSandboxFocusPath,
  resolveSandboxReadLineRange,
  resolveStepExpandInner,
  resolveStepHeaderText,
  sandboxDisplayPath,
  sandboxToolKind,
  shouldShiftSummaryOnExpand,
  stripWorkspaceCheckoutPrefixInText,
} from './processingStepsDisplay'

function sandboxStep(partial: Partial<ProcessingStep> & { id: string }): ProcessingStep {
  return {
    phase: 'tool',
    lifecycle: 'done',
    label: '调用工具 读文件',
    summary: { after: 'hello.py' },
    metadata: { sandboxPath: '/skills/demo/scripts/hello.py' },
    detail: 'print("hi")\n',
    ...partial,
  }
}

describe('sandbox tool timeline display', () => {
  it('detects sandbox__* tool step ids by prefix', () => {
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sandbox__read@123' }))).toBe(true)
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sandbox__exec@9' }))).toBe(true)
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sandbox__future@1' }))).toBe(true)
    expect(isSandboxToolStep(sandboxStep({ id: 'tool-sdk__finance__list@1' }))).toBe(false)
  })

  it('detects sandbox__read step', () => {
    expect(isSandboxReadStep(sandboxStep({ id: 'tool-sandbox__read@1' }))).toBe(true)
    expect(isSandboxReadStep(sandboxStep({ id: 'tool-sandbox__exec@1' }))).toBe(false)
    expect(isSandboxReadStep(sandboxStep({ id: 'tool-sdk__finance__list@1' }))).toBe(false)
  })

  it('isSandboxFetchStep：仅网页工具', () => {
    expect(isSandboxFetchStep(sandboxStep({ id: 'tool-sandbox__webfetch@1' }))).toBe(true)
    expect(isSandboxFetchStep(sandboxStep({ id: 'tool-sandbox__websearch@2' }))).toBe(true)
    expect(isSandboxFetchStep(sandboxStep({ id: 'tool-sandbox__read@3' }))).toBe(false)
    expect(isSandboxFetchStep(sandboxStep({ id: 'tool-sandbox__exec@4' }))).toBe(false)
  })

  it('read step: parses line range from after summary', () => {
    const partial = sandboxStep({
      id: 'tool-sandbox__read@1',
      summary: { after: 'test.py L20-28' },
    })
    expect(resolveSandboxReadLineRange(partial)).toEqual({ start: 20, end: 28 })
    const full = sandboxStep({
      id: 'tool-sandbox__read@2',
      summary: { after: 'readme.md L1-129' },
    })
    expect(resolveSandboxReadLineRange(full)).toEqual({ start: 1, end: 129 })
  })

  it('read step: no line range when after has none', () => {
    expect(resolveSandboxReadLineRange(sandboxStep({ id: 'tool-sandbox__read@1' }))).toBeUndefined()
    expect(resolveSandboxReadLineRange(sandboxStep({
      id: 'tool-sandbox__read@1',
      summary: { after: '读文件 test.py' },
    }))).toBeUndefined()
  })

  it('read step: content is not expanded (only locate in workspace)', () => {
    const step = sandboxStep({ id: 'tool-sandbox__read@1', detail: 'line1\nline2' })
    expect(hasExpandableContent(step)).toBe(false)
  })

  it('cancelled exec: header trusts after; expand command from detail (lifecycle, not 已取消 text)', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@9',
      label: '执行命令',
      lifecycle: 'paused',
      summary: { before: '准备执行命令', active: '正在执行 sleep 120', after: '用户取消' },
      detail: 'sleep 120',
      metadata: { cancellable: true },
    })
    expect(resolveStepHeaderText(step)).toBe('用户取消')
    expect(extractSandboxExecCommand(step)).toBe('sleep 120')
    expect(hasExpandableContent(step)).toBe(true)
    expect(resolveStepExpandInner(step)).toBe('')
  })

  it('paused without after: header empty (no legacy active fallback)', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@10',
      label: '调用工具 执行命令',
      lifecycle: 'paused',
      summary: { active: '已取消' },
      detail: 'sleep 120',
      metadata: { cancellable: true },
    })
    expect(resolveStepHeaderText(step)).toBe('')
  })

  it('strips /workspace/wt-xxx from write/edit/read header display', () => {
    expect(stripWorkspaceCheckoutPrefixInText(
      '正在写入 /workspace/wt-123466/docs/superpowers/specs/a.md',
    )).toBe('正在写入 docs/superpowers/specs/a.md')
    expect(sandboxDisplayPath('/workspace/wt-123466/docs/a.md')).toBe('docs/a.md')
    const writing = sandboxStep({
      id: 'tool-sandbox__write@1',
      lifecycle: 'running',
      label: '写文件',
      summary: { active: '正在写入 /workspace/wt-123466/docs/a.md' },
      metadata: { sandboxPath: '/workspace/wt-123466/docs/a.md' },
    })
    expect(resolveStepHeaderText(writing)).toBe('正在写入 docs/a.md')
    expect(resolveSandboxFocusPath(writing)).toBe('/workspace/wt-123466/docs/a.md')
  })

  it('header shows backend summary as-is; focus uses metadata.sandboxPath', () => {
    const step = sandboxStep({ id: 'tool-sandbox__read@1' })
    expect(resolveStepHeaderText(step)).toBe('hello.py')
    expect(resolveSandboxFocusPath(step)).toBe('/skills/demo/scripts/hello.py')
    expect(shouldShiftSummaryOnExpand(step)).toBe(false)
    const longCmd =
      'python3 -c "import csv; total=0.0; ' + 'x'.repeat(80) + '"'
    const exec = sandboxStep({
      id: 'tool-sandbox__exec@2',
      label: '调用工具 执行命令',
      summary: { after: longCmd },
      metadata: {},
      detail: 'ok',
    })
    const header = resolveStepHeaderText(exec)
    expect(header.length).toBeLessThanOrEqual(43)
    expect(header.endsWith('…')).toBe(false)
    expect(extractSandboxExecCommand(exec)).toBe(longCmd)
  })

  it('parses glob path list relative to search root', () => {
    const entries = parseSandboxPathList(
      '/skills/sandbox-coding-demo/SKILL.md\n/skills/sandbox-coding-demo/scripts/hello.py\n',
      '/skills',
    )
    expect(entries.map(e => e.name)).toEqual([
      'sandbox-coding-demo/SKILL.md',
      'sandbox-coding-demo/scripts/hello.py',
    ])
    expect(entries[0].path).toBe('/skills/sandbox-coding-demo/SKILL.md')
    const nested = parseSandboxPathList(
      '/skills/sandbox-coding-demo/scripts/hello.py\n',
      '/skills/sandbox-coding-demo',
    )
    expect(nested[0].name).toBe('scripts/hello.py')
    expect(inferSandboxSearchRoot(entries.map(e => e.path))).toBe('/skills')
  })

  it('glob header trusts backend after with search root', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__glob@1',
      label: '调用工具 查找文件',
      summary: { after: '**/* · /skills' },
      metadata: { sandboxSearchRoot: '/skills' },
      detail: '/skills/sandbox-coding-demo/SKILL.md\n/skills/sandbox-coding-demo/scripts/hello.py\n',
    })
    expect(resolveStepHeaderText(step)).toBe('**/* · /skills')
    expect(extractSandboxSearchRoot(step.summary?.after)).toBe('/skills')
  })

  it('grep header trusts backend pattern-only after', () => {
    const grep = sandboxStep({
      id: 'tool-sandbox__grep@1',
      label: '调用工具 搜索内容',
      summary: { after: 'hello' },
      metadata: {},
      detail: 'sandbox-coding-demo/scripts/hello.py:8: print("hello")\n',
    })
    expect(resolveStepHeaderText(grep)).toBe('hello')
  })

  it('returns raw expand body without code fence', () => {
    const step = sandboxStep({ id: 'tool-sandbox__read@1', detail: 'line1\nline2' })
    expect(resolveStepExpandInner(step)).toBe('line1\nline2')
  })

  it('extracts exec command and keeps raw output for expand panel', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@1',
      label: '调用工具 执行命令',
      summary: { after: 'ls -la /skills' },
      metadata: {},
      detail: 'total 0\ndrwxr-xr-x 1 root root 0 Jul 16 03:32 .\n',
    })
    expect(isSandboxExecStep(step)).toBe(true)
    expect(extractSandboxExecCommand(step)).toBe('ls -la /skills')
    expect(resolveStepExpandInner(step)).toBe('total 0\ndrwxr-xr-x 1 root root 0 Jul 16 03:32 .')
  })

  it('edit step with metadata.editDiff is expandable even without detail', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__edit@1',
      label: '调用工具 编辑文件',
      summary: { after: 'hello.py +1 -1' },
      detail: '',
      metadata: {
        sandboxPath: '/skills/demo/scripts/hello.py',
        editDiff: {
          path: '/skills/demo/scripts/hello.py',
          contextRadius: 3,
          lines: [
            { kind: 'del', text: 'old', oldLine: 2, newLine: null },
            { kind: 'add', text: 'new', oldLine: null, newLine: 2 },
          ],
        },
      },
    })
    expect(resolveStepExpandInner(step)).toBe('')
    expect(hasExpandableContent(step)).toBe(true)
  })

  it('HITL awaiting edit with metadata.editDiff is expandable', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__edit@2',
      label: '调用工具 编辑文件',
      lifecycle: 'running',
      summary: { active: '待确认编辑 hello.py' },
      detail: '',
      metadata: {
        hitlStatus: 'awaiting',
        hitlToken: 'tok-edit-1',
        sandboxPath: '/skills/demo/scripts/hello.py',
        editDiff: {
          path: '/skills/demo/scripts/hello.py',
          lines: [{ kind: 'add', text: 'pending', oldLine: null, newLine: 1 }],
        },
      },
    })
    expect(hasExpandableContent(step)).toBe(true)
  })

  it('formatExecCommandHeader: keeps first token per pipe/and segment', () => {
    expect(formatExecCommandHeader(
      'find /workspace/wt-1b385872d4 -maxdepth 3 -type f | head -120',
    )).toBe('find | head')
    expect(formatExecCommandHeader('ls -la')).toBe('ls')
    expect(formatExecCommandHeader('ls -la src 2>&1 || echo "src 目录不存在"')).toBe('ls || echo')
    expect(formatExecCommandHeader('cd /tmp && ls -la')).toBe('cd && ls')
    expect(formatExecCommandHeader('python3 -c "print(1 | 2)"')).toBe('python3')
    expect(formatExecCommandHeader('echo "a | b" && echo c; sleep 1')).toBe('echo && echo ; sleep')
  })

  it('formatExecCommandHeaderText: think_summary 摘要前置 + 命令头', () => {
    expect(formatExecCommandHeaderText(
      'git status | grep src',
      '提交一下改动',
    )).toBe('提交一下改动 git | grep')
    expect(formatExecCommandHeaderText(
      'find /workspace -name "*.py" | head -20',
      '',
    )).toBe('find | head')
    expect(formatExecCommandHeaderText('ls -la', '  ')).toBe('ls')
    expect(formatExecCommandHeaderText('ls -la', undefined)).toBe('ls')
  })

  it('header for exec step: shows compact command head, keeps cancelled text', () => {
    const exec = sandboxStep({
      id: 'tool-sandbox__exec@3',
      label: '执行命令',
      lifecycle: 'done',
      summary: { after: 'find /workspace/wt-1b385872d4 -maxdepth 3 -type f | head -120' },
      detail: 'ok',
    })
    expect(extractSandboxExecCommand(exec)).toBe(
      'find /workspace/wt-1b385872d4 -maxdepth 3 -type f | head -120',
    )
  })

  it('HITL awaiting exec: command extracted from detail (running, command 已写入 detail)', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@11',
      label: '执行命令',
      lifecycle: 'running',
      summary: { active: '等待用户确认执行写操作' },
      detail: 'ls -la /workspace',
      metadata: { cancellable: true, hitlStatus: 'awaiting', hitlToken: 'tok-exec-1' },
    })
    expect(extractSandboxExecCommand(step)).toBe('ls -la /workspace')
  })

  it('HITL awaiting exec without detail falls back to undefined (no stdout as command)', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@12',
      label: '执行命令',
      lifecycle: 'running',
      summary: { active: '等待用户确认执行写操作' },
      detail: '',
      metadata: { cancellable: true, hitlStatus: 'awaiting', hitlToken: 'tok-exec-2' },
    })
    expect(extractSandboxExecCommand(step)).toBeUndefined()
  })

  it('done exec: stdout in detail is never treated as the command', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@13',
      label: '执行命令',
      lifecycle: 'done',
      summary: { after: 'echo hello' },
      detail: 'hello\n',
      metadata: {},
    })
    expect(extractSandboxExecCommand(step)).toBe('echo hello')
  })

  it('running exec: command extracted from active summary (正在执行 {command})', () => {
    const step = sandboxStep({
      id: 'tool-sandbox__exec@14',
      label: '执行命令',
      lifecycle: 'running',
      summary: { active: '正在执行 ls -la /workspace' },
      detail: '',
      metadata: { cancellable: true },
    })
    expect(extractSandboxExecCommand(step)).toBe('ls -la /workspace')
  })
})

describe('sandboxToolKind 按用途细分（组文案决定）', () => {
  it('查看类：read / glob / grep', () => {
    expect(sandboxToolKind('sandbox__read')).toBe('view')
    expect(sandboxToolKind('sandbox__glob')).toBe('view')
    expect(sandboxToolKind('sandbox__grep')).toBe('view')
  })

  it('修改类：write / edit', () => {
    expect(sandboxToolKind('sandbox__write')).toBe('edit')
    expect(sandboxToolKind('sandbox__edit')).toBe('edit')
  })

  it('查找类：webfetch / websearch', () => {
    expect(sandboxToolKind('sandbox__webfetch')).toBe('fetch')
    expect(sandboxToolKind('sandbox__websearch')).toBe('fetch')
  })

  it('其余 sandbox 工具归执行类', () => {
    expect(sandboxToolKind('sandbox__exec')).toBe('exec')
    expect(sandboxToolKind('sandbox__unknown')).toBe('exec')
  })

  it('非 sandbox 工具返回 null', () => {
    expect(sandboxToolKind('sdk__sunshine-oa__list_oa_tasks')).toBeNull()
    expect(sandboxToolKind(undefined)).toBeNull()
  })
})

describe('resolveLatestChildStepBody · 卡片最新阶段正文', () => {
  function child(partial: Partial<ProcessingStep> & { id: string; phase: string }): ProcessingStep {
    return {
      lifecycle: 'done',
      ...partial,
    }
  }

  it('无正文载体返回空', () => {
    const step: ProcessingStep = { id: 'worker-t1-1', phase: 'worker', lifecycle: 'running' }
    expect(resolveLatestChildStepBody(step)).toBe('')
  })

  it('运行中 contentBlocks 末段非空即展示其最新一行（流式跟随）', () => {
    const step: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'running',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '已确认关键词\n等待打开网页' },
      ],
    }
    expect(resolveLatestChildStepBody(step)).toBe('等待打开网页')
  })

  it('只有思考 reasoning 无正文段时返回空（不占位不展示思考）', () => {
    const step: ProcessingStep = {
      id: 'worker-t1-1', phase: 'worker', lifecycle: 'running',
      subSteps: [child({ id: 'think-2', phase: 'think', lifecycle: 'running', reasoning: '内部推理' })],
    }
    expect(resolveLatestChildStepBody(step)).toBe('')
  })

  it('只有工具输出无正文段时返回空（不占位不展示工具内容）', () => {
    const step: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'running',
      subSteps: [
        child({ id: 'tool-sandbox__exec@1', phase: 'tool', label: '执行命令', lifecycle: 'running', output: 'ls 输出' }),
      ],
    }
    expect(resolveLatestChildStepBody(step)).toBe('')
  })

  it('上一段正文持续展示，直到下一段正文出现（空段回退上一非空段）', () => {
    const step: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'running',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '开始检索资料' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: '' },
      ],
    }
    expect(resolveLatestChildStepBody(step)).toBe('开始检索资料')
  })

  it('终态卡（done）末段正文识别为最终答复，固定显示「总结并回答」', () => {
    const step: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'done',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think-2', text: '第一段过程说明' },
        { segmentId: 'content-2', afterStepId: 'think-3', text: 'X（原 Twitter）平台调研总结' },
      ],
    }
    expect(resolveLatestChildStepBody(step)).toBe('总结并回答')
  })

  it('运行中即使已有正文段也显示最新一行（锚点不区分收尾段，不猜阶段）', () => {
    const step: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'running',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '开始检索资料' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: 'X（原' },
      ],
    }
    expect(resolveLatestChildStepBody(step)).toBe('X（原')
  })

  it('中间阶段正文不受最终段影响，显示最新一行', () => {
    const step: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'running',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '已确认关键词\n等待打开网页' },
      ],
    }
    expect(resolveLatestChildStepBody(step)).toBe('等待打开网页')
  })

  it('中间段为空时回退上一非空段，持续展示到下一段正文出现', () => {
    const step: ProcessingStep = {
      id: 'subagent-abc', phase: 'subagent', lifecycle: 'running',
      contentBlocks: [
        { segmentId: 'content-1', afterStepId: 'think', text: '开始检索资料' },
        { segmentId: 'content-2', afterStepId: 'think-2', text: '' },
      ],
    }
    expect(resolveLatestChildStepBody(step)).toBe('开始检索资料')
  })

  it('终态无 contentBlocks 时回退 step.result（worker 终稿）显示「总结并回答」', () => {
    const step: ProcessingStep = {
      id: 'worker-t1-1', phase: 'worker', lifecycle: 'done',
      result: '接口定稿\n编辑 chain.ts 完成',
    }
    expect(resolveLatestChildStepBody(step)).toBe('总结并回答')
  })

  it('终态卡无任何正文时返回空', () => {
    const step: ProcessingStep = {
      id: 'worker-t1-1', phase: 'worker', lifecycle: 'done',
      subSteps: [child({ id: 'tasks', phase: 'tasks', label: '任务清单', lifecycle: 'done' })],
    }
    expect(resolveLatestChildStepBody(step)).toBe('')
  })
})
