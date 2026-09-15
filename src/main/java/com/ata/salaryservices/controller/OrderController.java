package com.ata.salaryservices.controller;

import com.ata.salaryservices.dto.OrderDetailDto;
import com.ata.salaryservices.dto.OrderSummaryDto;
import com.ata.salaryservices.model.OrderAction;
import com.ata.salaryservices.model.OrderStatus;
import com.ata.salaryservices.model.Period;
import com.ata.salaryservices.service.OrderService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * Order search API backing ata-order-web; replaces its MSW mock handlers
 * ({@code src/mocks/handlers.ts}) behind the same URLs and JSON shapes.
 * <p>
 * {@code GET /api/orders?period=TRANSMISSION&status=WAITING&from=2022-12-01&to=2023-01-31&page=0&size=20&sort=orderDateTime,desc}
 * returns {@code { content: OrderSummary[], page: { size, number, totalElements, totalPages } }}.
 * {@code from}/{@code to} are inclusive calendar days in UTC; a backwards range is
 * rejected with 400. Errors are returned as {@code { "message": "..." }}.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public PagedModel<OrderSummaryDto> getOrders(
            // Validated but not filtered on: TRANSMISSION is the only period in MVP-1.
            @RequestParam(required = false) Period period,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "orderDateTime", direction = Sort.Direction.DESC) Pageable pageable) {

        return new PagedModel<>(orderService.search(status, from, to, pageable));
    }

    @GetMapping("/{id}/detail")
    public OrderDetailDto getOrderDetail(@PathVariable String id) {
        return orderService.getDetail(id);
    }

    @PostMapping("/{id}/actions/{action}")
    public Map<String, Boolean> performAction(@PathVariable String id, @PathVariable OrderAction action) {
        orderService.performAction(id, action);
        return Map.of("ok", true);
    }
}
