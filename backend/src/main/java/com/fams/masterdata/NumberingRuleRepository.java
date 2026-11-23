package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the NumberingRuleMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class NumberingRuleRepository {

    private final NumberingRuleMapper mapper;

    NumberingRuleRepository(NumberingRuleMapper mapper) {
        this.mapper = mapper;
    }

    Long lockConfiguration() {
        return mapper.lockConfiguration();
    }

    int countActivePrefixOtherThan(long id, String prefix) {
        return mapper.countActivePrefixOtherThan(id, prefix);
    }

    NumberingRuleRow find(long id) {
        return mapper.find(id);
    }

    NumberingRuleRow findForUpdate(long id) {
        return mapper.findForUpdate(id);
    }

    NumberingRuleRow findByCode(String code) {
        return mapper.findByCode(code);
    }

    NumberingRuleRow findActiveTarget(String target) {
        return mapper.findActiveTarget(target);
    }

    long count() {
        return mapper.count();
    }

    List<NumberingRuleRow> list(int offset, int limit) {
        return mapper.list(offset, limit);
    }

    List<String> activeCategoryPrefixes() {
        return mapper.activeCategoryPrefixes();
    }

    int insert(NewNumberingRuleRow row) {
        return mapper.insert(row);
    }

    int update(long id, long version, String target, String mode, String fixedPrefix,
            String datePart, int sequenceLength, long actorId, Instant now) {
        return mapper.update(id, version, target, mode, fixedPrefix, datePart, sequenceLength,
                actorId, now);
    }

    int changeStatus(long id, long version, String status, long actorId, Instant now) {
        return mapper.changeStatus(id, version, status, actorId, now);
    }
}
