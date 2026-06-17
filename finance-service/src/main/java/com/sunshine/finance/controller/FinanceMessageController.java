package com.sunshine.finance.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.finance.dto.FinanceMessageVO;
import com.sunshine.finance.service.FinanceMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceMessageController {

    private final FinanceMessageService messageService;

    @GetMapping("/messages")
    public R<List<FinanceMessageVO>> listMessages(
            @RequestParam(required = false) String status) {
        return R.ok(messageService.list(status));
    }
}
