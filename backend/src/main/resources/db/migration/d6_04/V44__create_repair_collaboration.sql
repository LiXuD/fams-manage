-- Invitation-bound technical opinions do not grant lifecycle or warehouse processing authority.
CREATE TABLE fams_repair_invitation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 repair_id BIGINT NOT NULL,
 technician_account_id BIGINT NOT NULL,
 inviter_account_id BIGINT NOT NULL,
 invitation_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 reason VARCHAR(1000) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 invited_at DATETIME(3) NOT NULL,
 ended_at DATETIME(3) NULL,
 end_reason VARCHAR(1000) NULL,
 active_technician_id BIGINT GENERATED ALWAYS AS (CASE WHEN invitation_status='ACTIVE' THEN technician_account_id ELSE NULL END) STORED,
 UNIQUE KEY uk_repair_active_invitation(repair_id,active_technician_id),
 INDEX ix_repair_technician(technician_account_id,invitation_status,id),
 CONSTRAINT fk_repair_invitation_request FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_repair_invitation_technician FOREIGN KEY(technician_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT fk_repair_invitation_inviter FOREIGN KEY(inviter_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_repair_invitation_status CHECK(invitation_status IN ('ACTIVE','REVOKED','CLOSED')),
 CONSTRAINT ck_repair_invitation_end CHECK((invitation_status='ACTIVE' AND ended_at IS NULL) OR (invitation_status<>'ACTIVE' AND ended_at IS NOT NULL)),
 CONSTRAINT ck_repair_invitation_version CHECK(version>=0)
);
CREATE TABLE fams_repair_opinion (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 invitation_id BIGINT NOT NULL,
 repair_id BIGINT NOT NULL,
 before_version BIGINT NOT NULL,
 judgement VARCHAR(2000) NOT NULL,
 method VARCHAR(2000) NOT NULL,
 opinion_result VARCHAR(24) NOT NULL,
 occurred_at DATETIME(3) NOT NULL,
 attachment_ids_json LONGTEXT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 actor_username VARCHAR(128) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_repair_opinion_version(invitation_id,before_version),
 CONSTRAINT fk_repair_opinion_invitation FOREIGN KEY(invitation_id) REFERENCES fams_repair_invitation(id),
 CONSTRAINT fk_repair_opinion_request FOREIGN KEY(repair_id) REFERENCES fams_repair_request(id),
 CONSTRAINT fk_repair_opinion_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 CONSTRAINT ck_repair_opinion_version CHECK(before_version>=0),
 CONSTRAINT ck_repair_opinion_result CHECK(opinion_result IN ('RESTORED','FACTORY_SUGGESTED','SCRAP_SUGGESTED','NEEDS_WORK')),
 CONSTRAINT ck_repair_opinion_attachments CHECK(JSON_VALID(attachment_ids_json))
);
CREATE TABLE fams_repair_collaboration_attachment (
 object_id CHAR(36) NOT NULL PRIMARY KEY,
 invitation_id BIGINT NOT NULL,
 actor_account_id BIGINT NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 recorded_at DATETIME(3) NOT NULL,
 CONSTRAINT fk_repair_collaboration_attachment_object FOREIGN KEY(object_id) REFERENCES fams_attachment(object_id),
 CONSTRAINT fk_repair_collaboration_attachment_invitation FOREIGN KEY(invitation_id) REFERENCES fams_repair_invitation(id),
 CONSTRAINT fk_repair_collaboration_attachment_actor FOREIGN KEY(actor_account_id) REFERENCES fams_system_account(id),
 INDEX ix_repair_collaboration_attachment(invitation_id,object_id)
);
