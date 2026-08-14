import { h, ref, watch } from 'vue'
import { NIcon, type TreeDragInfo, type TreeOption } from 'naive-ui'
import {
  DocumentTextOutline,
  FolderOpenOutline,
  FolderOutline,
} from '@vicons/ionicons5'
import { listSandboxWorkspace, listWorkspaceSandboxFiles, type SandboxFsNode } from '../api/sandboxWorkspace'
import { setSandboxPathDrag } from '../utils/sandboxPathChip'
import { formatFileSize } from '../utils/buildFileTree'

export interface UseSandboxFileTreeOptions {
  getConversationId: () => string
  getWorkspaceId?: () => string | null
  /** 工作区模式下选中的 checkoutId（缺省 main），根目录直接映射项目 checkout */
  getCheckoutId?: () => string | null
  /** 工作区模式根节点显示名（项目名） */
  getWorkspaceName?: () => string | null
  /** 是否任务工作区：task 会话绑定了工作区但未选 checkout 时显示空态；chat 简化工作区不在此列 */
  getTaskMode?: () => boolean
  onOpenFile: (path: string, focusLine?: number) => void | Promise<void>
}

/** 打开工作区时文件树加载超时（docker stop 后需重启容器，可能较慢） */
const LOAD_TIMEOUT_MS = 15000

