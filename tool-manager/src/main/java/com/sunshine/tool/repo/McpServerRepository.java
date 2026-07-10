package com.sunshine.tool.repo;

import com.sunshine.tool.entity.McpServerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface McpServerRepository extends JpaRepository<McpServerEntity, String> {

    List<McpServerEntity> findByEnabledTrue();
}
