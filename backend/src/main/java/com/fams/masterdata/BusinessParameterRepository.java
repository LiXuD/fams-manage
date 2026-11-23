package com.fams.masterdata;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** Repository boundary for the BusinessParameterMapper aggregate; pass-through persistence calls only. */
@Repository
@Profile("fams-product")
class BusinessParameterRepository {

    private final BusinessParameterMapper mapper;

    BusinessParameterRepository(BusinessParameterMapper mapper) {
        this.mapper = mapper;
    }

    BusinessParameterState lockState() {
        return mapper.lockState();
    }

    BusinessParameterState state() {
        return mapper.state();
    }

    BusinessParameterRow find(long id) {
        return mapper.find(id);
    }

    long count() {
        return mapper.count();
    }

    List<BusinessParameterRow> list(int offset, int limit) {
        return mapper.list(offset, limit);
    }

    String accountUsername(long id) {
        return mapper.accountUsername(id);
    }

    int supersede(long id) {
        return mapper.supersede(id);
    }

    int insert(NewBusinessParameterRow row) {
        return mapper.insert(row);
    }

    int activate(long expectedVersion, long version, long id) {
        return mapper.activate(expectedVersion, version, id);
    }
}
