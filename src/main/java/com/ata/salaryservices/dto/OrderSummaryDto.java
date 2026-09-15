package com.ata.salaryservices.dto;

import com.ata.salaryservices.model.Money;
import com.ata.salaryservices.model.Operation;
import com.ata.salaryservices.model.Order;
import com.ata.salaryservices.model.OrderStatus;

import java.time.Instant;

/**
 * One row of the order search table - mirrors {@code OrderSummary} in
 * ata-order-web's {@code src/types/order.ts}.
 */
public record OrderSummaryDto(
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
        String externalRef) {

    public static OrderSummaryDto from(Order order) {
        return new OrderSummaryDto(
                order.getId(),
                order.getAccount(),
                order.getOperation(),
                order.getSymbol(),
                order.getDescription(),
                order.getQuantity(),
                order.getFilledQuantity(),
                order.getPrice(),
                order.getStatus(),
                order.getOrderDateTime(),
                order.getExpirationDateTime(),
                order.getReferenceNo(),
                order.getExternalRef());
    }
}
