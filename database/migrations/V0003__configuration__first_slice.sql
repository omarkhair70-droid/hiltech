-- HILTECH OS typed, versioned operating configuration.

CREATE TABLE config_revision (
    id uuid PRIMARY KEY,
    scope_type varchar(16) NOT NULL CHECK (scope_type IN ('SYSTEM','ORGANIZATION')),
    scope_organization_id uuid NULL REFERENCES organization(id) ON DELETE RESTRICT,
    family varchar(64) NOT NULL CHECK (family IN (
        'work-types','assignment-policies','readiness-policies','evidence-policies',
        'review-policies','tracking-policies','asset-types','stock-categories',
        'storage-locations','code-policies','project-health-policies','templates'
    )),
    code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    description text NULL,
    lifecycle_state varchar(16) NOT NULL CHECK (lifecycle_state IN ('DRAFT','ACTIVE','SUPERSEDED','RETIRED')),
    revision_number integer NOT NULL CHECK (revision_number > 0),
    effective_from timestamptz NULL,
    effective_to timestamptz NULL,
    supersedes_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    change_reason text NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    activated_at timestamptz NULL,
    activated_by uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (
        (scope_type = 'SYSTEM' AND scope_organization_id IS NULL) OR
        (scope_type = 'ORGANIZATION' AND scope_organization_id IS NOT NULL)
    ),
    CHECK (effective_to IS NULL OR effective_from IS NULL OR effective_to > effective_from),
    CHECK (supersedes_id IS NULL OR supersedes_id <> id),
    UNIQUE NULLS NOT DISTINCT (scope_type, scope_organization_id, family, code, revision_number)
);

CREATE UNIQUE INDEX uq_config_revision_active
    ON config_revision (scope_type, scope_organization_id, family, code) NULLS NOT DISTINCT
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE assignment_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    allowed_target_types varchar(32)[] NOT NULL,
    min_assignees integer NULL,
    max_assignees integer NULL,
    lead_required boolean NOT NULL DEFAULT false,
    lead_relationship_type varchar(64) NULL,
    required_role_codes varchar(64)[] NOT NULL DEFAULT '{}',
    required_skill_or_certification_codes varchar(64)[] NOT NULL DEFAULT '{}',
    allow_cross_team_assignment boolean NOT NULL DEFAULT false,
    allow_external_subcontractor boolean NOT NULL DEFAULT false,
    reassignment_requires_reason boolean NOT NULL DEFAULT false,
    reassignment_approval_policy_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    assignment_must_be_online boolean NOT NULL DEFAULT true,
    CHECK (cardinality(allowed_target_types) > 0),
    CHECK (min_assignees IS NULL OR min_assignees >= 0),
    CHECK (max_assignees IS NULL OR max_assignees >= 0),
    CHECK (min_assignees IS NULL OR max_assignees IS NULL OR max_assignees >= min_assignees)
);

CREATE TABLE readiness_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    allow_offline_evaluation boolean NOT NULL DEFAULT true,
    all_required_must_be_satisfied boolean NOT NULL DEFAULT true
);

CREATE TABLE readiness_policy_requirement (
    id uuid PRIMARY KEY,
    config_revision_id uuid NOT NULL REFERENCES readiness_policy(config_revision_id) ON DELETE RESTRICT,
    requirement_key varchar(64) NOT NULL,
    requirement_type_code varchar(64) NOT NULL,
    label varchar(240) NOT NULL,
    required boolean NOT NULL DEFAULT true,
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    UNIQUE (config_revision_id, requirement_key)
);

CREATE TABLE evidence_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    system_max_size_bytes bigint NOT NULL DEFAULT 16777216
        CHECK (system_max_size_bytes > 0 AND system_max_size_bytes <= 16777216)
);

