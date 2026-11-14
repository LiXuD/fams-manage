package com.fams.reminder;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("fams-product")
class NotificationDeliveryMetrics implements MeterBinder {
    private final JdbcTemplate db;

    NotificationDeliveryMetrics(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        registry.gauge("fams.notification.pending", this, metrics -> metrics.measure("""
                COUNT(CASE WHEN job_status IN ('QUEUED','RUNNING','RETRY_WAIT') THEN 1 END)
                """));
        registry.gauge("fams.notification.oldest.pending.seconds", this, metrics -> metrics.measure("""
                TIMESTAMPDIFF(SECOND,
                    MIN(CASE WHEN job_status IN ('QUEUED','RUNNING','RETRY_WAIT') THEN created_at END),
                    UTC_TIMESTAMP())
                """));
        registry.gauge("fams.notification.retries", this,
                metrics -> metrics.measure("SUM(GREATEST(attempts - 1, 0))"));
        registry.gauge("fams.notification.terminal.failures", this,
                metrics -> metrics.measure("COUNT(CASE WHEN job_status = 'FAILED' THEN 1 END)"));
        registry.gauge("fams.notification.completed.latency.seconds", this, metrics -> metrics.measure("""
                AVG(CASE WHEN job_status = 'SUCCEEDED' THEN TIMESTAMPDIFF(SECOND, created_at, updated_at) END)
                """));
    }

    private double measure(String expression) {
        Double value = db.queryForObject("SELECT COALESCE(" + expression
                + ", 0) FROM fams_durable_job WHERE job_kind = ?", Double.class, NotificationDeliveryQueue.KIND);
        return value == null ? 0 : value;
    }
}
