package com.ata.salaryservices.model;

import jakarta.persistence.Embeddable;

import java.math.BigDecimal;

/**
 * An amount with its ISO-4217 currency. Crosses the wire as
 * {@code { "amount": 135, "currency": "USD" }}, never as a formatted string.
 */
@Embeddable
public record Money(BigDecimal amount, String currency) {
}
