package com.sunshine.rag.util;

/** chunk_id / parent_chunk_id 编解码 — 格式 {docId}#v{version}#{index} */
public final class ChunkIds {

    private ChunkIds() {
    }

    public static String chunkId(String docId, String version, int index) {
        return docId + "#v" + version + "#" + index;
    }

    public static String parentChunkId(String docId, String version, int parentIndex) {
        return chunkId(docId, version, parentIndex);
    }

    /** @return [docId, version, index] 或 null */
    public static Parsed parse(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        int vMarker = chunkId.indexOf("#v");
        if (vMarker <= 0) {
            return null;
        }
        int indexMarker = chunkId.lastIndexOf('#');
        if (indexMarker <= vMarker + 1) {
            return null;
        }
        String docId = chunkId.substring(0, vMarker);
        String version = chunkId.substring(vMarker + 2, indexMarker);
        try {
            int index = Integer.parseInt(chunkId.substring(indexMarker + 1));
            return new Parsed(docId, version, index);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record Parsed(String docId, String version, int index) {
    }
}
