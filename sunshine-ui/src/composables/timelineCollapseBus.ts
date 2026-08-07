import { ref } from 'vue'

/**
 * 运行中时间线展开态：最后一个 live OperationStack（ChatView 传入 collapseTick 的实例）上报，
 * ChatView 据此在滚动底部显示「折叠运行过程」气泡。
 * 用共享 ref 而非组件实例引用，避免流式渲染期间函数式 ref 反复重绑导致的性能开销。
 */
export const liveTimelineExpanded = ref(false)
