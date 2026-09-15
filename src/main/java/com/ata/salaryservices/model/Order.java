package com.ata.salaryservices.model;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A trading order awaiting review. Holds both the summary shown as a table row
 * and the detail shown when a row is expanded; the API exposes them through
 * separate endpoints so the list stays light.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String id;

    private String account;

    @Enumerated(EnumType.STRING)
    private Operation operation;

    /** null when the instrument has no ticker. */
    private String symbol;

    private String description;

    private int quantity;

    @Column(name = "filled_quantity")
    private int filledQuantity;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "price_amount", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "price_currency", length = 3))
    })
    private Money price;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "order_date_time")
    private Instant orderDateTime;

    @Column(name = "expiration_date_time")
    private Instant expirationDateTime;

    @Column(name = "reference_no")
    private String referenceNo;

    @Column(name = "external_ref")
    private String externalRef;

    // ---- detail ----

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "account_label")
    private String accountLabel;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount", column = @Column(name = "net_amount", precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "net_amount_currency", length = 3))
    })
    private Money netAmount;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(name = "outstanding_limit", precision = 19, scale = 4)
    private BigDecimal outstandingLimit;

    @Column(name = "reference_number")
    private String referenceNumber;

    private String telephone;

    @Column(name = "user_id")
    private String userId;

    @ElementCollection
    @CollectionTable(name = "order_warnings", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "position")
    @Column(name = "warning", length = 1000)
    private List<String> warnings = new ArrayList<>();

    public Order() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public Operation getOperation() {
        return operation;
    }

    public void setOperation(Operation operation) {
        this.operation = operation;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getFilledQuantity() {
        return filledQuantity;
    }

    public void setFilledQuantity(int filledQuantity) {
        this.filledQuantity = filledQuantity;
    }

    public Money getPrice() {
        return price;
    }

    public void setPrice(Money price) {
        this.price = price;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public Instant getOrderDateTime() {
        return orderDateTime;
    }

    public void setOrderDateTime(Instant orderDateTime) {
        this.orderDateTime = orderDateTime;
    }

    public Instant getExpirationDateTime() {
        return expirationDateTime;
    }

    public void setExpirationDateTime(Instant expirationDateTime) {
        this.expirationDateTime = expirationDateTime;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public void setReferenceNo(String referenceNo) {
        this.referenceNo = referenceNo;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    public void setAccountLabel(String accountLabel) {
        this.accountLabel = accountLabel;
    }

    public Money getNetAmount() {
        return netAmount;
    }

    public void setNetAmount(Money netAmount) {
        this.netAmount = netAmount;
    }

    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    public BigDecimal getOutstandingLimit() {
        return outstandingLimit;
    }

    public void setOutstandingLimit(BigDecimal outstandingLimit) {
        this.outstandingLimit = outstandingLimit;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public void setReferenceNumber(String referenceNumber) {
        this.referenceNumber = referenceNumber;
    }

    public String getTelephone() {
        return telephone;
    }

    public void setTelephone(String telephone) {
        this.telephone = telephone;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings;
    }
}
