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

    /** 勿用 fromCallable(null).then() — WebFlux DELETE 会永久挂起 */
    public static Mono<Void> run(Runnable runnable) {
        return Mono.fromRunnable(runnable)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
