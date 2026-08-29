package com.ata.salaryservices.repository;

import com.ata.salaryservices.model.SalaryRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SalaryRecordRepository
        extends JpaRepository<SalaryRecord, Long>, JpaSpecificationExecutor<SalaryRecord> {
}
