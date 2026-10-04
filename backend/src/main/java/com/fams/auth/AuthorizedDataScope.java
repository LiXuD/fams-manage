package com.fams.auth;

import java.util.List;

record AuthorizedDataScope(
        DataScope scopeType,
        List<VersionedReferenceRow> organizations,
        List<VersionedReferenceRow> warehouses) {

    AuthorizedDataScope {
        organizations = List.copyOf(organizations);
        warehouses = List.copyOf(warehouses);
    }
}
