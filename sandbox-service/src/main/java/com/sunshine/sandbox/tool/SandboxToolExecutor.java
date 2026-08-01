package com.sunshine.sandbox.tool;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.FixedErrorCode;
import com.sunshine.common.sandbox.EditDiffBuilder;
import com.sunshine.common.sandbox.ToolInvokeResponse;
import com.sunshine.sandbox.config.SandboxProperties;
import com.sunshine.sandbox.docker.DockerCli;
import com.sunshine.sandbox.docker.ExecResult;
import com.sunshine.sandbox.docker.SandboxInvocationRegistry;
import com.sunshine.sandbox.exception.SandboxErrorCode;
import com.sunshine.sandbox.jail.PathJail;
import com.sunshine.sandbox.metrics.SandboxMetrics;
import com.sunshine.sandbox.session.SandboxSession;
import com.sunshine.sandbox.session.SandboxSessionStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SandboxToolExecutor {

    static final int DEFAULT_MAX_CHARS = 200_000;
    static final int MAX_GREP_HITS = 200;

    private final SandboxSessionStore store;
    private final DockerCli dockerCli;
    private final SandboxProperties properties;
    private final SandboxMetrics metrics;
    private final SandboxInvocationRegistry invocationRegistry;

    public ToolInvokeResponse invoke(String sessionId, String name, Map<String, Object> body) {
        return invoke(sessionId, name, body, null);
    }

    public ToolInvokeResponse invoke(
            String sessionId, String name, Map<String, Object> body, String invocationId) {
        long startNanos = System.nanoTime();
        String tool = name != null ? name : "unknown";
        String invId = StringUtils.hasText(invocationId) ? invocationId.strip() : null;
        try {
            ToolInvokeResponse response = doInvoke(sessionId, name, body, invId);
            recordMetrics(tool, response != null && response.ok(), startNanos);
            return response;
        } catch (RuntimeException e) {
            recordMetrics(tool, false, startNanos);
            throw e;
        } finally {
            if (invId != null
                    && (SandboxToolNames.GLOB.equals(name) || SandboxToolNames.GREP.equals(name))) {
                invocationRegistry.unbind(invId);
            }
        }
    }

    private void recordMetrics(String tool, boolean ok, long startNanos) {
        if (metrics != null) {
            metrics.recordToolInvoke(tool, ok, startNanos);
        }
    }

    private ToolInvokeResponse doInvoke(
            String sessionId, String name, Map<String, Object> body, String invocationId) {
        SandboxSession session = store.get(sessionId)
                .orElseThrow(() -> new BizException(SandboxErrorCode.SESSION_NOT_FOUND));
        Map<String, Object> args = body != null ? body : Map.of();
        return switch (name) {
            case SandboxToolNames.READ -> read(session, args);
            case SandboxToolNames.WRITE -> write(session, args);
            case SandboxToolNames.EDIT -> edit(session, args);
            case SandboxToolNames.GLOB -> glob(session, args, invocationId);
            case SandboxToolNames.GREP -> grep(session, args, invocationId);
            case SandboxToolNames.EXEC -> exec(session, args, invocationId);
            case SandboxToolNames.WEBFETCH -> webfetch(args);
            case SandboxToolNames.WEBSEARCH -> websearch(args);
            case null, default -> throw new BizException(SandboxErrorCode.TOOL_UNKNOWN);
        };
    }

    private ToolInvokeResponse exec(SandboxSession session, Map<String, Object> args, String invocationId) {
        String command = requireString(args, "command");
        String mode = session.policy() != null ? session.policy().kind() : "chat";
        String blocked = SandboxExecGuard.denyReason(command, mode);
        if (blocked != null) {
            throw detailError(SandboxErrorCode.EXEC_BLOCKED,
                    "command blocked: " + blocked + "; command=" + clip(command, 120));
        }
        Path cwd;
        try {
            cwd = PathJail.resolveCwd(optionalString(args, "cwd"));
        } catch (IllegalArgumentException e) {
            throw badPath(e.getMessage());
        }
        int timeoutSec = resolveTimeoutSec(session, asInteger(args.get("timeout_sec")));
        ExecResult result = dockerCli.exec(
                session.containerName(),
                cwd.toString(),
                List.of("sh", "-lc", command),
                Duration.ofSeconds(timeoutSec),
                session.sessionId(),
                invocationId);
        String output = combineOutput(result.stdout(), result.stderr());
        if (result.exitCode() == -1 && output.toLowerCase().contains("cancelled")) {
            return new ToolInvokeResponse(false, "cancelled", -1, Map.of("cancelled", true));
        }
        if (result.exitCode() == -1 && output.toLowerCase().contains("timeout")) {
            return new ToolInvokeResponse(false, output, -1, Map.of());
        }
        return new ToolInvokeResponse(result.exitCode() == 0, output, result.exitCode(), Map.of());
    }

    private int resolveTimeoutSec(SandboxSession session, Integer override) {
        if (override != null && override > 0) {
            return override;
        }
        Integer policySec = session.policy() != null ? session.policy().timeoutSec() : null;
        if (policySec != null && policySec > 0) {
            return policySec;
        }
        return properties.getDocker().getDefaultTimeoutSec();
    }

    private static String combineOutput(String stdout, String stderr) {
        String out = stdout != null ? stdout : "";
        String err = stderr != null ? stderr : "";
        if (err.isEmpty()) {
            return out;
        }
        if (out.isEmpty()) {
            return err;
        }
        return out + err;
    }

    /** 抓取网页正文（宿主侧 HTTP；SSRF 防护 + 超时 + 截断） */
    private ToolInvokeResponse webfetch(Map<String, Object> args) {
        String url = requireString(args, "url").strip();
        Integer maxChars = asInteger(args.get("max_chars"));
        int limit = maxChars != null && maxChars > 0 ? Math.min(maxChars, DEFAULT_MAX_CHARS) : DEFAULT_MAX_CHARS;
        URI uri;
        try {
            uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return new ToolInvokeResponse(false,
                        "仅支持 http/https URL: " + clip(url, 120), null, Map.of());
            }
        } catch (IllegalArgumentException e) {
            return new ToolInvokeResponse(false, "URL 无效: " + clip(url, 120), null, Map.of());
        }
        String blockReason = blockedNetworkTarget(uri);
        if (blockReason != null) {
            return new ToolInvokeResponse(false, blockReason, null, Map.of());
        }
        try {
            HttpResponse<String> resp = httpGet(uri);
            int status = resp.statusCode();
            if (status < 200 || status >= 400) {
                return new ToolInvokeResponse(false,
                        "HTTP " + status + " 请求失败: " + clip(url, 120), status, Map.of());
            }
            String body = resp.body() != null ? resp.body() : "";
            String text = toPlainText(body);
            Map<String, Object> meta = new HashMap<>();
            meta.put("url", url);
            meta.put("status", status);
            meta.put("contentType", resp.headers().firstValue("Content-Type").orElse(""));
            boolean truncated = text.length() > limit;
            if (truncated) {
                text = text.substring(0, limit);
                meta.put("truncated", true);
            }
            return new ToolInvokeResponse(true, text, status, meta);
        } catch (IOException e) {
            return new ToolInvokeResponse(false, "抓取失败: " + e.getMessage(), null, Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolInvokeResponse(false, "抓取被中断", null, Map.of());
        }
    }

    /** 网页搜索（宿主侧 Bing；解析标题/URL/摘要） */
    private ToolInvokeResponse websearch(Map<String, Object> args) {
        String query = requireString(args, "query").strip();
        Integer countOpt = asInteger(args.get("count"));
        int count = countOpt != null && countOpt > 0 ? Math.min(countOpt, 10) : 5;
        try {
            String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI uri = URI.create("https://cn.bing.com/search?q=" + enc + "&count=" + count + "&setlang=zh-CN");
            HttpResponse<String> resp = httpGet(uri);
            int status = resp.statusCode();
            if (status < 200 || status >= 400) {
                return new ToolInvokeResponse(false, "HTTP " + status + " 搜索失败", status, Map.of());
            }
            List<String> results = parseBingResults(resp.body() != null ? resp.body() : "", count);
            if (results.isEmpty()) {
                return new ToolInvokeResponse(false, "未找到与「" + clip(query, 120) + "」相关的结果，请调整关键词重试",
                        null, Map.of("query", query));
            }
            String output = String.join("\n\n", results);
            Map<String, Object> meta = new HashMap<>();
            meta.put("query", query);
            meta.put("count", results.size());
            return new ToolInvokeResponse(true, output, null, meta);
        } catch (IOException e) {
            return new ToolInvokeResponse(false, "搜索失败: " + e.getMessage(), null, Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ToolInvokeResponse(false, "搜索被中断", null, Map.of());
        }
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpResponse<String> httpGet(URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .GET()
                .build();
        return HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** SSRF 防护：拒绝非公网目标（localhost/内网/保留/元数据地址） */
    static String blockedNetworkTarget(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "URL 缺少有效主机名: " + clip(uri.toString(), 120);
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost")
                || lower.endsWith(".localhost")
                || lower.endsWith(".local")
                || lower.equals("metadata.google.internal")
                || lower.endsWith(".internal")) {
            return "禁止访问内网/本机地址: " + host;
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (!addr.isSiteLocalAddress()
                        && !addr.isAnyLocalAddress()
                        && !addr.isLoopbackAddress()
                        && !addr.isLinkLocalAddress()
                        && !addr.isMulticastAddress()) {
                    continue;
                }
                return "禁止访问内网/本机地址: " + host + " (" + addr.getHostAddress() + ")";
            }
        } catch (UnknownHostException e) {
            return "域名解析失败: " + host;
        }
        return null;
    }

    /** HTML → 可读纯文本：去 script/style/noscript/head、去标签、解码常用实体、压缩空白 */
    static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String body = html
                .replaceAll("(?is)<head\\b[^>]*>.*?</head\\s*>", " ")
                .replaceAll("(?is)<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>", " ")
                .replaceAll("(?s)<[^>]+>", " ");
        return decodeEntities(body).replaceAll("[ \\t]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n")
                .strip();
    }

    /** 解码 HTML 实体：命名常用 + 十进制/十六进制数字实体 */
    static String decodeEntities(String text) {
        if (text == null || text.isBlank() || !text.contains("&")) {
            return text;
        }
        String out = text
                .replaceAll("(?i)&nbsp;", " ")
                .replaceAll("(?i)&ensp;", " ")
                .replaceAll("(?i)&emsp;", " ")
                .replaceAll("(?i)&amp;", "&")
                .replaceAll("(?i)&lt;", "<")
                .replaceAll("(?i)&gt;", ">")
                .replaceAll("(?i)&quot;", "\"")
                .replaceAll("(?i)&#39;", "'")
                .replaceAll("(?i)&apos;", "'")
                .replaceAll("(?i)&middot;", "·")
                .replaceAll("(?i)&hellip;", "…")
                .replaceAll("(?i)&ndash;", "–")
                .replaceAll("(?i)&mdash;", "—");
        // 数字实体：&#183; &#x2026; 等
        Matcher num = Pattern.compile("&#(\\d+);").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (num.find()) {
            num.appendReplacement(sb, Matcher.quoteReplacement(numEntity(Integer.parseInt(num.group(1)))));
        }
        num.appendTail(sb);
        Matcher hex = Pattern.compile("&#x([0-9a-fA-F]+);").matcher(sb.toString());
        StringBuilder sb2 = new StringBuilder();
        while (hex.find()) {
            hex.appendReplacement(sb2, Matcher.quoteReplacement(numEntity(Integer.parseInt(hex.group(1), 16))));
        }
        hex.appendTail(sb2);
        return sb2.toString();
    }

    private static String numEntity(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    /** 解析 Bing 搜索结果：标题 / URL / 摘要 */
    static List<String> parseBingResults(String html, int max) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return out;
        }
        Matcher algo = Pattern.compile("(?s)<li class=\"b_algo\".*?</li>").matcher(html);
        int rank = 0;
        while (algo.find() && out.size() < max) {
            String block = algo.group();
            rank++;
            String h2 = firstMatch(block, "<h2[^>]*>(.*?)</h2>");
            String title = stripHtml(h2);
            String link = firstExternalLink(h2 != null ? h2 : block);
            if (title == null && link == null) {
                continue;
            }
            String snippet = stripHtml(firstMatch(block, "<p[^>]*>(.*?)</p>"));
            StringBuilder sb = new StringBuilder();
            sb.append(rank).append(". ");
            sb.append(title != null ? title : link);
            if (link != null) {
                sb.append('\n').append("   ").append(link);
            }
            if (snippet != null && !snippet.isBlank()) {
                sb.append('\n').append("   ").append(snippet);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** 提取结果块内第一个真实外链（优先 h2 内 a；过滤 Bing 站内跳转/资源地址） */
    private static String firstExternalLink(String block) {
        if (block == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?i)href=\"(https?://[^\"]+)\"").matcher(block);
        while (m.find()) {
            String url = m.group(1);
            if (isBingInternal(url)) {
                continue;
            }
            return url;
        }
        return null;
    }

    private static boolean isBingInternal(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://r.bing.com")
                || lower.startsWith("https://www.bing.com")
                || lower.startsWith("https://cn.bing.com")
                || lower.startsWith("https://cc.bingj.com")
                || lower.startsWith("https://www.msn.com");
    }

    /** 返回首个匹配的第 1 个捕获组；无匹配返回 null */
    private static String firstMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String stripHtml(String raw) {
        if (raw == null) {
            return null;
        }
        return toPlainText(raw);
    }

    private ToolInvokeResponse read(SandboxSession session, Map<String, Object> args) {
        String path = requireString(args, "path");
        Path host = toHostSafe(session, path, false);
        if (Files.isDirectory(host)) {
            throw detailError(SandboxErrorCode.NOT_A_FILE,
                    "path is a directory, not a file: " + path + "; use sandbox__glob to list");
        }
        if (!Files.isRegularFile(host)) {
            throw badPath("file not found: " + path);
        }
        try {
            String content = Files.readString(host, StandardCharsets.UTF_8);
            Integer offset = asInteger(args.get("offset"));
            Integer limit = asInteger(args.get("limit"));
            if (offset != null || limit != null) {
                content = sliceLines(content, offset, limit);
            }
            Map<String, Object> meta = new HashMap<>();
            if (content.length() > DEFAULT_MAX_CHARS) {
                content = content.substring(0, DEFAULT_MAX_CHARS);
                meta.put("truncated", true);
            }
            return new ToolInvokeResponse(true, content, null, meta.isEmpty() ? Map.of() : meta);
        } catch (IOException e) {
            throw new IllegalStateException("read failed: " + path, e);
        }
    }

    private ToolInvokeResponse write(SandboxSession session, Map<String, Object> args) {
        String path = requireString(args, "path");
        String content = args.get("content") != null ? String.valueOf(args.get("content")) : "";
        Path host = toHostSafe(session, path, true);
        if (Files.exists(host)) {
            throw detailError(SandboxErrorCode.WRITE_ALREADY_EXISTS,
                    "file already exists: " + path + "; use sandbox__edit or choose another path");
        }
        try {
            Path parent = host.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(host, content, StandardCharsets.UTF_8);
            // 短确认给 LLM（勿回传全文）；空 output 易被模型误判为失败并重复 write
            return new ToolInvokeResponse(true, "wrote " + path, null, Map.of());
        } catch (IOException e) {
            throw new IllegalStateException("write failed: " + path, e);
        }
    }

    private ToolInvokeResponse edit(SandboxSession session, Map<String, Object> args) {
        String path = requireString(args, "path");
        String oldString = requireString(args, "old_string");
        String newString = args.get("new_string") != null ? String.valueOf(args.get("new_string")) : "";
        Path host = toHostSafe(session, path, true);
        if (Files.isDirectory(host)) {
            throw detailError(SandboxErrorCode.NOT_A_FILE,
                    "path is a directory, not a file: " + path);
        }
        if (!Files.isRegularFile(host)) {
            throw badPath("file not found: " + path + "; create with sandbox__write first");
        }
        try {
            String content = Files.readString(host, StandardCharsets.UTF_8);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                throw detailError(SandboxErrorCode.EDIT_NOT_FOUND,
                        "old_string not found in " + path);
            }
            if (count != 1) {
                throw detailError(SandboxErrorCode.EDIT_NOT_UNIQUE,
                        "old_string not unique in " + path + " (matches=" + count + ")");
            }
            var built = EditDiffBuilder.tryBuild(content, oldString, newString, 3)
                    .map(d -> d.withPath(path))
                    .orElse(null);
            String updated = content.replace(oldString, newString);
            Files.writeString(host, updated, StandardCharsets.UTF_8);
            Map<String, Object> meta = new LinkedHashMap<>();
            if (built != null) {
                meta.put("editDiff", built);
            }
            // 短确认给 LLM；结构化 diff 在 meta.editDiff，勿把 hunk 灌进 tool result
            return new ToolInvokeResponse(true, "edited " + path, null, meta);
        } catch (IOException e) {
            throw new IllegalStateException("edit failed: " + path, e);
        }
    }

    private ToolInvokeResponse glob(
            SandboxSession session, Map<String, Object> args, String invocationId) {
        AtomicBoolean cancelled = invocationId != null
                ? invocationRegistry.bindFlag(session.sessionId(), invocationId) : new AtomicBoolean(false);
        String pattern = requireString(args, "pattern");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<String> hits = new ArrayList<>();
        for (SearchRoot root : resolveSearchRoots(session, optionalString(args, "path"))) {
            if (cancelled.get()) {
                return cancelledResponse();
            }
            collectGlobHits(session, root, matcher, hits, cancelled);
            if (cancelled.get()) {
                return cancelledResponse();
            }
        }
        Collections.sort(hits);
        return new ToolInvokeResponse(true, String.join("\n", hits), null, Map.of());
    }

    private ToolInvokeResponse grep(
            SandboxSession session, Map<String, Object> args, String invocationId) {
        AtomicBoolean cancelled = invocationId != null
                ? invocationRegistry.bindFlag(session.sessionId(), invocationId) : new AtomicBoolean(false);
        String patternStr = requireString(args, "pattern");
        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            throw new BizException(SandboxErrorCode.PATTERN_INVALID);
        }
        PathMatcher fileGlob = null;
        String globFilter = optionalString(args, "glob");
        if (globFilter != null) {
            fileGlob = FileSystems.getDefault().getPathMatcher("glob:" + globFilter);
        }
        List<String> hits = new ArrayList<>();
        boolean hitLimit = false;
        for (SearchRoot root : resolveSearchRoots(session, optionalString(args, "path"))) {
            if (cancelled.get()) {
                return cancelledResponse();
            }
            hitLimit = collectGrepHits(session, root, regex, fileGlob, hits, cancelled);
            if (cancelled.get()) {
                return cancelledResponse();
            }
            if (hitLimit) {
                break;
            }
        }
        Map<String, Object> meta = hitLimit ? Map.of("hitLimit", true) : Map.of();
        return new ToolInvokeResponse(true, String.join("\n", hits), null, meta);
    }

    private static ToolInvokeResponse cancelledResponse() {
        return new ToolInvokeResponse(false, "cancelled", -1, Map.of("cancelled", true));
    }

    private static void collectGlobHits(
            SandboxSession session,
            SearchRoot root,
            PathMatcher matcher,
            List<String> hits,
            AtomicBoolean cancelled) {
        if (!Files.exists(root.host()) || cancelled.get()) {
            return;
        }
        if (Files.isRegularFile(root.host())) {
            Path rel = root.walkBase().relativize(root.host());
            if (matcher.matches(rel) || matcher.matches(root.host().getFileName())) {
                hits.add(HostPathResolver.toContainer(session, root.host()));
            }
            return;
        }
        try (Stream<Path> walk = Files.walk(root.host())) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                if (cancelled.get()) {
                    return;
                }
                Path rel = root.walkBase().relativize(p);
                if (matcher.matches(rel) || matcher.matches(p.getFileName())) {
                    hits.add(HostPathResolver.toContainer(session, p));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("glob failed", e);
        }
    }

    /** @return true if hit limit reached */
    private static boolean collectGrepHits(
            SandboxSession session,
            SearchRoot root,
            Pattern regex,
            PathMatcher fileGlob,
            List<String> hits,
            AtomicBoolean cancelled) {
        if (!Files.exists(root.host()) || cancelled.get()) {
            return false;
        }
        if (Files.isRegularFile(root.host())) {
            return grepFile(session, root.host(), root.walkBase(), regex, fileGlob, hits);
        }
        try (Stream<Path> walk = Files.walk(root.host())) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path p : files) {
                if (cancelled.get()) {
                    return false;
                }
                if (grepFile(session, p, root.walkBase(), regex, fileGlob, hits)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("grep failed", e);
        }
    }

    private static boolean grepFile(
            SandboxSession session,
            Path hostFile,
            Path walkBase,
            Pattern regex,
            PathMatcher fileGlob,
            List<String> hits) {
        if (fileGlob != null) {
            Path rel = walkBase.relativize(hostFile);
            if (!fileGlob.matches(rel) && !fileGlob.matches(hostFile.getFileName())) {
                return false;
            }
        }
        String containerPath = HostPathResolver.toContainer(session, hostFile);
        try {
            List<String> lines = Files.readAllLines(hostFile, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (regex.matcher(line).find()) {
                    hits.add(containerPath + ":" + (i + 1) + ":" + line);
                    if (hits.size() >= MAX_GREP_HITS) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("grep failed: " + containerPath, e);
        }
    }

    /**
     * path 缺省：同时搜 /skills 与 /workspace；否则解析为 jail 内单根（文件或目录）。
     */
    private static List<SearchRoot> resolveSearchRoots(SandboxSession session, String pathOpt) {
        if (pathOpt == null) {
            Path skill = session.hostRoot().resolve("skills").toAbsolutePath().normalize();
            Path workspace = session.hostRoot().resolve("workspace").toAbsolutePath().normalize();
            return List.of(new SearchRoot(skill, skill), new SearchRoot(workspace, workspace));
        }
        Path host = toHostSafe(session, pathOpt, false);
        Path walkBase = host;
        if (Files.isRegularFile(host) && host.getParent() != null) {
            walkBase = host.getParent();
        }
        return List.of(new SearchRoot(host, walkBase));
    }

    private record SearchRoot(Path host, Path walkBase) {}

    private static Path toHostSafe(SandboxSession session, String containerPath, boolean forWrite) {
        try {
            return HostPathResolver.toHost(session, containerPath, forWrite);
        } catch (IllegalArgumentException e) {
            throw badPath(e.getMessage());
        }
    }

    private static BizException badPath(String detail) {
        return detailError(SandboxErrorCode.FILE_PATH_INVALID, detail);
    }

    private static BizException detailError(SandboxErrorCode code, String detail) {
        return new BizException(new FixedErrorCode(code.getCode(), code.getKey(), detail));
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String requireString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw badPath(key + " required");
        }
        return String.valueOf(v);
    }

    private static String optionalString(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        return String.valueOf(v);
    }

    private static Integer asInteger(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(v));
    }

    /** offset 为 1-based 起始行；limit 为最多行数 */
    static String sliceLines(String content, Integer offset, Integer limit) {
        List<String> lines = content.lines().toList();
        int start = offset != null && offset > 0 ? offset - 1 : 0;
        if (start >= lines.size()) {
            return "";
        }
        int end = limit != null && limit >= 0 ? Math.min(lines.size(), start + limit) : lines.size();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    static int countOccurrences(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (true) {
            int idx = content.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + needle.length();
        }
        return count;
    }
}
