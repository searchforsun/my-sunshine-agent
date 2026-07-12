package com.sunshine.workflow.dto;

import java.util.List;

public record WorkflowPlanValidateResponse(boolean valid, List<String> issues) {
}
