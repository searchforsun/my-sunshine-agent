package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 恢复续跑 + 新 spawn 子任务的 streamEpoch 对齐。
 *
 * <p>回归：resume 时 bumpStreamEpoch(msgId) 抬到 N+1，但新 spawn 的 sub bridge 在 bind 时
 * 用 bridgeId（sub-{runId}，非 streamEpoch 键）取 epoch 得 0，与 bindingEpoch(N+1) 错配，
 * isHookFlushAllowed 拒绝直刷 → 前端卡住直到子任务完成。</p>
 */
class StepEventBridgeRegistryResumeEpochTest {

    private StepEventBridgeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StepEventBridgeRegistry();
    }

    @Test
    void resume_newSubBridge_alignsSessionEpochToBumpedStreamEpoch() {
        String msgId = "msg-1";
        String mainBridge = "main-run-1";
        String subBridge = "sub-run-2";

        // 首次运行：main bind + hitl，streamEpoch[msgId]=0
        registry.bind(mainBridge, new ProcessingTimelineSession(), new ConcurrentLinkedQueue<>());
        registry.bindHitlBridge(mainBridge, msgId, true);
        registry.registerMainRun(msgId, mainBridge);
        assertThat(registry.currentStreamEpoch(msgId)).isZero();

        // 恢复续跑：bump → streamEpoch[msgId]=1
        long bumped = registry.bumpStreamEpoch(msgId);
        assertThat(bumped).isEqualTo(1L);

        // 恢复后新 spawn 子任务：先 bindHitlBridge 再 bind（SpawnSubagentTool 时序）
        registry.bindHitlBridge(subBridge, msgId, true);
        registry.bind(subBridge, new ProcessingTimelineSession(), new ConcurrentLinkedQueue<>());

        // 修复前 sessionStreamEpoch[subBridge]=0（与 bumped=1 错配）；修复后须=1
        assertThat(registry.currentStreamEpoch(msgId)).isEqualTo(1L);
        assertThat(isHookFlushAllowedFor(subBridge, msgId, bumped)).isTrue();
    }

    @Test
    void main_bridge_sessionEpoch_calibratedOnBindHitl() {
        String msgId = "msg-2";
        String mainBridge = "main-run-9";
        // main 先 bind（hitlAssistantByBridge 未注册）后 bindHitlBridge
        registry.bind(mainBridge, new ProcessingTimelineSession(), new ConcurrentLinkedQueue<>());
        registry.bumpStreamEpoch(msgId); // → 1
        registry.bindHitlBridge(mainBridge, msgId, true);
        // bindHitlBridge 校准后，main sessionEpoch 应与 bumped 对齐
        assertThat(isHookFlushAllowedFor(mainBridge, msgId, 1L)).isTrue();
    }

    /** 反射调用私有 isHookFlushAllowed（仅测试用） */
    private boolean isHookFlushAllowedFor(String bridgeId, String flushKey, long bindingEpoch) {
        try {
            var m = StepEventBridgeRegistry.class.getDeclaredMethod(
                    "isHookFlushAllowed", String.class, String.class, long.class);
            m.setAccessible(true);
            return (boolean) m.invoke(registry, bridgeId, flushKey, bindingEpoch);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
