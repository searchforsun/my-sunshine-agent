package com.sunshine.orchestrator.config;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

public final class ReactiveBlocking {

    private ReactiveBlocking() {
    }

    public static <T> Mono<T> call(Callable<T> callable) {
        return Mono.fromCallable(callable).subscribeOn(Schedulers.boundedElastic());
    }

    public static Mono<Void> run(Runnable runnable) {
        return call(() -> {
            runnable.run();
            return null;
        }).then();
    }
}
