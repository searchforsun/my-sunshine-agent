package com.sunshine.tool.repo;

import com.sunshine.tool.entity.SdkApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SdkApplicationRepository extends JpaRepository<SdkApplicationEntity, String> {
}
