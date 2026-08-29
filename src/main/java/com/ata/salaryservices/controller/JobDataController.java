package com.ata.salaryservices.controller;

import com.ata.salaryservices.service.JobDataService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operator-based filtering over job title, salary, and gender, e.g.
 * {@code /atadev/job_data?salary[gte]=120000&jobTitle[eq]=Engineer}. Filters
 * are relational comparisons: eq (default when no operator is given), ne,
 * gt, gte, lt, lte. Salary comparisons parse the raw salary text into a
 * number on a best-effort basis (see {@link com.ata.salaryservices.util.SalaryParser}); records whose
 * salary can't be parsed are excluded from salary filters and from salary
 * sorting (treated as unparseable, not zero-adjacent).
 * <p>
 * Supports sparse fieldsets via {@code fields}, e.g.
 * {@code /atadev/job_data?fields=job_title,gender,salary} returns only those
 * columns per record. Field names are matched case-insensitively and
 * underscore-insensitively, so both {@code jobTitle} and {@code job_title}
 * resolve to the same column.
 */
@RestController
public class JobDataController {

    private final JobDataService jobDataService;

    public JobDataController(JobDataService jobDataService) {
        this.jobDataService = jobDataService;
    }

    @GetMapping("atadev/job_data")
    public Page<Object> getJobData(
            @RequestParam Map<String, String> allParams,
            @RequestParam(name = "fields", required = false) String fieldsParam,
            @PageableDefault(size = 20) Pageable pageable) {

        return jobDataService.getJobData(allParams, fieldsParam, pageable);
    }
}
