package com.ata.salaryservices.repository;

import com.ata.salaryservices.model.SalaryRecord;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Builds a case-insensitive "contains" filter per field. Every field on
 * SalaryRecord is raw free text, so this is the only kind of match that makes
 * sense without parsing values first.
 */
public final class SalaryRecordSpecifications {

    private SalaryRecordSpecifications() {
    }

    public static Specification<SalaryRecord> containsIgnoreCase(String field, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String pattern = "%" + value.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get(field)), pattern);
    }

    public static Specification<SalaryRecord> build(
            String employer,
            String location,
            String jobTitle,
            String gender,
            String salary,
            String yearsOfExperience,
            String yearsAtEmployer) {

        Specification<SalaryRecord> spec = Specification.unrestricted();

        spec = and(spec, containsIgnoreCase("employer", employer));
        spec = and(spec, containsIgnoreCase("location", location));
        spec = and(spec, containsIgnoreCase("jobTitle", jobTitle));
        spec = and(spec, containsIgnoreCase("gender", gender));
        spec = and(spec, containsIgnoreCase("salary", salary));
        spec = and(spec, containsIgnoreCase("yearsOfExperience", yearsOfExperience));
        spec = and(spec, containsIgnoreCase("yearsAtEmployer", yearsAtEmployer));

        return spec;
    }

    private static Specification<SalaryRecord> and(
            Specification<SalaryRecord> base, Specification<SalaryRecord> addition) {
        return addition == null ? base : base.and(addition);
    }
}
