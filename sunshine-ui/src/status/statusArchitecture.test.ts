import { describe, expect, it } from 'vitest'
import {
  INFRA_ITEMS,
  SERVICE_DEFS,
  buildServiceList,
  countProbeable,
  domainAccess,
  domainServices,
  domainToolApps,
  platformRoots,
} from './statusArchitecture'

describe('statusArchitecture', () => {
  it('lists probeable microservices including sandbox and biz-simulator', () => {
    expect(countProbeable(SERVICE_DEFS)).toBe(11)
    const names = SERVICE_DEFS.map((d) => d.name)
    expect(names).toContain('Sandbox Service')
    expect(names).toContain('Biz Simulator')
  })

  it('places Biz Simulator on L4 domain apps row and MCP on access row', () => {
    expect(domainToolApps(SERVICE_DEFS).map((d) => d.name)).toEqual(['Biz Simulator'])
    expect(domainAccess(SERVICE_DEFS).map((d) => d.name)).toEqual(['MCP'])
    expect(domainServices(SERVICE_DEFS).map((d) => d.name)).toEqual(['Biz Simulator', 'MCP'])
    const mcp = domainAccess(SERVICE_DEFS)[0]
    expect(mcp?.gatewayPath).toBeUndefined()
    expect(mcp?.description).toMatch(/已接入|Catalog/)
  })

  it('keeps Desensitize inside Resource Manager (not domain)', () => {
    const roots = platformRoots(SERVICE_DEFS)
    expect(roots.some((r) => r.name === 'Resource Manager')).toBe(true)
    expect(roots.some((r) => r.name === 'Biz Simulator')).toBe(false)
    expect(domainServices(SERVICE_DEFS).some((d) => d.name === 'Resource Manager')).toBe(false)
    expect(roots.find((r) => r.name === 'Resource Manager')?.description).toMatch(/Desensitize/)
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
