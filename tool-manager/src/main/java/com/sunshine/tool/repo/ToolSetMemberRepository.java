package com.sunshine.tool.repo;

import com.sunshine.tool.entity.ToolSetMemberEntity;
import com.sunshine.tool.entity.ToolSetMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ToolSetMemberRepository extends JpaRepository<ToolSetMemberEntity, ToolSetMemberId> {

    List<ToolSetMemberEntity> findBySetIdOrderBySortOrderAsc(String setId);

    void deleteBySetId(String setId);

    void deleteByToolId(String toolId);

    List<ToolSetMemberEntity> findByToolId(String toolId);
}
