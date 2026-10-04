package com.fams.auth;

final class NewDataScopeRow {

    private Long id;
    private final long authorizationId;
    private final DataScope scopeType;

    NewDataScopeRow(long authorizationId, DataScope scopeType) {
        this.authorizationId = authorizationId;
        this.scopeType = scopeType;
    }

    Long getId() {
        return id;
    }

    void setId(Long id) {
        this.id = id;
    }

    long getAuthorizationId() {
        return authorizationId;
    }

    DataScope getScopeType() {
        return scopeType;
    }
}
