/** Studio / Chat 画布节点几何常量与中心点换算 */
export type WorkflowLayoutPos = { x: number; y: number; width?: number; height?: number }

export const Y_GAP = 88
export const ORIGIN_X = 48
export const ORIGIN_Y = 72
export const BUSINESS_NODE_WIDTH = 148
export const BUSINESS_NODE_HEIGHT = 56
export const ANCHOR_NODE_WIDTH = 56
export const ANCHOR_NODE_HEIGHT = 56
export const GATEWAY_NODE_WIDTH = 40
export const GATEWAY_NODE_HEIGHT = 40
export const LOOP_MIN_WIDTH = 280
export const LOOP_MIN_HEIGHT = 160
export const LOOP_PAD_X = 24
export const LOOP_PAD_TOP = 52
export const LOOP_PAD_BOTTOM = 24
export const LOOP_INNER_GAP = 40
export const SPINE_HANDLE_Y = ORIGIN_Y + BUSINESS_NODE_HEIGHT / 2

export function nodeSize(
  nodeType: string | undefined,
  layoutPos?: Pick<WorkflowLayoutPos, 'width' | 'height'> | null,
  isGateway?: (t: string) => boolean,
): { w: number; h: number } {
  if (nodeType && isGateway?.(nodeType)) {
    return { w: GATEWAY_NODE_WIDTH, h: GATEWAY_NODE_HEIGHT }
  }
  if (nodeType === 'loop') {
    return {
      w: layoutPos?.width && layoutPos.width > 0 ? layoutPos.width : LOOP_MIN_WIDTH,
      h: layoutPos?.height && layoutPos.height > 0 ? layoutPos.height : LOOP_MIN_HEIGHT,
    }
  }
  if (nodeType === 'start' || nodeType === 'answer') {
    return { w: ANCHOR_NODE_WIDTH, h: ANCHOR_NODE_HEIGHT }
  }
  return { w: BUSINESS_NODE_WIDTH, h: BUSINESS_NODE_HEIGHT }
}

export function nodeCenterX(
  pos: WorkflowLayoutPos,
  nodeType: string | undefined,
  isGateway?: (t: string) => boolean,
): number {
  return pos.x + nodeSize(nodeType, pos, isGateway).w / 2
}

export function nodeCenterY(
  pos: WorkflowLayoutPos,
  nodeType: string | undefined,
  isGateway?: (t: string) => boolean,
): number {
  return pos.y + nodeSize(nodeType, pos, isGateway).h / 2
}

export function positionFromCenter(
  centerX: number,
  centerY: number,
  nodeType: string | undefined,
  layoutPos?: Pick<WorkflowLayoutPos, 'width' | 'height'> | null,
  isGateway?: (t: string) => boolean,
): WorkflowLayoutPos {
  const { w, h } = nodeSize(nodeType, layoutPos, isGateway)
  return { x: centerX - w / 2, y: centerY - h / 2, width: layoutPos?.width, height: layoutPos?.height }
}

export function measureLoopSize(bodyCount: number): { width: number; height: number } {
  if (bodyCount <= 0) {
    return { width: LOOP_MIN_WIDTH, height: LOOP_MIN_HEIGHT }
  }
  const innerStep = BUSINESS_NODE_WIDTH + LOOP_INNER_GAP
  const width = Math.max(
    LOOP_MIN_WIDTH,
    LOOP_PAD_X + (bodyCount - 1) * innerStep + BUSINESS_NODE_WIDTH + LOOP_PAD_X,
  )
  const height = Math.max(LOOP_MIN_HEIGHT, LOOP_PAD_TOP + BUSINESS_NODE_HEIGHT + LOOP_PAD_BOTTOM)
  return { width, height }
}
