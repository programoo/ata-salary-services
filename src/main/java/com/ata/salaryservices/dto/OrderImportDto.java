package com.ata.salaryservices.dto;

import com.ata.salaryservices.model.Money;
import com.ata.salaryservices.model.Operation;
import com.ata.salaryservices.model.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Shape of the order seed JSON (an order summary with its detail nested under
 * {@code detail}). Only used at import time.
 */
public record OrderImportDto(
        String id,
        String account,
        Operation operation,
        String symbol,
        String description,
        int quantity,
        int filledQuantity,
        Money price,
        OrderStatus status,
        Instant orderDateTime,
        Instant expirationDateTime,
        String referenceNo,
        String externalRef,
        Detail detail) {

    public record Detail(
            String clientName,
            String accountLabel,
            Money netAmount,
            BigDecimal exchangeRate,
            BigDecimal outstandingLimit,
            String referenceNumber,
            String telephone,
            String userId,
            List<String> warnings) {
    }
}
