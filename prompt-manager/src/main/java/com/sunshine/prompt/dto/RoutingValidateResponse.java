package com.sunshine.prompt.dto;

import java.util.List;

public record RoutingValidateResponse(List<RoutingWarningItem> warnings) {}
