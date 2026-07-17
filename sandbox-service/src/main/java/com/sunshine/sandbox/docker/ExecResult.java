package com.sunshine.sandbox.docker;

public record ExecResult(int exitCode, String stdout, String stderr) {}
