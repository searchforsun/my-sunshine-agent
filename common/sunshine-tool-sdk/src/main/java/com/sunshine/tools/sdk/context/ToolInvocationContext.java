package com.sunshine.tools.sdk.context;

/**
 * 工具调用身份上下文：由 Controller 从 Gateway 头注入，工具实现内读取。
 * 身份只认 x-user-id / x-tenant-id，禁止从 LLM 参数冒充。
 */
public final class ToolInvocationContext {

    private static final ThreadLocal<Identity> HOLDER = new ThreadLocal<>();

    private ToolInvocationContext() {
    }

    public record Identity(String tenantId, String userId) {
    }

    /**
     * 设置当前调用身份。tenant 空白时归一为 {@code default}；user 可为 null/空白（调用方再 require）。
     */
    public static void set(String tenantId, String userId) {
        String tenant = blankToNull(tenantId) == null ? "default" : tenantId.trim();
        String user = blankToNull(userId);
        HOLDER.set(new Identity(tenant, user));
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前用户 ID；未设置或空白时抛 {@link IllegalStateException}。 */
    public static String requireUserId() {
        Identity identity = HOLDER.get();
        if (identity == null || blankToNull(identity.userId()) == null) {
            throw new IllegalStateException("ToolInvocationContext: x-user-id is required");
        }
        return identity.userId();
    }

    /** 当前租户 ID；未设置时返回 {@code default}。 */
    public static String tenantIdOrDefault() {
        Identity identity = HOLDER.get();
        if (identity == null || blankToNull(identity.tenantId()) == null) {
            return "default";
        }
        return identity.tenantId();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
