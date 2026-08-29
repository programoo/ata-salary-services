package com.ata.salaryservices.controller;

import com.ata.salaryservices.model.SalaryRecord;
import com.ata.salaryservices.repository.SalaryRecordRepository;
import com.ata.salaryservices.repository.SalaryRecordSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only access to salary survey records: list with optional filters, or
 * fetch a single record by id. No create/update/delete endpoints.
 */
@RestController
@RequestMapping("/api/salary-records")
public class SalaryRecordController {

    private final SalaryRecordRepository repository;

    public SalaryRecordController(SalaryRecordRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Page<SalaryRecord> getAll(
            @RequestParam(required = false) String employer,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String jobTitle,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) String salary,
            @RequestParam(required = false) String yearsOfExperience,
            @RequestParam(required = false) String yearsAtEmployer,
            @PageableDefault(size = 20) Pageable pageable) {

        Specification<SalaryRecord> spec = SalaryRecordSpecifications.build(
                employer, location, jobTitle, gender, salary, yearsOfExperience, yearsAtEmployer);

        return repository.findAll(spec, pageable);
    }

    @GetMapping("/{id}")
    public ResponseEntity<SalaryRecord> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
