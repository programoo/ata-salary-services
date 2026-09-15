package com.ata.salaryservices.service;

import com.ata.salaryservices.dto.OrderDetailDto;
import com.ata.salaryservices.dto.OrderSummaryDto;
import com.ata.salaryservices.model.Order;
import com.ata.salaryservices.model.OrderAction;
import com.ata.salaryservices.model.OrderStatus;
import com.ata.salaryservices.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Backing logic for {@code com.ata.salaryservices.controller.OrderController}.
 * Filtering, sorting and paging all run in the database.
 */
@Service
@Transactional(readOnly = true)
public class OrderService {

    /**
     * API sort field -> entity attribute path. The client sends only the field
     * name; how it compares is decided here. {@code price} is {amount, currency},
     * so it sorts by amount; dates sort as instants and quantities as numbers
     * because that's how they are stored.
     */
    private static final Map<String, String> SORT_PROPERTIES = Map.ofEntries(
            Map.entry("account", "account"),
            Map.entry("operation", "operation"),
            Map.entry("symbol", "symbol"),
            Map.entry("description", "description"),
            Map.entry("quantity", "quantity"),
            Map.entry("filledQuantity", "filledQuantity"),
            Map.entry("price", "price.amount"),
            Map.entry("status", "status"),
            Map.entry("orderDateTime", "orderDateTime"),
            Map.entry("expirationDateTime", "expirationDateTime"),
            Map.entry("referenceNo", "referenceNo"),
            Map.entry("externalRef", "externalRef"));

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    /**
     * @param from first calendar day (UTC) to include, or null for no lower bound
     * @param to   last calendar day (UTC) to include, or null for no upper bound
     */
    public Page<OrderSummaryDto> search(OrderStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"From\" date must be on or before \"To\" date.");
        }

        List<Specification<Order>> specs = new ArrayList<>();
        if (status != null) {
            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (from != null) {
            Instant start = from.atStartOfDay(ZoneOffset.UTC).toInstant();
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("orderDateTime"), start));
        }
        if (to != null) {
            Instant endExclusive = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            specs.add((root, query, cb) -> cb.lessThan(root.get("orderDateTime"), endExclusive));
        }

        return repository.findAll(toEntityPageable(pageable))
                .map(OrderSummaryDto::from);
    }

    public OrderDetailDto getDetail(String orderId) {
        Order order = findOrder(orderId);
        return OrderDetailDto.from(order, availableActions(order));
    }

    /**
     * Validates that {@code action} is currently allowed on the order. Status
     * transitions are not modelled yet (MVP-1 has only WAITING), so a valid
     * action leaves the order unchanged.
     */
    public void performAction(String orderId, OrderAction action) {
        Order order = findOrder(orderId);
        if (!availableActions(order).contains(action)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Action " + action + " is not available for order " + orderId);
        }
    }

    /** Which buttons the UI may show is business logic, so it is decided here. */
    private List<OrderAction> availableActions(Order order) {
        return switch (order.getStatus()) {
            case WAITING -> List.of(OrderAction.ACCEPT, OrderAction.REJECT);
        };
    }

    private Order findOrder(String orderId) {
        return repository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private Pageable toEntityPageable(Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            String property = SORT_PROPERTIES.get(order.getProperty());
            if (property == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Unsupported sort field '" + order.getProperty() + "'");
            }
            orders.add(order.withProperty(property));
        }
        // Tie-breaker: without it, rows sharing a sort key (e.g. every status is
        // WAITING) can shift between pages and infinite scroll shows duplicates.
        orders.add(Sort.Order.asc("id"));
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders));
    }
}
