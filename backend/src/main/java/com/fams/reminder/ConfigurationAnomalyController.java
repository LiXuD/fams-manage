package com.fams.reminder;

import com.fams.auth.MasterDataActorContext;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("fams-product")
@Validated
@RequestMapping("/api/v1/configuration-anomalies")
class ConfigurationAnomalyController {
    private final ConfigurationAnomalyService anomalies;
    private final MasterDataActorContext actors;

    ConfigurationAnomalyController(ConfigurationAnomalyService anomalies, MasterDataActorContext actors) {
        this.anomalies = anomalies;
        this.actors = actors;
    }

    @GetMapping
    ConfigurationAnomalyService.AnomalyPage list(
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
        actors.requireSystemAdministrator();
        return anomalies.list(page, pageSize);
    }

    @GetMapping("/{anomalyId}")
    ResponseEntity<ConfigurationAnomalyService.Anomaly> detail(@PathVariable String anomalyId) {
        actors.requireSystemAdministrator();
        try {
            long id = Long.parseLong(anomalyId);
            return ResponseEntity.ofNullable(id > 0 ? anomalies.detail(id) : null);
        } catch (NumberFormatException failure) {
            return ResponseEntity.notFound().build();
        }
    }
}
