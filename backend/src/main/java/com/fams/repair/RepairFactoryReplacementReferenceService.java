package com.fams.repair;

import com.fams.auth.RequisitionActorContext;
import com.fams.masterdata.FactoryReplacementCatalogPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application query boundary keeps handler authorization and catalog selection in one transaction. */
@Service
@Profile("fams-product")
@Transactional
public class RepairFactoryReplacementReferenceService {
    private final RepairFactoryService factory;
    private final FactoryReplacementCatalogPort catalogs;

    public RepairFactoryReplacementReferenceService(RepairFactoryService factory,
            FactoryReplacementCatalogPort catalogs) {
        this.factory = factory;
        this.catalogs = catalogs;
    }

    public Object references(RequisitionActorContext.Actor actor, long repair, long round,
            String kind, int page, int size) {
        if (!Boolean.TRUE.equals(factory.replacements(actor, repair, round).get("canRecord"))) {
            throw new RepairFailure(404, "REPAIR_FACTORY_REPLACEMENT_NOT_AVAILABLE");
        }
        return catalogs.references(kind, page, size);
    }
}