CREATE TABLE evidence_policy_requirement (
    id uuid PRIMARY KEY,
    config_revision_id uuid NOT NULL REFERENCES evidence_policy(config_revision_id) ON DELETE RESTRICT,
    requirement_key varchar(64) NOT NULL,
    evidence_type_code varchar(64) NOT NULL,
    stage varchar(32) NOT NULL CHECK (stage IN ('BEFORE_START','BEFORE_SUBMIT','BEFORE_ACCEPT')),
    min_count integer NOT NULL DEFAULT 1 CHECK (min_count >= 0),
    max_count integer NULL CHECK (max_count IS NULL OR max_count >= 0),
    offline_capture_allowed boolean NOT NULL DEFAULT true,
    allowed_content_types varchar(160)[] NOT NULL DEFAULT '{}',
    classification_code varchar(64) NOT NULL,
    retention_policy_code varchar(64) NULL,
    client_visibility_mode varchar(64) NOT NULL,
    reviewer_relationship_code varchar(64) NULL,
    security_scan_class varchar(32) NOT NULL CHECK (security_scan_class IN ('GENERATED_TRUSTED_FORMAT','NATIVE_MEDIA','ARBITRARY_FILE')),
    CHECK (max_count IS NULL OR max_count >= min_count),
    UNIQUE (config_revision_id, requirement_key)
);

CREATE TABLE review_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    exact_submitted_version_required boolean NOT NULL DEFAULT true
);

CREATE TABLE review_policy_step (
    id uuid PRIMARY KEY,
    config_revision_id uuid NOT NULL REFERENCES review_policy(config_revision_id) ON DELETE RESTRICT,
    step_key varchar(64) NOT NULL,
    reviewer_relationship_code varchar(64) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    UNIQUE (config_revision_id, step_key)
);

CREATE TABLE field_tracking_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    tracking_enabled boolean NOT NULL DEFAULT false,
    offline_buffering_allowed boolean NOT NULL DEFAULT false,
    retention_policy_code varchar(64) NULL
);

CREATE TABLE template_definition (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    template_type varchar(64) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    structured_definition jsonb NOT NULL
);

CREATE TABLE work_type_definition (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    assignment_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    readiness_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    evidence_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    review_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    tracking_policy_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    asset_requirement_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    material_requirement_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    checklist_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    instruction_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    completion_policy_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    default_priority_code varchar(64) NULL,
    default_progress_weight numeric(20,6) NOT NULL DEFAULT 1.0 CHECK (default_progress_weight >= 0),
    counts_toward_project_progress boolean NOT NULL DEFAULT true
);

CREATE TABLE asset_type_definition (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    calibration_required boolean NOT NULL DEFAULT false,
    high_value_or_restricted boolean NOT NULL DEFAULT false
);

CREATE TABLE stock_item_category_definition (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    default_unit_of_measure_code varchar(32) NOT NULL
);

CREATE TABLE code_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    target_object_type varchar(64) NOT NULL,
    prefix varchar(32) NULL,
    include_year boolean NOT NULL DEFAULT false,
    separator varchar(8) NOT NULL DEFAULT '-',
    sequence_scope varchar(32) NOT NULL CHECK (sequence_scope IN ('ORGANIZATION','PROJECT','SITE','OBJECT_TYPE')),
    sequence_padding integer NOT NULL CHECK (sequence_padding > 0),
    manual_override_allowed boolean NOT NULL DEFAULT false,
    uniqueness_scope varchar(32) NOT NULL CHECK (uniqueness_scope IN ('ORGANIZATION','PROJECT','SITE','CLIENT_ORGANIZATION')),
    reset_rule varchar(16) NOT NULL CHECK (reset_rule IN ('NEVER','YEARLY'))
);

CREATE TABLE project_health_policy (
    config_revision_id uuid PRIMARY KEY REFERENCES config_revision(id) ON DELETE RESTRICT,
    aggregation_precedence varchar(32)[] NOT NULL DEFAULT '{}',
    signal_rules jsonb NOT NULL DEFAULT '{}'::jsonb
);
