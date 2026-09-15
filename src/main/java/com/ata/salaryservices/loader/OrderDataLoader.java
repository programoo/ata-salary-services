package com.ata.salaryservices.loader;

import com.ata.salaryservices.dto.OrderImportDto;
import com.ata.salaryservices.model.Order;
import com.ata.salaryservices.repository.OrderRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * On startup, imports the order seed dataset into the database exactly once.
 */
@Component
public class OrderDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(OrderDataLoader.class);

    private final OrderRepository repository;
    private final ObjectMapper objectMapper;
    private final Resource importFile;

    public OrderDataLoader(
            OrderRepository repository,
            ObjectMapper objectMapper,
            @Value("${app.data.orders-import-file}") Resource importFile) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.importFile = importFile;
    }

    @Override
    public void run(String... args) throws Exception {
        long existing = repository.count();
        if (existing > 0) {
            log.info("Orders already present ({}), skipping import.", existing);
            return;
        }

        try (InputStream inputStream = importFile.getInputStream()) {
            List<OrderImportDto> dtos =
                    objectMapper.readValue(inputStream, new TypeReference<List<OrderImportDto>>() {
                    });

            List<Order> entities = dtos.stream().map(this::toEntity).toList();
            repository.saveAll(entities);
            log.info("Imported {} orders from {}.", entities.size(), importFile.getFilename());
        }
    }

    private Order toEntity(OrderImportDto dto) {
        Order entity = new Order();
        entity.setId(dto.id());
        entity.setAccount(dto.account());
        entity.setOperation(dto.operation());
        entity.setSymbol(dto.symbol());
        entity.setDescription(dto.description());
        entity.setQuantity(dto.quantity());
        entity.setFilledQuantity(dto.filledQuantity());
        entity.setPrice(dto.price());
        entity.setStatus(dto.status());
        entity.setOrderDateTime(dto.orderDateTime());
        entity.setExpirationDateTime(dto.expirationDateTime());
        entity.setReferenceNo(dto.referenceNo());
        entity.setExternalRef(dto.externalRef());

        OrderImportDto.Detail detail = dto.detail();
        entity.setClientName(detail.clientName());
        entity.setAccountLabel(detail.accountLabel());
        entity.setNetAmount(detail.netAmount());
        entity.setExchangeRate(detail.exchangeRate());
        entity.setOutstandingLimit(detail.outstandingLimit());
        entity.setReferenceNumber(detail.referenceNumber());
        entity.setTelephone(detail.telephone());
        entity.setUserId(detail.userId());
        entity.setWarnings(new ArrayList<>(detail.warnings()));
        return entity;
    }
}
