package com.ata.salaryservices.controller;

import com.ata.salaryservices.model.SalaryRecord;
import com.ata.salaryservices.repository.SalaryRecordRepository;
import com.ata.salaryservices.util.SalaryParser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Operator-based filtering over job title, salary, and gender, e.g.
 * {@code /job_data?salary[gte]=120000&jobTitle[eq]=Engineer}. Unlike
 * {@code /api/salary-records} (case-insensitive substring match), filters
 * here are relational comparisons: eq (default when no operator is given),
 * ne, gt, gte, lt, lte. Salary comparisons parse the raw salary text into a
 * number on a best-effort basis (see {@link SalaryParser}); records whose
 * salary can't be parsed are excluded from salary filters and from salary
 * sorting (treated as unparseable, not zero-adjacent).
 */
@RestController
public class JobDataController {

    private static final Set<String> FILTERABLE_FIELDS = Set.of("jobTitle", "salary", "gender");
    private static final Set<String> RESERVED_PARAMS = Set.of("page", "size", "sort");
    private static final Pattern PARAM_KEY = Pattern.compile("^(\\w+)(?:\\[(\\w+)])?$");

    private final SalaryRecordRepository repository;

    public JobDataController(SalaryRecordRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/job_data")
    public Page<SalaryRecord> getJobData(
            @RequestParam Map<String, String> allParams,
            @PageableDefault(size = 20) Pageable pageable) {

        List<Filter> filters = parseFilters(allParams);

        List<SalaryRecord> filtered = repository.findAll().stream()
                .filter(record -> filters.stream().allMatch(f -> f.matches(record)))
                .collect(Collectors.toCollection(ArrayList::new));

        sort(filtered, pageable.getSort());

        int total = filtered.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);

        return new PageImpl<>(filtered.subList(start, end), pageable, total);
    }

    private List<Filter> parseFilters(Map<String, String> allParams) {
        List<Filter> filters = new ArrayList<>();
        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();
            if (RESERVED_PARAMS.contains(key) || !StringUtils.hasText(entry.getValue())) {
                continue;
            }
            Matcher matcher = PARAM_KEY.matcher(key);
            if (!matcher.matches()) {
                continue;
            }
            String field = matcher.group(1);
            if (!FILTERABLE_FIELDS.contains(field)) {
                continue;
            }
            String opRaw = matcher.group(2);
            FilterOperator operator;
            try {
                operator = opRaw == null ? FilterOperator.EQ : FilterOperator.from(opRaw);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported filter operator '" + opRaw + "' for field '" + field + "'");
            }
            filters.add(new Filter(field, operator, entry.getValue()));
        }
        return filters;
    }

    private void sort(List<SalaryRecord> records, Sort sort) {
        Comparator<SalaryRecord> comparator = null;
        for (Sort.Order order : sort) {
            Comparator<SalaryRecord> fieldComparator = comparatorFor(order.getProperty());
            if (fieldComparator == null) {
                continue;
            }
            if (order.isDescending()) {
                fieldComparator = fieldComparator.reversed();
            }
            comparator = comparator == null ? fieldComparator : comparator.thenComparing(fieldComparator);
        }
        if (comparator != null) {
            records.sort(comparator);
        }
    }

    private Comparator<SalaryRecord> comparatorFor(String property) {
        return switch (property) {
            case "jobTitle" -> Comparator.comparing(
                    r -> r.getJobTitle() == null ? "" : r.getJobTitle(), String.CASE_INSENSITIVE_ORDER);
            case "gender" -> Comparator.comparing(
                    r -> r.getGender() == null ? "" : r.getGender(), String.CASE_INSENSITIVE_ORDER);
            case "salary" -> Comparator.comparing(
                    r -> SalaryParser.parse(r.getSalary()).orElse(BigDecimal.ZERO));
            default -> null;
        };
    }

    private record Filter(String field, FilterOperator operator, String value) {

        boolean matches(SalaryRecord record) {
            return switch (field) {
                case "jobTitle" -> matchesString(record.getJobTitle());
                case "gender" -> matchesString(record.getGender());
                case "salary" -> matchesSalary(record.getSalary());
                default -> true;
            };
        }

        private boolean matchesString(String actual) {
            if (!StringUtils.hasText(actual)) {
                return false;
            }
            return operator.test(actual.compareToIgnoreCase(value));
        }

        private boolean matchesSalary(String actualRaw) {
            BigDecimal filterValue = SalaryParser.parse(value)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Invalid numeric value for salary filter: '" + value + "'"));
            return SalaryParser.parse(actualRaw)
                    .map(actual -> operator.test(actual.compareTo(filterValue)))
                    .orElse(false);
        }
    }
}
