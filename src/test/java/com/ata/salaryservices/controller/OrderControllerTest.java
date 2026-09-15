package com.ata.salaryservices.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:sqlite:file:ordertest?mode=memory&cache=shared")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void listDefaultsToFirstPageOfTwentyNewestFirst() throws Exception {
        JsonNode body = getJson("/api/orders");

        assertThat(body.get("content")).hasSize(20);
        assertThat(body.at("/page/number").asInt()).isZero();
        assertThat(body.at("/page/size").asInt()).isEqualTo(20);
        assertThat(body.at("/page/totalElements").asInt()).isEqualTo(123);
        assertThat(body.at("/page/totalPages").asInt()).isEqualTo(7);

        Instant previous = Instant.MAX;
        for (JsonNode order : body.get("content")) {
            Instant current = Instant.parse(order.get("orderDateTime").asText());
            assertThat(current).isBeforeOrEqualTo(previous);
            previous = current;
        }
        JsonNode first = body.at("/content/0");
        assertThat(first.at("/price/currency").asText()).isEqualTo("USD");
        assertThat(first.has("symbol")).isTrue();
    }

    @Test
    void dateRangeIsInclusiveOfBothCalendarDays() throws Exception {
        JsonNode body = getJson("/api/orders?period=TRANSMISSION&status=WAITING&from=2022-12-10&to=2022-12-20&size=200");

        assertThat(body.get("content")).isNotEmpty();
        assertThat(body.at("/page/totalElements").asInt()).isLessThan(123);
        for (JsonNode order : body.get("content")) {
            String day = order.get("orderDateTime").asText().substring(0, 10);
            assertThat(day).isBetween("2022-12-10", "2022-12-20");
        }
    }

    @Test
    void backwardsDateRangeIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders?from=2023-01-31&to=2022-12-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("\"From\" date must be on or before \"To\" date."));
    }

    @Test
    void invalidEnumParameterIsRejectedWithMessage() throws Exception {
        mockMvc.perform(get("/api/orders?status=DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value 'DONE' for 'status'"));
    }

    @Test
    void priceSortsNumericallyByAmount() throws Exception {
        JsonNode body = getJson("/api/orders?sort=price,asc&size=200");

        BigDecimal previous = BigDecimal.valueOf(Long.MIN_VALUE);
        for (JsonNode order : body.get("content")) {
            BigDecimal current = order.at("/price/amount").decimalValue();
            assertThat(current).isGreaterThanOrEqualTo(previous);
            previous = current;
        }
    }

    @Test
    void pagingIsStableWhenSortKeyIsNotUnique() throws Exception {
        Set<String> ids = new HashSet<>();
        for (int page = 0; page < 7; page++) {
            for (JsonNode order : getJson("/api/orders?sort=status,desc&size=20&page=" + page).get("content")) {
                ids.add(order.get("id").asText());
            }
        }
        assertThat(ids).hasSize(123);
    }

    @Test
    void unknownSortFieldIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders?sort=bogus,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported sort field 'bogus'"));
    }

    @Test
    void detailReturnsServerDecidedActions() throws Exception {
        mockMvc.perform(get("/api/orders/ord-00000000/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("ord-00000000"))
                .andExpect(jsonPath("$.netAmount.currency").value("USD"))
                .andExpect(jsonPath("$.exchangeRate").value(1.3357))
                .andExpect(jsonPath("$.warnings.length()").value(6))
                .andExpect(jsonPath("$.availableActions[0]").value("ACCEPT"))
                .andExpect(jsonPath("$.availableActions[1]").value("REJECT"));
    }

    @Test
    void detailOfUnknownOrderIsNotFound() throws Exception {
        mockMvc.perform(get("/api/orders/ord-missing/detail"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found"));
    }

    @Test
    void actionOnExistingOrderSucceeds() throws Exception {
        mockMvc.perform(post("/api/orders/ord-00000000/actions/ACCEPT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void actionOnUnknownOrderIsNotFound() throws Exception {
        mockMvc.perform(post("/api/orders/ord-missing/actions/REJECT"))
                .andExpect(status().isNotFound());
    }

    @Test
    void corsAllowsWebDevServer() throws Exception {
        mockMvc.perform(options("/api/orders")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    private JsonNode getJson(String url) throws Exception {
        String json = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }
}
