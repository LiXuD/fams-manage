export type SystemAccountStatus = "ENABLED" | "LOCKED" | "DISABLED";

export type Role =
  | "EMPLOYEE"
  | "DEPARTMENT_ASSET_MANAGER"
  | "ASSET_ADMIN"
  | "WAREHOUSE_OPERATOR"
  | "TECH_ASSISTANT"
  | "AUDIT_READ_ONLY"
  | "SYSTEM_ADMIN";

export interface CurrentUser {
  id: string;
  username: string;
  status: SystemAccountStatus;
  roles: Role[];
  requiresInitialPasswordChange: boolean;
}

export interface DataScopeGrant {
  scopeType:
    | "SELF"
    | "OWN_ORGANIZATION"
    | "OWN_ORGANIZATION_AND_DESCENDANTS"
    | "SPECIFIED_ORGANIZATIONS"
    | "SPECIFIED_WAREHOUSES"
    | "ENTERPRISE";
  organizations: Array<{ id: string; version: number }>;
  warehouses: Array<{ id: string; version: number }>;
}

export interface PermissionSnapshot {
  userId: string;
  permissions: string[];
  dataScopes: DataScopeGrant[];
  fieldPermissions: string[];
  authorizationVersion: number;
}

export interface SessionResponse {
  csrfToken: string;
  idleExpiresAt: string;
  absoluteExpiresAt: string;
  requiresInitialPasswordChange: boolean;
  user: CurrentUser;
}

export interface ErrorResponse {
  code: string;
  message: string;
  requestId: string;
  fieldErrors: Record<string, string>;
}
