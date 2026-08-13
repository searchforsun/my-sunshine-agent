package com.sunshine.orchestrator.routing;

/** 路由链路可观测：layer = mode | track | L0 | rule | L3 | final */
public record RoutingTrace(String layer, String label, String detail) {

    public static RoutingTrace of(String layer, String label, String detail) {
        return new RoutingTrace(layer, label, detail != null ? detail : "");
    }
}
