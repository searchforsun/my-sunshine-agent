package com.sunshine.orchestrator.biz;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务上下文权威层运行参数（authority §7）— Nacos {@code agent.business-context.*}。
 * 一期主路径 = kind=chat；task 默认跳过（走 task-scene）。
 */
@Getter
@Setter
@RefreshScope
@ConfigurationProperties(prefix = "agent.business-context")
public class BusinessContextProperties {

    /** 总开关；默认关（一期企业 chat 手动打开，避免 C 端闲聊误灌）。 */
    private boolean enabled = false;

    private Task task = new Task();
    private Preference preference = new Preference();
    private ConflictCheck conflictCheck = new ConflictCheck();
    private SceneEmbedding sceneEmbedding = new SceneEmbedding();
    private SceneAuto sceneAuto = new SceneAuto();

    @Getter
    @Setter
    public static class Task {
        /** 候选池时间窗（天）：仅召回近 N 天更新的活跃任务。 */
        private int activeDays = 14;
        /** 可选极简目录宽度（id+title+status）。 */
        private int topK = 5;
        /** 同时详情条数上限（权威纪律：只装最近 1 条详情）。 */
        private int detailMax = 1;
    }

    @Getter
    @Setter
    public static class Preference {
        /** 全局白名单 key：scope=* 偏好仅这些 key 可装载。 */
        private List<String> globalKeys = new ArrayList<>();
        /** scene → 允许装载的 pref key 白名单（无条目即该场景不装载场景偏好）。 */
        private Map<String, List<String>> whitelist = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class ConflictCheck {
        /** 有 scene 且存在 Policy/活跃任务板时，对 L3 摘要做冲突仲裁（authority §5.2）；默认关。 */
        private boolean enabled = false;
        /** 仲裁输入 L3 原文长度上限（字符）；超出截断（L3 属低优先级补充材料）。 */
        private int maxL3Chars = 3000;
        /** LLM 判定失败兜底：drop=丢弃整段 L3（有权威块时低优先级安全）| keep=原样保留。 */
        private String llmFailurePolicy = "drop";
    }

    @Getter
    @Setter
    public static class SceneEmbedding {
        /** 读/写路径 embedding 回退开关（authority §2.1b/§2.1c/§5.5）；默认关。 */
        private boolean enabled = false;
        /** 采纳阈值：查询与场景 description 的余弦相似度 ≥ minScore 才采纳。 */
        private double minScore = 0.7;
        /** 返回候选数（装配与写路径均为取最高分，1 即可）。 */
        private int topK = 1;
        /** DashScope text-embedding 模型名。 */
        private String model = "text-embedding-v4";
        /** DashScope 向量维度（text-embedding-v4 = 1024）。 */
        private int dimension = 1024;
        /** DashScope Embedding API base-url。 */
        private String baseUrl =
                "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
        /** DashScope API key（生产经 Nacos 注入，默认空 = 回退禁用）。 */
        private String apiKey = "";
    }

    @Getter
    @Setter
    public static class SceneAuto {
        /** LLM 自动场景创建开关（authority §5.5b）；默认关。 */
        private boolean enabled = false;
        /** 同 tenant 的 auto 场景（pending_review + active）总数上限；超限停止创建。 */
        private int maxPending = 20;
        /** 10 分钟内同 tenant 创建次数上限（防污染）。 */
        private int createRateLimit = 3;
        /** pending_review 长期未审核的过期天数；超期由维护任务清理。 */
        private int ttlDays = 30;
        /** 与既有场景 description 余弦相似度 ≥ 该值视为重复，复用既有场景而非新建。 */
        private double similarityThreshold = 0.85;
    }
}
