package com.sunshine.rag.admin.config;

import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;

public class PublishGateException extends RuntimeException {

    private final SmokeEvalResult evalResult;

    public PublishGateException(SmokeEvalResult evalResult) {
        super("publish gate failed: recall@5=" + evalResult.recallAt5()
                + " baseline=" + evalResult.baselineRecallAt5());
        this.evalResult = evalResult;
    }

    public SmokeEvalResult evalResult() {
        return evalResult;
    }
}
