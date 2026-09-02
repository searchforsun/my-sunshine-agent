package com.sunshine.skill.storage;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/** Skill 文件读写公共逻辑 */
public final class SkillFileCodec {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".md", ".txt", ".json", ".yaml", ".yml", ".py", ".sh", ".bash", ".sql", ".xml",
            ".html", ".htm", ".css", ".js", ".ts", ".jsx", ".tsx", ".csv", ".toml", ".ini",
            ".properties", ".env", ".bat", ".ps1", ".java", ".svg", ".gitignore", ".mdc"
    );

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico"
    );

    private SkillFileCodec() {
    }

    public static boolean isTextFile(String name) {
        String lower = lowerName(name);
        if ("skill.md".equals(lower)) {
            return true;
        }
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return TEXT_EXTENSIONS.contains(lower.substring(dot));
    }

    static boolean isImageFile(String name) {
        String lower = lowerName(name);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(lower.substring(dot));
    }

    static String guessContentType(String name) {
        String lower = lowerName(name);
        if (lower.endsWith(".md") || "skill.md".equals(lower)) {
            return "text/markdown";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "text/yaml";
        }
        if (lower.endsWith(".py")) {
            return "text/x-python";
        }
        if (lower.endsWith(".sh") || lower.endsWith(".bash")) {
            return "text/x-shellscript";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".css")) {
            return "text/css";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "text/plain";
    }

    static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static String lowerName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.toLowerCase(Locale.ROOT);
    }
}
