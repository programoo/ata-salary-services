package com.ata.salaryservices.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Learning aid for readme_probes_k8s.md: flips this pod's liveness/readiness state so you can watch
 * Kubernetes react. Only exists when {@code app.probe-demo.enabled=true}.
 */
@RestController
@RequestMapping("/demo/probes")
@ConditionalOnProperty(name = "app.probe-demo.enabled", havingValue = "true")
public class ProbeDemoController {

    private final ApplicationEventPublisher publisher;

    public ProbeDemoController(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Readiness probe starts failing: the pod is removed from the Service but keeps running. */
    @PostMapping("/readiness/refuse")
    public Map<String, String> refuseTraffic() {
        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.REFUSING_TRAFFIC);
        return Map.of("readiness", ReadinessState.REFUSING_TRAFFIC.name());
    }

    /** Readiness probe passes again: the pod is added back to the Service. */
    @PostMapping("/readiness/accept")
    public Map<String, String> acceptTraffic() {
        AvailabilityChangeEvent.publish(publisher, this, ReadinessState.ACCEPTING_TRAFFIC);
        return Map.of("readiness", ReadinessState.ACCEPTING_TRAFFIC.name());
    }

    /** Liveness probe starts failing: the kubelet restarts the container. There is no undo. */
    @PostMapping("/liveness/break")
    public Map<String, String> breakLiveness() {
        AvailabilityChangeEvent.publish(publisher, this, LivenessState.BROKEN);
        return Map.of("liveness", LivenessState.BROKEN.name());
    }
}
