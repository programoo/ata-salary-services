package com.ata.salaryservices.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Maps the raw survey JSON's original field names (with spaces/slashes) onto a
 * shape usable in code. Only used at import time - the persisted entity and the
 * API exposed to clients use normal camelCase names.
 */
public record SalaryRecordImportDto(
        @JsonProperty("Timestamp") String timestamp,
        @JsonProperty("Employer") String employer,
        @JsonProperty("Location") String location,
        @JsonProperty("Job Title") String jobTitle,
        @JsonProperty("Years at Employer") String yearsAtEmployer,
        @JsonProperty("Years of Experience") String yearsOfExperience,
        @JsonProperty("Salary") String salary,
        @JsonProperty("Signing Bonus") String signingBonus,
        @JsonProperty("Annual Bonus") String annualBonus,
        @JsonProperty("Annual Stock Value/Bonus") String annualStockValueBonus,
        @JsonProperty("Gender") String gender,
        @JsonProperty("Additional Comments") String additionalComments) {
}
