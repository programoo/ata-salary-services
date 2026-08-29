package com.ata.salaryservices.loader;

import com.ata.salaryservices.dto.SalaryRecordImportDto;
import com.ata.salaryservices.model.SalaryRecord;
import com.ata.salaryservices.repository.SalaryRecordRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * On startup, imports the seed dataset into the database exactly once. Values
 * are copied as-is from the source JSON - no parsing or normalization.
 */
@Component
public class SalaryDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SalaryDataLoader.class);

    private final SalaryRecordRepository repository;
    private final ObjectMapper objectMapper;
    private final Resource importFile;

    public SalaryDataLoader(
            SalaryRecordRepository repository,
            ObjectMapper objectMapper,
            @Value("${app.data.import-file}") Resource importFile) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.importFile = importFile;
    }

    @Override
    public void run(String... args) throws Exception {
        long existing = repository.count();
        if (existing > 0) {
            log.info("Salary records already present ({}), skipping import.", existing);
            return;
        }

        try (InputStream inputStream = importFile.getInputStream()) {
            List<SalaryRecordImportDto> dtos =
                    objectMapper.readValue(inputStream, new TypeReference<List<SalaryRecordImportDto>>() {
                    });

            List<SalaryRecord> entities = dtos.stream().map(this::toEntity).toList();
            repository.saveAll(entities);
            log.info("Imported {} salary records from {}.", entities.size(), importFile.getFilename());
        }
    }

    private SalaryRecord toEntity(SalaryRecordImportDto dto) {
        SalaryRecord entity = new SalaryRecord();
        entity.setTimestamp(dto.timestamp());
        entity.setEmployer(dto.employer());
        entity.setLocation(dto.location());
        entity.setJobTitle(dto.jobTitle());
        entity.setYearsAtEmployer(dto.yearsAtEmployer());
        entity.setYearsOfExperience(dto.yearsOfExperience());
        entity.setSalary(dto.salary());
        entity.setSigningBonus(dto.signingBonus());
        entity.setAnnualBonus(dto.annualBonus());
        entity.setAnnualStockValueBonus(dto.annualStockValueBonus());
        entity.setGender(dto.gender());
        entity.setAdditionalComments(dto.additionalComments());
        return entity;
    }
}
