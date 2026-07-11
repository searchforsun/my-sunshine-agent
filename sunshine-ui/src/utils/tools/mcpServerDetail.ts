import type { McpServer, McpServerPatchBody } from '../../api/tools'

export function buildMcpServerJson(server: McpServer): string {
  const config: Record<string, unknown> = {}
  if (server.transport === 'sse') {
    config.url = server.endpoint ?? ''
  } else {
    config.command = server.command ?? ''
    try {
      config.args = JSON.parse(server.argsJson || '[]')
    } catch {
      config.args = []
    }
    try {
      const env = JSON.parse(server.envJson || '{}') as Record<string, string>
      config.env = env
    } catch {
      config.env = {}
    }
  }
  return JSON.stringify({ mcpServers: { [server.id]: config } }, null, 2)
}

export function mcpFormDraftToPatch(draft: {
  displayName: string
  transport: string
  command: string
  argsJson: string
  endpoint: string
  envJson: string
}): McpServerPatchBody {
  return {
    displayName: draft.displayName.trim() || undefined,
    transport: draft.transport,
    command: draft.transport === 'stdio' ? draft.command.trim() : '',
    argsJson: draft.transport === 'stdio' ? draft.argsJson.trim() || '[]' : '[]',
    endpoint: draft.transport === 'sse' ? draft.endpoint.trim() : '',
    envJson: draft.envJson.trim() || '{}',
  }
}

export function mcpJsonDraftToPatch(serverId: string, jsonDraft: string): McpServerPatchBody {
  let root: { mcpServers?: Record<string, Record<string, unknown>> }
  try {
    root = JSON.parse(jsonDraft.trim()) as typeof root
  } catch {
    throw new Error('JSON 格式无效')
  }
  const config = root.mcpServers?.[serverId]
  if (!config) throw new Error(`mcpServers 中缺少 "${serverId}"`)
  const url = config.url
  if (url != null && String(url).trim()) {
    return {
      transport: 'sse',
      endpoint: String(url).trim(),
      command: '',
      argsJson: '[]',
      envJson: '{}',
    }
  }
  return {
    transport: 'stdio',
    command: config.command == null ? '' : String(config.command),
    argsJson: JSON.stringify(config.args ?? []),
    envJson: JSON.stringify(config.env ?? {}),
    endpoint: '',
  }
}
