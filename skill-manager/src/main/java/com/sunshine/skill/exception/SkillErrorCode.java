package com.sunshine.skill.exception;

import com.sunshine.common.core.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** skill-manager 业务错误码 */
@Getter
@RequiredArgsConstructor
public enum SkillErrorCode implements ErrorCode {

    SKILL_NOT_FOUND(404, "skill_not_found", "Skill 不存在"),
    SKILL_NOT_ENABLED(404, "skill_not_enabled", "Skill 不存在或未启用"),
    VERSION_NOT_FOUND(404, "skill_version_not_found", "Skill 版本不存在"),
    FILE_NOT_FOUND(404, "skill_file_not_found", "文件不存在"),
    ID_DISPLAY_NAME_REQUIRED(400, "skill_id_display_name_required", "Skill 标识与显示名称不能为空"),
    DISPLAY_NAME_REQUIRED(400, "skill_display_name_required", "显示名称不能为空"),
    SKILL_ALREADY_EXISTS(409, "skill_already_exists", "Skill 已存在"),
    DRAFT_ALREADY_EXISTS(409, "skill_draft_already_exists", "已有草稿版本，请先发布或删除后再操作"),
    SOURCE_PACKAGE_MISSING(400, "skill_source_package_missing", "源版本无 Skill 包，无法复制"),
    PACKAGE_COPY_FAILED(500, "skill_package_copy_failed", "复制 Skill 包失败"),
    PACKAGE_REQUIRED(400, "skill_package_required", "请先上传 Skill 包"),
    PUBLISH_PACKAGE_REQUIRED(400, "skill_publish_package_required", "发布前请先上传 Skill 包"),
    LAST_VERSION_DELETE_FORBIDDEN(400, "skill_last_version_delete_forbidden", "仅剩一个版本，请删除整个 Skill"),
    ENABLE_REQUIRES_PUBLISHED(400, "skill_enable_requires_published", "请先发布并生效某一版本后再开启"),
    SKILL_NAME_MISMATCH(400, "skill_name_mismatch", "SKILL.md 中的 name 与 Skill 标识不一致"),
    PATH_REQUIRED(400, "skill_path_required", "文件路径不能为空"),
    CONTENT_REQUIRED(400, "skill_content_required", "文件内容不能为空"),
    DRAFT_EDIT_ONLY(400, "skill_draft_edit_only", "仅草稿版本可在线编辑"),
    BINARY_EDIT_FORBIDDEN(400, "skill_binary_edit_forbidden", "不支持编辑二进制文件"),
    FILE_SAVE_FAILED(400, "skill_file_save_failed", "文件保存失败"),
    FILE_READ_AFTER_SAVE_FAILED(500, "skill_file_read_after_save_failed", "保存后读取失败"),
    SKILL_MD_INVALID(400, "skill_md_invalid", "SKILL.md 格式不正确"),
    PACKAGE_DOWNLOAD_EMPTY(400, "skill_package_download_empty", "该版本无可下载文件"),
    PACKAGE_ZIP_FAILED(500, "skill_package_zip_failed", "打包下载失败"),
    UPLOAD_PAYLOAD_INVALID(400, "skill_upload_payload_invalid", "请上传 SKILL.md、zip 或文本内容"),
    VERSION_DIFF_SAME(400, "skill_version_diff_same", "对比版本不能相同");

    private final int code;
    private final String key;
    private final String message;
}
