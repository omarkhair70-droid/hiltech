-- HILTECH OS first-slice query and invariant indexes.

CREATE INDEX idx_org_membership_user_state
    ON organization_membership (user_identity_id, state, valid_from, valid_until);
CREATE INDEX idx_team_membership_user
    ON team_membership (user_identity_id, valid_from, valid_until);
CREATE INDEX idx_device_user_revoked
    ON device (user_identity_id, revoked_at);

CREATE INDEX idx_idempotent_actor_created
    ON idempotent_operation (actor_user_id, created_at DESC);
CREATE INDEX idx_idempotent_unfinished
    ON idempotent_operation (created_at) WHERE completed_at IS NULL;
CREATE INDEX idx_audit_target_time
    ON audit_event (target_type, target_id, occurred_at DESC);
CREATE INDEX idx_audit_actor_time
    ON audit_event (actor_user_id, occurred_at DESC);
CREATE INDEX idx_audit_correlation
    ON audit_event (correlation_id);
CREATE INDEX idx_audit_trace
    ON audit_event (trace_id) WHERE trace_id IS NOT NULL;

CREATE INDEX idx_config_lookup
    ON config_revision (scope_type, scope_organization_id, family, code, lifecycle_state);
CREATE INDEX idx_config_supersedes ON config_revision (supersedes_id);
CREATE INDEX idx_config_effective ON config_revision (effective_from, effective_to);

CREATE INDEX idx_project_org_state ON project (organization_id, lifecycle_state);
CREATE INDEX idx_project_manager_state ON project (project_manager_id, lifecycle_state);
CREATE INDEX idx_project_client_state ON project (client_organization_id, lifecycle_state);
CREATE INDEX idx_site_client_state ON site (client_organization_id, status);
CREATE INDEX idx_project_site_project_state ON project_site (project_id, lifecycle_state);
CREATE INDEX idx_project_site_site_state ON project_site (site_id, lifecycle_state);
CREATE INDEX idx_area_parent ON area (parent_area_id);

CREATE INDEX idx_work_project_state_plan ON work_order (project_id, lifecycle_state, planned_start);
CREATE INDEX idx_work_site_state ON work_order (site_id, lifecycle_state);
CREATE INDEX idx_work_review_queue ON work_order (submitted_at)
    WHERE lifecycle_state = 'SUBMITTED_FOR_REVIEW';
CREATE INDEX idx_work_project_site ON work_order (project_site_id);
CREATE UNIQUE INDEX uq_work_current_binding ON work_policy_binding (work_order_id)
    WHERE superseded_at IS NULL;
CREATE INDEX idx_instruction_work_revision ON work_instruction_revision (work_order_id, instruction_revision DESC);
CREATE UNIQUE INDEX uq_work_active_assignment_target
    ON work_assignment (work_order_id, target_type, target_id)
    WHERE state = 'ACTIVE';
CREATE INDEX idx_assignment_work_state ON work_assignment (work_order_id, state);
CREATE INDEX idx_assignment_target_state ON work_assignment (target_type, target_id, state);
CREATE INDEX idx_blocker_work_state ON work_blocker (work_order_id, state);
CREATE INDEX idx_review_work_version ON work_review_decision (work_order_id, submitted_work_version);
CREATE INDEX idx_requirement_work_state
    ON work_requirement_instance (work_order_id, requirement_family, satisfaction_state);

CREATE INDEX idx_storage_warehouse ON storage_location (warehouse_id);
CREATE INDEX idx_storage_project_site ON storage_location (project_id, site_id);
CREATE INDEX idx_storage_parent ON storage_location (parent_storage_location_id);
CREATE INDEX idx_asset_org_state ON asset (organization_id, lifecycle_state);
CREATE INDEX idx_asset_serial ON asset (serial_number);
CREATE INDEX idx_asset_type ON asset (asset_type_definition_id);
CREATE UNIQUE INDEX uq_asset_primary_active_tag ON asset_tag (asset_id)
    WHERE active = true AND is_primary = true;
CREATE INDEX idx_asset_tag_asset ON asset_tag (asset_id);
CREATE INDEX idx_asset_tag_provider ON asset_tag (provider_external_id) WHERE provider_external_id IS NOT NULL;
CREATE INDEX idx_asset_movement_time ON asset_movement (asset_id, occurred_at DESC);
CREATE INDEX idx_asset_movement_custodian
    ON asset_movement (to_custodian_target_type, to_custodian_target_id, occurred_at DESC);
CREATE INDEX idx_asset_movement_work ON asset_movement (work_order_id);
CREATE INDEX idx_asset_movement_project_site ON asset_movement (project_id, site_id);
CREATE INDEX idx_custody_location ON asset_custody_projection (current_storage_location_id);
CREATE INDEX idx_custody_target ON asset_custody_projection (current_custodian_target_type, current_custodian_target_id);
CREATE INDEX idx_custody_project_work ON asset_custody_projection (current_project_id, current_work_order_id);
CREATE INDEX idx_stock_balance_location ON stock_balance (storage_location_id);
CREATE INDEX idx_stock_movement_time ON stock_movement (stock_item_id, occurred_at DESC);
CREATE INDEX idx_stock_movement_from ON stock_movement (from_storage_location_id);
CREATE INDEX idx_stock_movement_to ON stock_movement (to_storage_location_id);
CREATE INDEX idx_stock_movement_work ON stock_movement (work_order_id);
CREATE INDEX idx_stock_movement_project_site ON stock_movement (project_id, site_id);
CREATE INDEX idx_asset_reservation_state ON asset_reservation (asset_id, state);
CREATE INDEX idx_asset_reservation_project ON asset_reservation (project_id, state);
CREATE INDEX idx_asset_reservation_work ON asset_reservation (work_order_id);
CREATE INDEX idx_stock_reservation_state ON stock_reservation (stock_item_id, state);
CREATE INDEX idx_stock_reservation_project ON stock_reservation (project_id, state);
CREATE INDEX idx_stock_reservation_work ON stock_reservation (work_order_id);

CREATE INDEX idx_evidence_work_state ON evidence (work_order_id, storage_state);
CREATE INDEX idx_evidence_target ON evidence (target_type, target_id);
CREATE INDEX idx_evidence_org_created ON evidence (organization_id, created_at DESC);
CREATE INDEX idx_evidence_sha ON evidence (sha256);
CREATE INDEX idx_evidence_session_state ON evidence_upload_session (evidence_id, state);
CREATE INDEX idx_evidence_session_expiry ON evidence_upload_session (expires_at);

CREATE INDEX idx_auth_projection_state ON authorization_relation_projection (projection_state, updated_at);
CREATE INDEX idx_auth_projection_source ON authorization_relation_projection (source_type, source_id);
CREATE INDEX idx_auth_projection_subject ON authorization_relation_projection (subject_type, subject_id);
CREATE INDEX idx_auth_projection_object ON authorization_relation_projection (object_type, object_id);
CREATE INDEX idx_auth_outbox_pending ON authorization_projection_outbox (created_at)
    WHERE completed_at IS NULL;
CREATE INDEX idx_auth_outbox_relation_version ON authorization_projection_outbox (relation_key, source_version);