export function useSandboxFileTree(options: UseSandboxFileTreeOptions) {
  const treeLoading = ref(false)
  const errorText = ref('')
  const treeData = ref<TreeOption[]>([])
  const expandedKeys = ref<string[]>([])
  const selectedKeys = ref<string[]>([])
  /** 工作区模式下项目根 key（/workspace/{checkoutId}）；普通对话沙箱为空 */
  const workspaceRootKey = ref('')
  /** 加载超时标记：由抽屉层触发「自动返回」（关闭抽屉） */
  const timedOut = ref(false)

  function dirIcon(expanded: boolean) {
    return () => h(NIcon, {
      component: expanded ? FolderOpenOutline : FolderOutline,
      size: 14,
      class: 'tree-icon-dir',
    })
  }

  function fileIcon() {
    return () => h(NIcon, {
      component: DocumentTextOutline,
      size: 14,
      class: 'tree-icon-file',
    })
  }

  function toOptions(entries: SandboxFsNode[]): TreeOption[] {
    const sorted = [...entries].sort((a, b) => {
      const ad = a.type === 'dir' ? 0 : 1
      const bd = b.type === 'dir' ? 0 : 1
      if (ad !== bd) return ad - bd
      return a.name.localeCompare(b.name)
    })
    return sorted.map((n) => {
      const isDir = n.type === 'dir'
      const size = typeof n.size === 'number' && n.size >= 0 ? n.size : null
      const opt: TreeOption = {
        key: n.path,
        label: n.name,
        isLeaf: !isDir,
        prefix: isDir ? dirIcon(false) : fileIcon(),
        suffix: !isDir && size != null
          ? () => h('span', { class: 'tree-size' }, formatFileSize(size))
          : undefined,
      }
      ;(opt as TreeOption & { path: string; isDir: boolean }).path = n.path
      ;(opt as TreeOption & { path: string; isDir: boolean }).isDir = isDir
      return opt
    })
  }

  function fetchChildrenRaw(path: string): Promise<TreeOption[]> {
    const conversationId = options.getConversationId()
    if (conversationId) {
      return listSandboxWorkspace(conversationId, path).then(data => toOptions(data.entries ?? []))
    }
    const wsId = options.getWorkspaceId?.()
    if (wsId) {
      return listWorkspaceSandboxFiles(wsId, path).then(data => toOptions(data.entries ?? []))
    }
    return Promise.resolve([])
  }

  /** 带超时的目录列举；超时抛「加载超时」 */
  async function fetchChildren(path: string): Promise<TreeOption[]> {
    const task = fetchChildrenRaw(path)
    const timer = new Promise<never>((_, reject) => {
      setTimeout(() => reject(new Error('加载超时')), LOAD_TIMEOUT_MS)
    })
    return Promise.race([task, timer])
  }

  async function loadRoots() {
    const conversationId = options.getConversationId()
    const wsId = options.getWorkspaceId?.()
    if (!conversationId && !wsId) return
    treeLoading.value = true
    errorText.value = ''
    timedOut.value = false
    try {
      // 工作区模式：项目根（类 VSCode 打开项目 checkout 目录）+ skills 根
      const checkoutId = options.getCheckoutId?.()
      if (wsId && checkoutId) {
        const rootPath = `/workspace/${checkoutId}`
        // 项目根失败/超时 → 整体失败（可能容器未就绪）；skills 独立失败不阻塞
        const kids = await fetchChildren(rootPath)
        const skillKids = await fetchChildren('/skills').catch(() => [] as TreeOption[])
        workspaceRootKey.value = rootPath
        treeData.value = [
          {
            key: rootPath,
            label: options.getWorkspaceName?.() || checkoutId,
            isLeaf: false,
            path: rootPath,
            isDir: true,
            children: kids,
            prefix: dirIcon(true),
          },
          {
            key: '/skills',
            label: 'skills',
            isLeaf: false,
            path: '/skills',
            isDir: true,
            children: skillKids,
            prefix: dirIcon(true),
          },
        ]
        expandedKeys.value = [rootPath, '/skills']
        return
      }
      // 任务工作区但尚未选择 checkout（新任务未发送）：无代码，空态由抽屉提示；
      // chat 简化工作区（绑定了 workspaceId 但非 task）不受此限制，走下方会话沙箱文件树
      if (wsId && options.getTaskMode?.()) {
        workspaceRootKey.value = ''
        treeData.value = []
        expandedKeys.value = []
        return
      }
      workspaceRootKey.value = ''
      const [wsKids, skillKids] = await Promise.all([
        fetchChildren('/workspace').catch(() => [] as TreeOption[]),
        fetchChildren('/skills').catch(() => [] as TreeOption[]),
      ])
      treeData.value = [
        {
          key: '/workspace',
          label: 'workspace',
          isLeaf: false,
          path: '/workspace',
          isDir: true,
          children: wsKids,
          prefix: dirIcon(true),
        },
        {
          key: '/skills',
          label: 'skills',
          isLeaf: false,
          path: '/skills',
          isDir: true,
          children: skillKids,
          prefix: dirIcon(true),
        },
      ]
      expandedKeys.value = ['/workspace', '/skills']
    } catch (e) {
      const timed = e instanceof Error && e.message === '加载超时'
      timedOut.value = timed
      errorText.value = timed ? '加载超时，工作区容器可能尚未就绪' : (e instanceof Error ? e.message : '加载失败')
      treeData.value = []
    } finally {
      treeLoading.value = false
    }
  }

  function findNode(nodes: TreeOption[], key: string): TreeOption | null {
    for (const n of nodes) {
      if (String(n.key) === key) return n
      if (n.children?.length) {
        const hit = findNode(n.children as TreeOption[], key)
        if (hit) return hit
      }
    }
    return null
  }

  /** 项目名（getWorkspaceName）异步就绪后，把根节点 label 从 checkoutId 刷新为项目名。
   *  重建根节点对象（而非原地改 label）以变更节点引用，确保 NTree 渲染链识别变化。 */
  watch(
    () => options.getWorkspaceName?.(),
    (name) => {
      if (!name || !workspaceRootKey.value) return
      treeData.value = treeData.value.map(n =>
        String(n.key) === workspaceRootKey.value && n.label !== name
          ? { ...n, label: name }
          : n,
      )
    },
    { immediate: true },
  )

  /** 仅刷新指定根目录子树（保留另一根目录已加载内容） */
  async function reloadBranch(rootPath: '/workspace' | '/skills') {
    const conversationId = options.getConversationId()
    const wsId = options.getWorkspaceId?.()
    if (!conversationId && !wsId) return
    // 工作区模式下「workspace」分支 = 项目 checkout 根
    const effectiveRoot = rootPath === '/workspace' && workspaceRootKey.value
      ? workspaceRootKey.value
      : rootPath
    if (!treeData.value.length) {
      await loadRoots()
      return
    }
    const root = findNode(treeData.value, effectiveRoot)
    if (!root) {
      await loadRoots()
      return
    }
    root.children = await fetchChildren(effectiveRoot)
    const expandedUnder = expandedKeys.value
      .filter((k) => k.startsWith(`${effectiveRoot}/`))
      .sort((a, b) => a.length - b.length)
    for (const dir of expandedUnder) {
      const node = findNode(treeData.value, dir)
      if (node) node.children = await fetchChildren(dir)
    }
    treeData.value = [...treeData.value]
  }

  function treeNodeProps({ option }: { option: TreeOption }) {
    return {
      title: String((option as TreeOption & { path?: string }).path || option.key),
    }
  }

  function onTreeDragStart({ event, node }: TreeDragInfo) {
    const ext = node as TreeOption & { path?: string; isDir?: boolean }
    const path = (ext.path || String(node.key)).trim()
    if (!path.startsWith('/workspace') && !path.startsWith('/skills')) return
    if (!event.dataTransfer) return
    setSandboxPathDrag(event.dataTransfer, {
      path,
      name: String(node.label ?? path),
      isDir: !!ext.isDir || node.isLeaf === false,
    })
  }

  function denyTreeDrop() {
    return false
  }

  async function onLoad(option: TreeOption): Promise<void> {
    const path = String(option.key)
    try {
      option.children = await fetchChildren(path)
    } catch {
      option.children = []
    }
  }

  function onUpdateExpanded(keys: Array<string | number>) {
    expandedKeys.value = keys.map(String)
  }

  function onSelect(keys: Array<string | number>, option: Array<TreeOption | null>) {
    selectedKeys.value = keys.map(String)
    const opt = option[0]
    if (!opt) return
    const path = String(opt.key)
    const isDir = (opt as TreeOption & { isDir?: boolean }).isDir || !opt.isLeaf
    if (isDir) {
      const set = new Set(expandedKeys.value)
      if (set.has(path)) set.delete(path)
      else set.add(path)
      expandedKeys.value = [...set]
      return
    }
    void options.onOpenFile(path)
  }

  async function ensureExpanded(dirPath: string) {
    const node = findNode(treeData.value, dirPath)
    if (!node) return
    if (!node.children || node.children.length === 0) {
      await onLoad(node)
      treeData.value = [...treeData.value]
    }
  }

  async function revealPath(focus: string, focusLine?: number) {
    if (!focus.startsWith('/workspace') && !focus.startsWith('/skills')) return
    // 通配符路径（含 * ? [ 等）：截断到最后一个不含通配符的段，作为目录展开
    const hasWildcard = /[*?\[\]{}]/.test(focus)
    if (hasWildcard) {
      const parts = focus.split('/').filter(Boolean)
      let safePath = ''
      for (const p of parts) {
        if (/[*?\[\]{}]/.test(p)) break
        safePath += `/${p}`
      }
      if (!safePath || safePath === '/workspace' || safePath === '/skills') return
      await ensureExpanded(safePath)
      expandedKeys.value = [...new Set([...expandedKeys.value, safePath])]
      selectedKeys.value = [safePath]
      return
    }
    const parts = focus.split('/').filter(Boolean)
    const ancestors: string[] = []
    let acc = ''
    for (let i = 0; i < parts.length - 1; i++) {
      acc += `/${parts[i]}`
      ancestors.push(acc)
    }
    for (const dir of ancestors) {
      await ensureExpanded(dir)
    }
    if (focus !== '/workspace' && focus !== '/skills') {
      // 通过文件树节点 isDir 属性精确判断目录/文件（避免扩展名启发式误判）
      const node = findNode(treeData.value, focus)
      const isDir = node ? !!(node as TreeOption & { isDir?: boolean }).isDir || !node.isLeaf : false
      if (isDir) {
        expandedKeys.value = [...new Set([...expandedKeys.value, ...ancestors, focus])]
        selectedKeys.value = [focus]
      } else {
        expandedKeys.value = [...new Set([...expandedKeys.value, ...ancestors])]
        await options.onOpenFile(focus, focusLine)
      }
    }
  }

  function resetTree() {
    treeData.value = []
    expandedKeys.value = []
    selectedKeys.value = []
    errorText.value = ''
    timedOut.value = false
  }

  return {
    treeLoading,
    errorText,
    timedOut,
    treeData,
    expandedKeys,
    selectedKeys,
    loadRoots,
    treeNodeProps,
    onTreeDragStart,
    denyTreeDrop,
    onLoad,
    onUpdateExpanded,
    onSelect,
    revealPath,
    resetTree,
    reloadBranch,
  }
}
