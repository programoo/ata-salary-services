package com.ata.salaryservices.dto;

import com.ata.salaryservices.model.Money;
import com.ata.salaryservices.model.Order;
import com.ata.salaryservices.model.OrderAction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The expanded detail panel for one order - mirrors {@code OrderDetail} in
 * ata-order-web's {@code src/types/order.ts}.
 */
public record OrderDetailDto(
        String orderId,
        String clientName,
        String accountLabel,
        Money netAmount,
        Money price,
        BigDecimal exchangeRate,
        BigDecimal outstandingLimit,
        String referenceNumber,
        Instant submittedAt,
        String telephone,
        String userId,
        List<String> warnings,
        List<OrderAction> availableActions) {

    public static OrderDetailDto from(Order order, List<OrderAction> availableActions) {
        return new OrderDetailDto(
                order.getId(),
                order.getClientName(),
                order.getAccountLabel(),
                order.getNetAmount(),
                order.getPrice(),
                order.getExchangeRate(),
                order.getOutstandingLimit(),
                order.getReferenceNumber(),
                order.getOrderDateTime(),
                order.getTelephone(),
                order.getUserId(),
                List.copyOf(order.getWarnings()),
                availableActions);
    }
}
