package com.ata.salaryservices.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:file:probetest?mode=memory&cache=shared",
        "app.data.import-enabled=false",
        "app.probe-demo.enabled=true"
})
class ProbeDemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void probesStartUpAndReadinessIncludesTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db").doesNotExist());
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void refusingTrafficFailsReadinessButNotLiveness() throws Exception {
        mockMvc.perform(post("/demo/probes/readiness/refuse")).andExpect(status().isOk());
        try {
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
            mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        } finally {
            mockMvc.perform(post("/demo/probes/readiness/accept")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
