import { describe, expect, it } from 'vitest'
import {
  INFRA_ITEMS,
  SERVICE_DEFS,
  buildServiceList,
  countProbeable,
  domainServices,
  platformRoots,
} from './statusArchitecture'

describe('statusArchitecture', () => {
  it('lists 15 probeable microservices including sandbox and oa', () => {
    expect(countProbeable(SERVICE_DEFS)).toBe(15)
    const names = SERVICE_DEFS.map((d) => d.name)
    expect(names).toContain('Sandbox Service')
    expect(names).toContain('OA')
  })

  it('places OA, Finance and MCP capability on L4 domain lane', () => {
    const domain = domainServices(SERVICE_DEFS)
    expect(domain.map((d) => d.name)).toEqual(['OA', 'Finance', 'MCP'])
    expect(domain.filter((d) => d.gatewayPath).map((d) => d.name).sort()).toEqual(['Finance', 'OA'])
    const mcp = domain.find((d) => d.name === 'MCP')
    expect(mcp?.gatewayPath).toBeUndefined()
    expect(mcp?.description).toMatch(/已接入|Catalog/)
  })

  it('keeps Desensitize as platform root (not domain)', () => {
    const roots = platformRoots(SERVICE_DEFS)
    expect(roots.some((r) => r.name === 'Desensitize')).toBe(true)
    expect(roots.some((r) => r.name === 'OA')).toBe(false)
    expect(domainServices(SERVICE_DEFS).some((d) => d.name === 'Desensitize')).toBe(false)
  })

  it('infra strip is display-only', () => {
    expect(INFRA_ITEMS.length).toBeGreaterThanOrEqual(5)
    expect(INFRA_ITEMS).toEqual(
      expect.arrayContaining(['Nacos', 'Redis', 'MySQL', 'Milvus', 'ES', 'RocketMQ', 'MinIO']),
    )
  })

  it('buildServiceList defaults probeable to checking and MCP to external', () => {
    const list = buildServiceList(SERVICE_DEFS)
    expect(list.filter((s) => s.gatewayPath).every((s) => s.status === 'checking')).toBe(true)
    expect(list.find((s) => s.name === 'MCP')?.status).toBe('external')
  })
})
