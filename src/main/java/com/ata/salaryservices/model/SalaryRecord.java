package com.ata.salaryservices.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Raw salary survey response. Fields are kept as free-text strings, exactly as
 * they appear in the source survey (e.g. Salary may be "80000", "$24/hr", or "135k") -
 * this service does not normalize them, so filtering on numeric-looking fields is
 * substring based rather than range based.
 */
@Entity
@Table(name = "salary_records")
public class SalaryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String timestamp;

    private String employer;

    private String location;

    @Column(name = "job_title")
    private String jobTitle;

    @Column(name = "years_at_employer")
    private String yearsAtEmployer;

    @Column(name = "years_of_experience")
    private String yearsOfExperience;

    private String salary;

    @Column(name = "signing_bonus")
    private String signingBonus;

    @Column(name = "annual_bonus")
    private String annualBonus;

    @Column(name = "annual_stock_value_bonus")
    private String annualStockValueBonus;

    private String gender;

    @Column(name = "additional_comments", length = 2000)
    private String additionalComments;

    public SalaryRecord() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getEmployer() {
        return employer;
    }

    public void setEmployer(String employer) {
        this.employer = employer;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getYearsAtEmployer() {
        return yearsAtEmployer;
    }

    public void setYearsAtEmployer(String yearsAtEmployer) {
        this.yearsAtEmployer = yearsAtEmployer;
    }

    public String getYearsOfExperience() {
        return yearsOfExperience;
    }

    public void setYearsOfExperience(String yearsOfExperience) {
        this.yearsOfExperience = yearsOfExperience;
    }

    public String getSalary() {
        return salary;
    }

    public void setSalary(String salary) {
        this.salary = salary;
    }

    public String getSigningBonus() {
        return signingBonus;
    }

    public void setSigningBonus(String signingBonus) {
        this.signingBonus = signingBonus;
    }

    public String getAnnualBonus() {
        return annualBonus;
    }

    public void setAnnualBonus(String annualBonus) {
        this.annualBonus = annualBonus;
    }

    public String getAnnualStockValueBonus() {
        return annualStockValueBonus;
    }

    public void setAnnualStockValueBonus(String annualStockValueBonus) {
        this.annualStockValueBonus = annualStockValueBonus;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getAdditionalComments() {
        return additionalComments;
    }

    public void setAdditionalComments(String additionalComments) {
        this.additionalComments = additionalComments;
    }
    
    @Override
    public String toString() {
		return "jobTitle='" + jobTitle + '\'' +", salary='" + salary + '\'' +
				", gender='" + gender + '\'';
				
	}
}
