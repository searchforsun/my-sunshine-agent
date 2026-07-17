package com.sunshine.sandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sandbox")
public class SandboxProperties {

    private Docker docker = new Docker();
    private Egress egress = new Egress();

    @Data
    public static class Docker {
        private String binary = "docker";
        private String hostDataRoot = "/var/lib/sunshine-sandbox";
        private String defaultImage = "sunshine-sandbox-python:3.11-slim";
        private int defaultMemoryMb = 256;
        private String defaultCpus = "0.5";
        private int defaultTimeoutSec = 30;
    }

    @Data
    public static class Egress {
        private String proxyImage = "sunshine-sandbox-egress:1.0";
        private int proxyPort = 8888;
    }
}
