import { resolveHealthProbeUrl } from './config'

export interface CpuInfo {
  cores: number
  systemLoad: number
  processLoad: number
  processCpuTimeNanos: number
  loadAverage: number
}

export interface MemoryInfo {
  total: number
  used: number
  free: number
  committedVirtual: number
  swapTotal: number
  swapUsed: number
}

export interface DiskInfo {
  total: number
  free: number
  used: number
}

export interface JvmInfo {
  name: string
  version: string
  uptimeMillis: number
  startTimeMillis: number
}

export interface SystemInfo {
  hostname: string
  osName: string
  osVersion: string
  osArch: string
  capturedAt: number
  cpu: CpuInfo
  memory: MemoryInfo
  disk: DiskInfo
  jvm: JvmInfo
}

interface ServerProbePayload {
  status?: string
  service?: string
  data?: SystemInfo
}

/** 拉取宿主机基本资源信息（Gateway /health/server，无需鉴权）。 */
export async function fetchSystemInfo(): Promise<SystemInfo> {
  const url = resolveHealthProbeUrl('/health/server')
  const res = await fetch(url, {
    signal: AbortSignal.timeout(5000),
    headers: { Accept: 'application/json' },
  })
  if (!res.ok) throw new Error(`服务器信息探测失败：HTTP ${res.status}`)
  const body = (await res.json()) as ServerProbePayload
  if (!body.data) throw new Error('服务器信息探测失败：缺少 data')
  return body.data
}
