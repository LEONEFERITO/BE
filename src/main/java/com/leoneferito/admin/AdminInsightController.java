package com.leoneferito.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 대시보드 · 통계 (ADMIN). 읽기만 한다. */
@RestController
public class AdminInsightController {

    private final AdminInsightService insights;

    public AdminInsightController(AdminInsightService insights) {
        this.insights = insights;
    }

    @GetMapping("/api/admin/dashboard")
    public AdminInsightService.Dashboard dashboard() {
        return insights.dashboard();
    }

    /** days: 최근 며칠 (화면은 30 · 90 · 365 · 0=전체). 0~3650 으로 자른다. */
    @GetMapping("/api/admin/stats/sizes")
    public AdminInsightService.SizeStats sizes(@RequestParam(defaultValue = "90") int days) {
        return insights.sizeStats(Math.min(Math.max(days, 0), 3650));
    }
}
