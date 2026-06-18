package com.sunshine.oa.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.oa.dto.OaTaskVO;
import com.sunshine.oa.service.OaTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/oa")
@RequiredArgsConstructor
public class OaTaskController {

    private final OaTaskService taskService;

    @GetMapping("/tasks")
    public R<List<OaTaskVO>> listTasks(@RequestParam(value = "status", required = false) String status) {
        return R.ok(taskService.list(status));
    }
}
