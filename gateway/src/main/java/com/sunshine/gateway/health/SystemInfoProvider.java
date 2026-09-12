package com.sunshine.gateway.health;

import com.sun.management.OperatingSystemMXBean;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宿主机基本资源信息采集：CPU / 物理内存 / 磁盘 / 系统与 JVM。
 * <p>用于前端「系统状态」页的服务器信息卡片。所有服务共享同一宿主机，
 * 因此收集一次即可代表整个平台所在物理机。</p>
 */
final class SystemInfoProvider {

    private SystemInfoProvider() {
    }

    static Map<String, Object> collect() {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("cores", os.getAvailableProcessors());
        cpu.put("systemLoad", round(os.getCpuLoad()));
        cpu.put("processLoad", round(os.getProcessCpuLoad()));
        cpu.put("processCpuTimeNanos", os.getProcessCpuTime());
        cpu.put("loadAverage", os.getSystemLoadAverage());

        Map<String, Object> memory = new LinkedHashMap<>();
        long memoryTotal = os.getTotalMemorySize();
        long memoryFree = os.getFreeMemorySize();
        memory.put("total", memoryTotal);
        memory.put("used", Math.max(0, memoryTotal - memoryFree));
        memory.put("free", memoryFree);
        memory.put("committedVirtual", os.getCommittedVirtualMemorySize());
        memory.put("swapTotal", os.getTotalSwapSpaceSize());
        memory.put("swapUsed", Math.max(0, os.getTotalSwapSpaceSize() - os.getFreeSwapSpaceSize()));

        Map<String, Object> disk = new LinkedHashMap<>();
        File root = new File("/");
        long rootTotal = root.getTotalSpace();
        long rootUsable = root.getUsableSpace();
        disk.put("total", rootTotal);
        disk.put("free", rootUsable);
        disk.put("used", Math.max(0, rootTotal - rootUsable));

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("name", System.getProperty("java.vm.name"));
        jvm.put("version", System.getProperty("java.version"));
        jvm.put("uptimeMillis", runtime.getUptime());
        jvm.put("startTimeMillis", runtime.getStartTime());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hostname", hostname());
        data.put("osName", System.getProperty("os.name"));
        data.put("osVersion", System.getProperty("os.version"));
        data.put("osArch", System.getProperty("os.arch"));
        data.put("capturedAt", System.currentTimeMillis());
        data.put("cpu", cpu);
        data.put("memory", memory);
        data.put("disk", disk);
        data.put("jvm", jvm);
        return data;
    }

    /** CPU 负载为 0..1，度量不可用时为 -1；仅保留 4 位小数避免前端抖动。 */
    private static double round(double v) {
        if (v < 0) return -1;
        return Math.round(v * 10000) / 10000.0;
    }

    private static String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return System.getenv().getOrDefault("HOSTNAME", "unknown");
        }
    }
}
