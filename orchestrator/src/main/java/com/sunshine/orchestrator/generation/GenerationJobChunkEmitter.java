package com.sunshine.orchestrator.generation;

import com.sunshine.orchestrator.agent.ProcessingStep;
import com.sunshine.orchestrator.agent.ProcessingStepLifecycleOps;
import com.sunshine.orchestrator.agent.ProcessingStepMerger;
import com.sunshine.orchestrator.agent.ProcessingStepSerde;
import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.StreamChunkSplitter;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.conversation.GenerationFlushScheduler;
import com.sunshine.orchestrator.processing.ContentBlockAccumulator;
import com.sunshine.orchestrator.processing.ThinkStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** GenerationJob SSE 分片写入 Redis / MySQL 缓冲 */
@Slf4j
final class GenerationJobChunkEmitter {

    private final String generationId;
    private final String messageId;
    private final AtomicLong seq;
    /** 与 {@link GenerationJob} 共享：串行 seq++ + XADD（工具并行语义不变） */
    private final Object streamAppendLock;
    private final AtomicBoolean finished;
    private final long boundStreamEpoch;
    private final List<ProcessingStep> stepsBuffer;
    private final ThinkStepMapper thinkMapper;
    private final ContentBlockAccumulator contentBlockAccumulator;
    private volatile StringBuilder reasoningBufferRef;
    private final GenerationStreamService streamService;
    private final GenerationFlushScheduler flushScheduler;
    private final GenerationProperties properties;

    GenerationJobChunkEmitter(
            String generationId,
            String messageId,
            AtomicLong seq,
            Object streamAppendLock,
            AtomicBoolean finished,
            long boundStreamEpoch,
            List<ProcessingStep> stepsBuffer,
            ThinkStepMapper thinkMapper,
            ContentBlockAccumulator contentBlockAccumulator,
            StringBuilder reasoningBufferRef,
            GenerationStreamService streamService,
            GenerationFlushScheduler flushScheduler,
            GenerationProperties properties) {
        this.generationId = generationId;
        this.messageId = messageId;
        this.seq = seq;
        this.streamAppendLock = streamAppendLock;
        this.finished = finished;
        this.boundStreamEpoch = boundStreamEpoch;
        this.stepsBuffer = stepsBuffer;
        this.thinkMapper = thinkMapper;
        this.contentBlockAccumulator = contentBlockAccumulator;
        this.reasoningBufferRef = reasoningBufferRef;
        this.streamService = streamService;
        this.flushScheduler = flushScheduler;
        this.properties = properties;
    }

    void setReasoningBufferRef(StringBuilder reasoningBufferRef) {
        this.reasoningBufferRef = reasoningBufferRef;
    }

    void onChunk(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        for (StreamToken t : applyLoopBodyFold(token)) {
            onChunkUnfolded(t, mysqlBuffer, flushPartial, lastFlush);
        }
    }

