package com.sunshine.rag.config;

import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 虚拟线程执行器 — 阻塞等待 / 同步 IO 类负载专用，避免占用有界平台线程。
 * 适用：ES / Milvus / LLM 同步调用（boundedElastic 语义的等价替换）。
 * 纯异步 NIO 链（SSE 透传、WebClient 响应式调用）留在 reactor 事件循环，勿迁移。
 */
public final class VirtualThreadExecutors {

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Scheduler SCHEDULER = Schedulers.fromExecutorService(EXECUTOR);
    private static final Scheduler TIMER = Schedulers.single();

    private VirtualThreadExecutors() {
    }

    /** Reactor Scheduler：等价替换 {@code Schedulers.boundedElastic()} */
    public static Scheduler scheduler() {
        return SCHEDULER;
    }

    /** 底层 JDK 虚拟线程 Executor（虚拟线程不阻塞 JVM 退出，无需显式关闭） */
    public static ExecutorService executor() {
        return EXECUTOR;
    }

    /**
     * 延迟调度：内置单线程定时器计时，到期后转投虚拟线程执行。
     * 虚拟线程 executor 非 {@code ScheduledExecutorService}，Reactor 的 {@code fromExecutorService}
     * 对延迟任务会抛 RejectedNotTimeCapable，故统一走此入口。
     */
    public static Disposable scheduleDelayed(Runnable task, long delayMs) {
        if (delayMs <= 0) {
            return SCHEDULER.schedule(task);
        }
        return TIMER.schedule(() -> SCHEDULER.schedule(task), delayMs, TimeUnit.MILLISECONDS);
    }
}
