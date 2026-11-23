package com.fams.masterdata;

import java.time.Instant;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the NumberAllocationMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class NumberAllocationRepository {

    private final NumberAllocationMapper mapper;

    NumberAllocationRepository(NumberAllocationMapper mapper) {
        this.mapper = mapper;
    }

    String activeCategoryPrefix(long id) {
        return mapper.activeCategoryPrefix(id);
    }

    int ensureSequence(String namespace, Instant now) {
        return mapper.ensureSequence(namespace, now);
    }

    Long lockSequence(String namespace) {
        return mapper.lockSequence(namespace);
    }

    int advanceSequence(String namespace, long previousValue, long lastValue, Instant now) {
        return mapper.advanceSequence(namespace, previousValue, lastValue, now);
    }

    int insert(NewNumberAllocationRow row) {
        return mapper.insert(row);
    }

    int finish(long id, String state) {
        return mapper.finish(id, state);
    }
}
