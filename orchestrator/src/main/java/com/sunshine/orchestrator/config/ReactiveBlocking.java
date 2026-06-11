package com.sunshine.orchestrator.config;

import com.sunshine.orchestrator.conversation.ConversationNotFoundException;
import com.sunshine.orchestrator.conversation.ResumeNotAllowedException;
import com.sunshine.orchestrator.generation.GenerationGoneException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;

public final class ReactiveBlocking {

    private ReactiveBlocking() {
    }

    public static <T> Mono<T> call(Callable<T> callable) {
        return Mono.fromCallable(callable)
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(ConversationNotFoundException.class,
                        e -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e))
                .onErrorMap(ResumeNotAllowedException.class,
                        e -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e))
                .onErrorMap(GenerationGoneException.class,
                        e -> new ResponseStatusException(HttpStatus.GONE, e.getMessage(), e));
    }

    public static Mono<Void> run(Runnable runnable) {
        return call(() -> {
            runnable.run();
            return null;
        }).then();
    }
}