    private void onChunkUnfolded(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        if (token.isSandboxSession()) {
            emitMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        if (token.isStep() || token.isStepDelta() || token.isContentStart() || token.isContentEnd()) {
            if (token.isStep()) {
                thinkMapper.syncExternalStep(token.step());
            }
            emitMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        if (token.isContent() && token.segmentId() != null) {
            emitMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        for (StreamToken mapped : thinkMapper.map(token)) {
            emitMappedChunk(mapped, mysqlBuffer, flushPartial, lastFlush);
        }
    }

    /**
     * HITL / ReAct 旁路 token。须写入与 {@link #onChunk} 同一 {@code mysqlBuffer}，
     * 否则正文只进 content_blocks、content 为空，L1 会丢弃 assistant 轮次。
     */
    void emitStreamToken(StreamToken token, StringBuilder mysqlBuffer, Consumer<String> flushPartial) {
        if (token == null || finished.get() || !isStreamEpochValid()) {
            return;
        }
        StringBuilder buf = mysqlBuffer != null ? mysqlBuffer : new StringBuilder();
        Consumer<String> flush = flushPartial != null ? flushPartial : s -> { };
        AtomicLong lastFlush = new AtomicLong(0);
        for (StreamToken t : applyLoopBodyFold(token)) {
            if (t.isStep()) {
                thinkMapper.syncExternalStep(t.step());
            }
            emitMappedChunk(t, buf, flush, lastFlush);
        }
    }

    private List<StreamToken> applyLoopBodyFold(StreamToken token) {
        if (token == null) {
            return List.of();
        }
        var fold = StepEventBridge.loopBodyFold(messageId);
        if (fold == null) {
            return List.of(token);
        }
        List<StreamToken> out = fold.apply(token);
        return out != null ? out : List.of();
    }

    void emitPausedStep(StreamToken token, StringBuilder mysqlBuffer, Consumer<String> flushPartial) {
        emitMappedChunk(token, mysqlBuffer != null ? mysqlBuffer : new StringBuilder(),
                flushPartial, new AtomicLong(0));
    }

    void emitFinishSteps(boolean streamFailed, StringBuilder mysqlBuffer, Consumer<String> flushPartial) {
        if (thinkMapper == null) {
            return;
        }
        AtomicLong lastFlush = new AtomicLong(0);
        for (StreamToken token : thinkMapper.finish(streamFailed)) {
            emitMappedChunk(token, mysqlBuffer != null ? mysqlBuffer : new StringBuilder(),
                    flushPartial, lastFlush);
        }
    }

    void maybeFlushStepsForAwaitingInteraction() {
        boolean awaiting = stepsBuffer.stream().anyMatch(ProcessingStepLifecycleOps::isAwaitingInteractionStep);
        if (!awaiting) {
            return;
        }
        flushScheduler.flushStepsPartial(messageId, ProcessingStepSerde.toPersistJson(stepsBuffer));
    }

    private void emitMappedChunk(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        if (!isStreamEpochValid()) {
            return;
        }
        int maxChars = properties.maxChunkChars();
        if (maxChars <= 0) {
            emitSingleMappedChunk(token, mysqlBuffer, flushPartial, lastFlush);
            return;
        }
        for (StreamToken piece : StreamChunkSplitter.splitToken(token, maxChars)) {
            emitSingleMappedChunk(piece, mysqlBuffer, flushPartial, lastFlush);
        }
    }

    private void emitSingleMappedChunk(StreamToken token, StringBuilder mysqlBuffer,
            Consumer<String> flushPartial, AtomicLong lastFlush) {
        // 与 GenerationJob.emitOutbound 同锁：分配 seq 与 XADD 原子有序，工具并行只等写流
        synchronized (streamAppendLock) {
            long nextSeq = seq.incrementAndGet();
            if (token.isSandboxSession()) {
                streamService.appendChunk(generationId, nextSeq,
                        flushScheduler.metaSandboxSession(token.text(), token.channel(), token.stepId()));
                return;
            }
            if (token.isStep()) {
                ProcessingStepMerger.upsert(stepsBuffer, token.step());
                maybeFlushStepsForAwaitingInteraction();
                streamService.appendChunk(generationId, nextSeq, flushScheduler.metaStep(token.step()));
                return;
            }
            if (token.isStepDelta()) {
                ProcessingStepMerger.applyDelta(
                        stepsBuffer, token.stepId(), token.channel(), token.text());
                streamService.appendChunk(generationId, nextSeq, flushScheduler.metaStepDelta(
                        token.stepId(), token.channel(), token.text()));
                if ("reasoning".equals(token.channel()) && reasoningBufferRef != null
                        && (token.stepId() == null || !token.stepId().startsWith("node-"))) {
                    reasoningBufferRef.append(token.text());
                }
                return;
            }
            if (token.isContentStart()) {
                contentBlockAccumulator.onContentStart(token);
                streamService.appendChunk(generationId, nextSeq,
                        flushScheduler.metaContentStart(
                                token.segmentId(), token.afterStepId(), token.scopeNodeStepId()));
                return;
            }
            if (token.isContentEnd()) {
                contentBlockAccumulator.onContentEnd(token);
                streamService.appendChunk(generationId, nextSeq,
                        flushScheduler.metaContentEnd(token.segmentId(), token.scopeNodeStepId()));
                return;
            }
            String wire = token.isContent()
                    ? (token.segmentId() != null
                    ? flushScheduler.metaContentInSegment(
                            token.segmentId(), token.text(), token.scopeNodeStepId())
                    : flushScheduler.metaContent(token.text(), token.afterStepId()))
                    : flushScheduler.metaReasoning(token.text());
            streamService.appendChunk(generationId, nextSeq, wire);
            if (token.isReasoning()) {
                if (reasoningBufferRef != null) {
                    reasoningBufferRef.append(token.text());
                }
                return;
            }
            if (token.isContent() && token.segmentId() != null) {
                contentBlockAccumulator.onContent(token);
            }
            if (StringUtils.hasText(token.scopeNodeStepId())) {
                throttlePartialFlush(mysqlBuffer, flushPartial, lastFlush);
                return;
            }
            if (token.isContent() && token.text() != null) {
                mysqlBuffer.append(token.text());
            }
            throttlePartialFlush(mysqlBuffer, flushPartial, lastFlush);
        }
    }

    private void throttlePartialFlush(StringBuilder mysqlBuffer, Consumer<String> flushPartial, AtomicLong lastFlush) {
        long now = System.currentTimeMillis();
        if (now - lastFlush.get() >= properties.flushIntervalMs()) {
            lastFlush.set(now);
            flushPartial.accept(mysqlBuffer.toString());
        }
    }

    private boolean isStreamEpochValid() {
        return boundStreamEpoch >= 0
                && StepEventBridge.isStreamEpochValid(messageId, boundStreamEpoch);
    }
}
