package com.fams.auth;

/**
 * Row-visibility breadth attached to a permission grant, ranging from the actor's own records
 * only, through organization and warehouse subsets, up to enterprise-wide visibility.
 */
public enum DataScope {
    SELF,
    OWN_ORGANIZATION,
    OWN_ORGANIZATION_AND_DESCENDANTS,
    SPECIFIED_ORGANIZATIONS,
    SPECIFIED_WAREHOUSES,
    ENTERPRISE
}
