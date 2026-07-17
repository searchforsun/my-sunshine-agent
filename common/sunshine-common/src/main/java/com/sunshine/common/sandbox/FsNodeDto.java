package com.sunshine.common.sandbox;

import java.util.List;

/** 目录列表节点（容器路径语义） */
public record FsNodeDto(String name, String path, String type, Long size) {

    public static FsNodeDto dir(String name, String path) {
        return new FsNodeDto(name, path, "dir", null);
    }

    public static FsNodeDto file(String name, String path, long size) {
        return new FsNodeDto(name, path, "file", size);
    }

    public record FsListResponse(String path, List<FsNodeDto> entries) {}
}
