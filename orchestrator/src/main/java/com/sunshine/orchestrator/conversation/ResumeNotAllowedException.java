package com.sunshine.orchestrator.conversation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class ResumeNotAllowedException extends RuntimeException {

    public ResumeNotAllowedException(String message) {
        super(message);
    }
}
