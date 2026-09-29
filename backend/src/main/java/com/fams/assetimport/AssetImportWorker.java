package com.fams.assetimport;

import com.fams.job.DurableJobService;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Claims one import per poll; a crashed process leaves its lease for another instance to reclaim. */
@Configuration
@Profile("fams-product")
@EnableScheduling
@ConditionalOnProperty(name = "fams.asset-import.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AssetImportWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AssetImportWorker.class);
    private final String owner = "asset-import-" + UUID.randomUUID();
    private final DurableJobService jobs;
    private final AssetImportPublicationService publication;
    private final AssetImportRollbackService rollbacks;

    public AssetImportWorker(DurableJobService jobs, AssetImportPublicationService publication,
            AssetImportRollbackService rollbacks) {
        this.jobs = jobs;
        this.publication = publication;
        this.rollbacks = rollbacks;
    }

    @Scheduled(fixedDelayString = "${fams.asset-import.worker.delay-ms:5000}",
            initialDelayString = "${fams.asset-import.worker.initial-delay-ms:5000}")
    public void poll() {
        jobs.claim("ASSET_IMPORT", owner, Duration.ofMinutes(45)).ifPresent(this::publish);
        jobs.claim("ASSET_IMPORT_ROLLBACK", owner, Duration.ofMinutes(45)).ifPresent(this::rollback);
    }

    private void publish(DurableJobService.Claim claim) {
        try {
            jobs.complete(claim, claim.businessReference(), () -> publication.publish(claim));
        } catch (RuntimeException failure) {
            LOG.warn("Asset import job {} failed", claim.id(), failure);
            try {
                publication.recordFailure(claim, failure);
            } catch (RuntimeException recordingFailure) {
                LOG.warn("Asset import job {} could not record its failure; lease recovery remains available",
                        claim.id(), recordingFailure);
            }
        }
    }

    private void rollback(DurableJobService.Claim claim) {
        try {
            jobs.complete(claim, claim.businessReference(), () -> rollbacks.rollback(claim));
        } catch (RuntimeException failure) {
            LOG.warn("Asset import rollback job {} failed", claim.id(), failure);
            try {
                rollbacks.recordFailure(claim, failure);
            } catch (RuntimeException recordingFailure) {
                LOG.warn("Asset import rollback job {} could not record its failure",
                        claim.id(), recordingFailure);
            }
        }
    }
}
