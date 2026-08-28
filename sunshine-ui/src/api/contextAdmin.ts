import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

export interface L2StateEntry {
  id: string
  userId: string
  tenantId: string
  kind: string
  stateKey: string
  stateValue: string
  confidence: number
  status: string
  expiresAt?: string | null
  sourceMsgId?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface L2UpdatePayload {
  stateValue?: string
  confidence?: number
  status?: string
}

export interface L1WindowRow {
  band: 'near' | 'mid' | 'far' | string
  index: number
  userText?: string | null
  assistantText?: string | null
  assistantSummarized?: boolean
  at?: string | null
}

export interface L1Snapshot {
  convId: string
  userId: string
  tenantId: string
  midAnswers: Record<string, string>
  farSummary: string
  farFoldedMsgIds: string[]
  nearN: number
  midN: number
  updatedAt?: string
  rows?: L1WindowRow[]
}

export interface L3Status {
  userId: string
  tenantId: string
  contextEnabled: boolean
  collection: string
  note?: string
  l1RowCount: number
  l3TopK: number
  l3MinScore: number
}

export interface L3Entry {
  msgId: string
  role: string
  chunkIndex: number
  content: string
  createdAt?: string | null
  expiresAt?: string | null
}

export interface GcResult {
  ok: boolean
  message: string
}

export interface ReingestResult {
  convId: string
  ingested: number
  message: string
}

export interface ConversationSummary {
  id: string
  title: string
  kind?: string
  workspaceId?: string | null
  checkoutPath?: string | null
  createdAt?: string
  updatedAt?: string
}

/** T0 任务进度：task_board 快照（后端 TaskBoardItemView） */
export interface TaskBoardItem {
  id: string
  content: string
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled'
  dependsOn?: string[]
}

export interface TaskBoardSnapshot {
  boardId: string
  messageId: string
  conversationId: string
  tenantId: string
  userId: string
  revision: number
  items: TaskBoardItem[]
  createdAt?: string
  updatedAt?: string
}

/** H1 计划笔记本：PlanNotebook 的 Redis 反序列化结构 */
export interface H1TaskItem {
  taskId: string
  label: string
  status: string
  dependsOn?: string[]
  constraints?: string
  expectedOutput?: string
  successCriteria?: string
  baseTaskId?: string
  retryIndex?: number
  parentTaskId?: string
  failReason?: string
}

export interface H1RoundNodeResult {
  nodeId: string
  status: string
  summary?: string
}

export interface H1Round {
  roundIndex: number
  task?: H1TaskItem | null
  nodeResults?: H1RoundNodeResult[]
  roundGoalCompletion: number
  assessReason?: string
}

export interface H1Notebook {
  originalGoal?: string
  userQuery?: string
  kind?: string
  taskQueue?: H1TaskItem[]
  rounds?: H1Round[]
  goalCompletion: number
  nextDirection?: string
  createdAt?: string
  currentRound: number
  totalTasksCompleted: number
  staleRounds: number
  replanCount: number
  sessionId?: string
  maxRounds?: number
  maxTotalTasks?: number
}

export async function listContextConversations(
  userId: string,
  tenantId = 'default',
): Promise<ConversationSummary[]> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(apiUrl(`/api/admin/context/conversations?${q}`), { headers: apiHeaders() })
  return parseApiResponse<ConversationSummary[]>(res)
}

export async function listContextL2(userId: string, tenantId = 'default'): Promise<L2StateEntry[]> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(apiUrl(`/api/admin/context/l2?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L2StateEntry[]>(res)
}

export async function updateContextL2(id: string, body: L2UpdatePayload): Promise<L2StateEntry> {
  const res = await fetch(apiUrl(`/api/admin/context/l2/${encodeURIComponent(id)}`), {
    method: 'PUT',
    headers: { ...apiHeaders(), 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return parseApiResponse<L2StateEntry>(res)
}

export async function voidContextL2(id: string): Promise<L2StateEntry> {
  const res = await fetch(apiUrl(`/api/admin/context/l2/${encodeURIComponent(id)}/void`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<L2StateEntry>(res)
}

export async function getContextL1(convId: string): Promise<L1Snapshot> {
  const q = new URLSearchParams({ convId })
  const res = await fetch(apiUrl(`/api/admin/context/l1?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L1Snapshot>(res)
}

export async function getContextL3Status(userId: string, tenantId = 'default'): Promise<L3Status> {
  const q = new URLSearchParams({ userId, tenantId })
  const res = await fetch(apiUrl(`/api/admin/context/l3/status?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L3Status>(res)
}

export async function listContextL3Entries(convId: string): Promise<L3Entry[]> {
  const q = new URLSearchParams({ convId })
  const res = await fetch(apiUrl(`/api/admin/context/l3/entries?${q}`), { headers: apiHeaders() })
  return parseApiResponse<L3Entry[]>(res)
}

export async function runContextL3Gc(): Promise<GcResult> {
  const res = await fetch(apiUrl('/api/admin/context/l3/gc'), {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<GcResult>(res)
}

export async function reingestContextL3(convId: string): Promise<ReingestResult> {
  const q = new URLSearchParams({ convId })
  const res = await fetch(apiUrl(`/api/admin/context/l3/reingest?${q}`), {
    method: 'POST',
    headers: apiHeaders(),
  })
  return parseApiResponse<ReingestResult>(res)
}

/** T0 任务进度：按会话取最近一次任务板快照 */
export async function getTaskBoardSnapshot(convId: string): Promise<TaskBoardSnapshot> {
  const res = await fetch(apiUrl(`/api/admin/context/task/${encodeURIComponent(convId)}/taskboard`), {
    headers: apiHeaders(),
  })
  return parseApiResponse<TaskBoardSnapshot>(res)
}

/** H1 计划笔记本：读 Redis 中的执行态 PlanNotebook */
export async function getH1Notebook(convId: string): Promise<H1Notebook> {
  const res = await fetch(apiUrl(`/api/admin/context/task/${encodeURIComponent(convId)}/notebook`), {
    headers: apiHeaders(),
  })
  return parseApiResponse<H1Notebook>(res)
}
