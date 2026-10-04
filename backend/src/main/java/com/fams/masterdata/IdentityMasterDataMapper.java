package com.fams.masterdata;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
interface IdentityMasterDataMapper {

    @Select("""
            SELECT o.id, o.code, o.name, o.parent_id,
                   p.version AS parent_version, o.status, o.version, o.created_at, o.updated_at
              FROM fams_organization o
              LEFT JOIN fams_organization p ON p.id = o.parent_id
             ORDER BY o.id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<OrganizationRow> listOrganizations(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM fams_organization")
    long countOrganizations();

    @Select("""
            SELECT o.id, o.code, o.name, o.parent_id,
                   p.version AS parent_version, o.status, o.version, o.created_at, o.updated_at
              FROM fams_organization o
              LEFT JOIN fams_organization p ON p.id = o.parent_id
             WHERE o.id = #{id}
            """)
    OrganizationRow findOrganization(@Param("id") long id);

    @Select("""
            SELECT o.id, o.code, o.name, o.parent_id,
                   p.version AS parent_version, o.status, o.version, o.created_at, o.updated_at
              FROM fams_organization o
              LEFT JOIN fams_organization p ON p.id = o.parent_id
             WHERE o.code_normalized = #{code}
            """)
    OrganizationRow findOrganizationByCode(@Param("code") String normalizedCode);

    @Select("SELECT COUNT(*) FROM fams_organization WHERE code_normalized = #{code}")
    int countOrganizationCode(@Param("code") String normalizedCode);

    @Insert("""
            INSERT INTO fams_organization
                (code, code_normalized, name, parent_id, status, version,
                 created_at, updated_at, created_by, updated_by)
            VALUES
                (#{code}, #{normalizedCode}, #{name}, #{parentId}, 'ACTIVE', 0,
                 #{now}, #{now}, #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrganization(NewOrganizationRow row);

    @Update("""
            UPDATE fams_organization
               SET code = #{code}, code_normalized = #{normalizedCode}, name = #{name},
                   parent_id = #{parentId}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateOrganization(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("code") String code,
            @Param("normalizedCode") String normalizedCode,
            @Param("name") String name,
            @Param("parentId") Long parentId,
            @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_organization
               SET status = 'DISABLED', version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{expectedVersion} AND status = 'ACTIVE'
            """)
    int disableOrganization(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Select("SELECT COUNT(*) FROM fams_organization WHERE parent_id = #{id} AND status = 'ACTIVE'")
    int countActiveChildOrganizations(@Param("id") long organizationId);

    @Select("SELECT COUNT(*) FROM fams_employee WHERE organization_id = #{id} AND status = 'ACTIVE'")
    int countActiveEmployees(@Param("id") long organizationId);

    @Select("""
            SELECT COUNT(*)
              FROM fams_account_scope_organization aso
              JOIN fams_account_data_scope ads ON ads.id = aso.data_scope_id
              JOIN fams_account_authorization aa ON aa.id = ads.authorization_id
             WHERE aso.organization_id = #{id}
               AND aa.superseded_at IS NULL
               AND aa.authorization_status = 'ACTIVE'
            """)
    int countActiveAuthorizationScopes(@Param("id") long organizationId);

    @Select("""
            WITH RECURSIVE ancestors AS (
                SELECT id, parent_id FROM fams_organization WHERE id = #{parentId}
                UNION ALL
                SELECT o.id, o.parent_id
                  FROM fams_organization o
                  JOIN ancestors a ON o.id = a.parent_id
            )
            SELECT COUNT(*) FROM ancestors WHERE id = #{organizationId}
            """)
    int countOrganizationCycle(
            @Param("organizationId") long organizationId,
            @Param("parentId") long parentId);

    @Select("""
            SELECT e.id, e.employee_no, e.name, e.organization_id,
                   o.version AS organization_version, e.status, e.version, e.created_at, e.updated_at
              FROM fams_employee e
              JOIN fams_organization o ON o.id = e.organization_id
             ORDER BY e.id
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<EmployeeRow> listEmployees(@Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM fams_employee")
    long countEmployees();

    @Select("""
            SELECT e.id, e.employee_no, e.name, e.organization_id,
                   o.version AS organization_version, e.status, e.version, e.created_at, e.updated_at
              FROM fams_employee e
              JOIN fams_organization o ON o.id = e.organization_id
             WHERE e.id = #{id}
            """)
    EmployeeRow findEmployee(@Param("id") long id);

    @Select("""
            SELECT e.id, e.employee_no, e.name, e.organization_id,
                   o.version AS organization_version, e.status, e.version, e.created_at, e.updated_at
              FROM fams_employee e
              JOIN fams_organization o ON o.id = e.organization_id
             WHERE e.employee_no_normalized = #{employeeNo}
            """)
    EmployeeRow findEmployeeByNumber(@Param("employeeNo") String normalizedEmployeeNo);

    @Select("SELECT COUNT(*) FROM fams_employee WHERE employee_no_normalized = #{employeeNo}")
    int countEmployeeNumber(@Param("employeeNo") String normalizedEmployeeNo);

    @Insert("""
            INSERT INTO fams_employee
                (employee_no, employee_no_normalized, name, organization_id,
                 mobile_ciphertext, mobile_lookup_hash, status, version,
                 created_at, updated_at, created_by, updated_by)
            VALUES
                (#{employeeNo}, #{normalizedEmployeeNo}, #{name}, #{organizationId},
                 #{phoneCiphertext}, #{phoneLookupHash}, 'ACTIVE', 0,
                 #{now}, #{now}, #{actorId}, #{actorId})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertEmployee(NewEmployeeRow row);

    @Update("""
            UPDATE fams_employee
               SET employee_no = #{employeeNo}, employee_no_normalized = #{normalizedEmployeeNo},
                   name = #{name}, organization_id = #{organizationId},
                   mobile_ciphertext = COALESCE(#{phoneCiphertext}, mobile_ciphertext),
                   mobile_lookup_hash = COALESCE(#{phoneLookupHash}, mobile_lookup_hash),
                   version = version + 1, updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updateEmployee(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("employeeNo") String employeeNo,
            @Param("normalizedEmployeeNo") String normalizedEmployeeNo,
            @Param("name") String name,
            @Param("organizationId") long organizationId,
            @Param("phoneCiphertext") byte[] phoneCiphertext,
            @Param("phoneLookupHash") byte[] phoneLookupHash,
            @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_employee
               SET status = #{status}, version = version + 1,
                   updated_at = #{now}, updated_by = #{actorId}
             WHERE id = #{id} AND version = #{expectedVersion} AND status != #{status}
            """)
    int changeEmployeeStatus(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Update("""
            UPDATE fams_system_account
               SET status = 'DISABLED', auth_epoch = auth_epoch + 1,
                   version = version + 1, updated_at = #{now}, updated_by = #{actorId}
             WHERE employee_id = #{employeeId} AND status != 'DISABLED'
            """)
    int disableEmployeeAccount(
            @Param("employeeId") long employeeId,
            @Param("actorId") long actorId,
            @Param("now") Instant now);

    @Select("""
            SELECT payload_sha256, response_body_json
              FROM fams_idempotency_record
             WHERE actor_account_id = #{actorId}
               AND operation_id = #{operationId}
               AND idempotency_key = #{idempotencyKey}
               AND expires_at > #{now}
            """)
    IdempotencyRow findIdempotency(
            @Param("actorId") long actorId,
            @Param("operationId") String operationId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("now") Instant now);

    @Insert("""
            INSERT INTO fams_idempotency_record
                (actor_account_id, operation_id, idempotency_key, payload_sha256,
                 result_status, http_status, response_body_json, resource_type,
                 resource_id, resource_version, created_at, completed_at, expires_at)
            VALUES
                (#{actorId}, #{operationId}, #{idempotencyKey}, #{fingerprint},
                 'SUCCEEDED', #{httpStatus}, #{responseJson}, #{resourceType},
                 #{resourceId}, #{resourceVersion}, #{now}, #{now}, #{expiresAt})
            """)
    int insertIdempotency(
            @Param("actorId") long actorId,
            @Param("operationId") String operationId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("fingerprint") String fingerprint,
            @Param("httpStatus") int httpStatus,
            @Param("responseJson") String responseJson,
            @Param("resourceType") String resourceType,
            @Param("resourceId") Long resourceId,
            @Param("resourceVersion") Long resourceVersion,
            @Param("now") Instant now,
            @Param("expiresAt") Instant expiresAt);

    @Insert("""
            INSERT INTO fams_security_event
                (account_id, event_type, request_id, occurred_at, detail_json)
            VALUES (#{actorId}, 'MASTER_DATA_CHANGED', #{requestId}, #{now}, #{detailJson})
            """)
    int insertAudit(
            @Param("actorId") long actorId,
            @Param("requestId") String requestId,
            @Param("now") Instant now,
            @Param("detailJson") String detailJson);
}
