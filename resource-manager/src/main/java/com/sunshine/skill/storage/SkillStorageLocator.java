package com.sunshine.skill.storage;

/** Skill 包在对象存储中的定位符：minio://{bucket}/{objectKey} */
public final class SkillStorageLocator {

    public static final String MINIO_SCHEME = "minio://";

    private SkillStorageLocator() {
    }

    public record MinioRef(String bucket, String objectKey) {
    }

    public static String minio(String bucket, String objectKey) {
        return MINIO_SCHEME + bucket + "/" + objectKey;
    }

    public static boolean isMinio(String locator) {
        return locator != null && locator.startsWith(MINIO_SCHEME);
    }

    public static MinioRef parseMinio(String locator) {
        if (!isMinio(locator)) {
            throw new IllegalArgumentException("非 MinIO 定位符: " + locator);
        }
        String rest = locator.substring(MINIO_SCHEME.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash >= rest.length() - 1) {
            throw new IllegalArgumentException("MinIO 定位符格式错误: " + locator);
        }
        return new MinioRef(rest.substring(0, slash), rest.substring(slash + 1));
    }

    public static String versionPrefix(String skillId, int version) {
        return skillId + "/" + version + "/";
    }

    public static String skillMdKey(String skillId, int version) {
        return versionPrefix(skillId, version) + "SKILL.md";
    }
}
