package com.sunshine.orchestrator.workspace.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 工作区列表 offset/limit 分页 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspacePageDto {

    private List<WorkspaceVO> items;
    private boolean hasMore;
}
