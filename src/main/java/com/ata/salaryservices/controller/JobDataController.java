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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
 * <p>
 * Supports sparse fieldsets via {@code fields}, e.g.
 * {@code /job_data?fields=job_title,gender,salary} returns only those
 * columns per record. Field names are matched case-insensitively and
 * underscore-insensitively, so both {@code jobTitle} and {@code job_title}
 * resolve to the same column.
 */
@RestController
public class JobDataController {

    private static final Set<String> FILTERABLE_FIELDS = Set.of("jobTitle", "salary", "gender");
    private static final Set<String> RESERVED_PARAMS = Set.of("page", "size", "sort", "fields");
    private static final Pattern PARAM_KEY = Pattern.compile("^(\\w+)(?:\\[(\\w+)])?$");

    private static final Map<String, Function<SalaryRecord, Object>> FIELD_ACCESSORS = buildFieldAccessors();
    private static final Map<String, String> FIELD_LOOKUP = FIELD_ACCESSORS.keySet().stream()
            .collect(Collectors.toMap(JobDataController::canonicalize, Function.identity()));

    private final SalaryRecordRepository repository;

    public JobDataController(SalaryRecordRepository repository) {
        this.repository = repository;
    }
    
    @GetMapping("/job_filter")
    public Page<Object> getJobFilter(
            @RequestParam Map<String, String> allParams,
            @RequestParam(name = "fields", required = false) String fieldsParam,
            @PageableDefault(size = 20) Pageable pageable) {

        List<Filter> filters = parseFilters(allParams);
        List<String> fields = new ArrayList<>();//parseFields(fieldsParam);

        List<SalaryRecord> filtered = repository.findAll().stream()
                .filter(record -> filters.stream().allMatch(f -> f.matches(record)))
                .collect(Collectors.toCollection(ArrayList::new));

        //sort(filtered, pageable.getSort());

        int total = filtered.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);

        List<Object> content = filtered.subList(start, end).stream()
                .map(record -> project(record, fields))
                .collect(Collectors.toList());

        return new PageImpl<>(content, pageable, total);
    }

    @GetMapping("/job_data")
    public Page<Object> getJobData(
            @RequestParam Map<String, String> allParams,
            @RequestParam(name = "fields", required = false) String fieldsParam,
            @PageableDefault(size = 20) Pageable pageable) {

        List<Filter> filters = parseFilters(allParams);
        List<String> fields = parseFields(fieldsParam);

        List<SalaryRecord> filtered = repository.findAll().stream()
                .filter(record -> filters.stream().allMatch(f -> f.matches(record)))
                .collect(Collectors.toCollection(ArrayList::new));

        sort(filtered, pageable.getSort());

        int total = filtered.size();
        int start = Math.min((int) pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);

        List<Object> content = filtered.subList(start, end).stream()
                .map(record -> project(record, fields))
                .collect(Collectors.toList());

        return new PageImpl<>(content, pageable, total);
    }

    private static Map<String, Function<SalaryRecord, Object>> buildFieldAccessors() {
        Map<String, Function<SalaryRecord, Object>> map = new LinkedHashMap<>();
        map.put("id", SalaryRecord::getId);
        map.put("timestamp", SalaryRecord::getTimestamp);
        map.put("employer", SalaryRecord::getEmployer);
        map.put("location", SalaryRecord::getLocation);
        map.put("jobTitle", SalaryRecord::getJobTitle);
        map.put("yearsAtEmployer", SalaryRecord::getYearsAtEmployer);
        map.put("yearsOfExperience", SalaryRecord::getYearsOfExperience);
        map.put("salary", SalaryRecord::getSalary);
        map.put("signingBonus", SalaryRecord::getSigningBonus);
        map.put("annualBonus", SalaryRecord::getAnnualBonus);
        map.put("annualStockValueBonus", SalaryRecord::getAnnualStockValueBonus);
        map.put("gender", SalaryRecord::getGender);
        map.put("additionalComments", SalaryRecord::getAdditionalComments);
        return map;
    }

    private static String canonicalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }

    private List<String> parseFields(String fieldsParam) {
        if (!StringUtils.hasText(fieldsParam)) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (String raw : fieldsParam.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String resolved = FIELD_LOOKUP.get(canonicalize(trimmed));
            if (resolved == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unknown field '" + trimmed + "' in fields parameter");
            }
            if (!fields.contains(resolved)) {
                fields.add(resolved);
            }
        }
        return fields;
    }

    private Object project(SalaryRecord record, List<String> fields) {
        if (fields.isEmpty()) {
            return record;
        }
        Map<String, Object> projection = new LinkedHashMap<>();
        for (String field : fields) {
            projection.put(field, FIELD_ACCESSORS.get(field).apply(record));
        }
        return projection;
    }

    private List<Filter> parseFilters(Map<String, String> allParams) {
        List<Filter> filters = new ArrayList<>();
        for (Map.Entry<String, String> entry : allParams.entrySet()) {
            String key = entry.getKey();
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
