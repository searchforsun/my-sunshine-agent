package com.sunshine.orchestrator.sandbox;

import java.util.List;

/** 沙箱内置工具 ID — 不进 tool-manager Catalog */
public final class SandboxIds {

    public static final String READ = "sandbox__read";
    public static final String WRITE = "sandbox__write";
    public static final String EDIT = "sandbox__edit";
    public static final String GLOB = "sandbox__glob";
    public static final String GREP = "sandbox__grep";
    public static final String EXEC = "sandbox__exec";
    public static final List<String> ALL = List.of(READ, WRITE, EDIT, GLOB, GREP, EXEC);

    public static String rpcName(String toolId) {
        return toolId != null && toolId.startsWith("sandbox__")
                ? toolId.substring("sandbox__".length())
                : toolId;
    }

    private SandboxIds() {}
}
