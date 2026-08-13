import { apiHeaders } from '../stores/authStore'
import { resolveApiBase } from './config'
import { parseApiResponse } from './apiError'

function apiUrl(path: string): string {
  return `${resolveApiBase()}${path}`
}

/** 业务场景 Lab active 码闭集（Skill/Agent 表单 biz_scene 下拉；完整 Lab 管理在侧栏 Lab 页） */
export async function listActiveBizSceneCodes(): Promise<string[]> {
  const res = await fetch(apiUrl('/api/biz-scenes/active-codes'), { headers: apiHeaders() })
  return parseApiResponse<string[]>(res)
}
