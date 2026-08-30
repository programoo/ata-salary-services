package com.ata.salaryservices.service;

import com.ata.salaryservices.model.SalaryRecord;
import com.ata.salaryservices.repository.SalaryRecordRepository;
import com.ata.salaryservices.util.SalaryParser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
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
 * Backing logic for {@code com.ata.salaryservices.controller.JobDataController}:
 * operator-based filtering, sparse fieldsets, and sorting over
 * {@link SalaryRecord}s. See the controller's javadoc for the supported
 * query syntax.
 */
@Service
public class JobDataService {

    private static final Set<String> FILTERABLE_FIELDS = Set.of("job_title", "salary", "gender");
    private static final Pattern PARAM_KEY = Pattern.compile("^(\\w+)(?:\\[(\\w+)])?$");

    private static final Map<String, Function<SalaryRecord, Object>> FIELD_ACCESSORS = buildFieldAccessors();
    private static final Map<String, String> FIELD_LOOKUP = FIELD_ACCESSORS.keySet().stream()
            .collect(Collectors.toMap(JobDataService::canonicalize, Function.identity()));

    /**
     * Free-text fields that hold a number (e.g. "135k", "$24/hr", "3 years") and so
     * sort numerically rather than lexicographically.
     */
    private static final Set<String> NUMERIC_FIELDS = Set.of(
            "yearsAtEmployer", "yearsOfExperience", "salary",
            "signingBonus", "annualBonus", "annualStockValueBonus");

    private static final Map<String, Comparator<SalaryRecord>> COMPARATORS = buildComparators();

    private final SalaryRecordRepository repository;

    public JobDataService(SalaryRecordRepository repository) {
        this.repository = repository;
    }

    public Page<Object> getJobData(Map<String, String> allParams, String fieldsParam, Pageable pageable) {
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

    private static Map<String, Comparator<SalaryRecord>> buildComparators() {
        Map<String, Comparator<SalaryRecord>> map = new LinkedHashMap<>();
        for (Map.Entry<String, Function<SalaryRecord, Object>> entry : FIELD_ACCESSORS.entrySet()) {
            String field = entry.getKey();
            Function<SalaryRecord, Object> accessor = entry.getValue();
            Comparator<SalaryRecord> comparator;
            if ("id".equals(field)) {
                comparator = Comparator.comparing(record -> record.getId() == null ? Long.MIN_VALUE : record.getId());
            } else if (NUMERIC_FIELDS.contains(field)) {
                comparator = Comparator.comparing(
                        record -> SalaryParser.parse((String) accessor.apply(record)).orElse(BigDecimal.ZERO));
            } else {
                comparator = Comparator.comparing(
                        record -> {
                            Object value = accessor.apply(record);
                            return value == null ? "" : value.toString();
                        }, String.CASE_INSENSITIVE_ORDER);
            }
            map.put(canonicalize(field), comparator);
        }
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
        return COMPARATORS.get(canonicalize(property));
    }

    private record Filter(String field, FilterOperator operator, String value) {

        boolean matches(SalaryRecord record) {
            return switch (field) {
                case "job_title" -> matchesString(record.getJobTitle());
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
