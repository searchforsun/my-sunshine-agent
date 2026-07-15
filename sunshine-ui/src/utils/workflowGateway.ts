/** BPMN 风格网关节点（路由专用，非业务节点） */
export const WORKFLOW_GATEWAY_TYPES = ['parallel-gateway', 'join', 'exclusive-gateway'] as const
export type WorkflowGatewayType = (typeof WORKFLOW_GATEWAY_TYPES)[number]

export function isGatewayType(type: string | undefined | null): type is WorkflowGatewayType {
  return WORKFLOW_GATEWAY_TYPES.includes(type as WorkflowGatewayType)
}

export function isLoopType(type: string | undefined | null): boolean {
  return type === 'loop'
}

export function isParallelForkGateway(type: string | undefined | null): boolean {
  return type === 'parallel-gateway'
}

export function isParallelMergeGateway(type: string | undefined | null): boolean {
  return type === 'join'
}

export function isExclusiveGateway(type: string | undefined | null): boolean {
  return type === 'exclusive-gateway'
}

export function defaultGatewayDisplayName(type: WorkflowGatewayType): string {
  switch (type) {
    case 'parallel-gateway':
      return '并行分叉'
    case 'join':
      return '并行汇总'
    case 'exclusive-gateway':
      return '条件分支'
    default:
      return type
  }
}

export function gatewayNodeIdPrefix(type: WorkflowGatewayType): string {
  switch (type) {
    case 'parallel-gateway':
      return 'pg'
    case 'exclusive-gateway':
      return 'xg'
    default:
      return 'join'
  }
}
