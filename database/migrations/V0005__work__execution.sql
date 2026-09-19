-- HILTECH OS WorkOrder execution and review schema.

CREATE TABLE work_order (
    id uuid PRIMARY KEY,
    work_order_code varchar(64) NOT NULL,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NOT NULL REFERENCES site(id) ON DELETE RESTRICT,
    project_site_id uuid NULL,
    area_id uuid NULL REFERENCES area(id) ON DELETE RESTRICT,
    work_package_id uuid NULL,
    title varchar(240) NOT NULL,
    description text NULL,
    lifecycle_state varchar(32) NOT NULL CHECK (lifecycle_state IN (
        'DRAFT','PLANNED','ASSIGNED','IN_PROGRESS','BLOCKED',
        'SUBMITTED_FOR_REVIEW','REWORK_REQUIRED','ACCEPTED','CLOSED','CANCELLED'
    )),
    readiness_state varchar(24) NOT NULL CHECK (readiness_state IN ('NOT_EVALUATED','READY','BLOCKED')),
    planned_start timestamptz NULL,
    planned_end timestamptz NULL,
    actual_start timestamptz NULL,
    submitted_at timestamptz NULL,
    accepted_at timestamptz NULL,
    closed_at timestamptz NULL,
    priority_code varchar(64) NOT NULL,
    current_instruction_revision_id uuid NULL,
    current_policy_binding_id uuid NULL,
    progress_weight numeric(20,6) NOT NULL DEFAULT 1.0 CHECK (progress_weight >= 0),
    counts_toward_project_progress boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (planned_end IS NULL OR planned_start IS NULL OR planned_end >= planned_start),
    CHECK (accepted_at IS NULL OR lifecycle_state IN ('ACCEPTED','CLOSED')),
    CHECK (closed_at IS NULL OR lifecycle_state = 'CLOSED'),
    CHECK (counts_toward_project_progress = false OR progress_weight > 0),
    UNIQUE (project_id, work_order_code),
    CONSTRAINT fk_work_order_project_site_context
        FOREIGN KEY (project_site_id, project_id, site_id)
        REFERENCES project_site(id, project_id, site_id) ON DELETE RESTRICT
);

CREATE TABLE work_policy_binding (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    binding_revision integer NOT NULL CHECK (binding_revision > 0),
    work_type_definition_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    work_type_revision integer NOT NULL CHECK (work_type_revision > 0),
    assignment_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    assignment_policy_revision integer NOT NULL CHECK (assignment_policy_revision > 0),
    readiness_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    readiness_policy_revision integer NOT NULL CHECK (readiness_policy_revision > 0),
    evidence_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    evidence_policy_revision integer NOT NULL CHECK (evidence_policy_revision > 0),
    review_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    review_policy_revision integer NOT NULL CHECK (review_policy_revision > 0),
    tracking_policy_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    tracking_policy_revision integer NULL,
    checklist_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    checklist_template_revision integer NULL,
    instruction_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    instruction_template_revision integer NULL,
    binding_created_at timestamptz NOT NULL,
    binding_created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    superseded_at timestamptz NULL,
    superseded_by_binding_id uuid NULL REFERENCES work_policy_binding(id) ON DELETE RESTRICT,
    rebind_reason text NULL,
    CHECK (superseded_by_binding_id IS NULL OR superseded_by_binding_id <> id),
    UNIQUE (work_order_id, binding_revision)
);

CREATE TABLE work_instruction_revision (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    instruction_revision integer NOT NULL CHECK (instruction_revision > 0),
    source_instruction_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    source_instruction_template_revision integer NULL,
    payload_schema_version integer NOT NULL CHECK (payload_schema_version > 0),
    structured_payload_json jsonb NOT NULL,
    summary_text text NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    supersedes_instruction_revision_id uuid NULL REFERENCES work_instruction_revision(id) ON DELETE RESTRICT,
    change_reason text NULL,
    correlation_id varchar(128) NOT NULL,
    CHECK (supersedes_instruction_revision_id IS NULL OR supersedes_instruction_revision_id <> id),
    UNIQUE (work_order_id, instruction_revision)
);

ALTER TABLE work_order
    ADD CONSTRAINT fk_work_order_policy_binding
        FOREIGN KEY (current_policy_binding_id) REFERENCES work_policy_binding(id) ON DELETE RESTRICT,
    ADD CONSTRAINT fk_work_order_instruction_revision
        FOREIGN KEY (current_instruction_revision_id) REFERENCES work_instruction_revision(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_work_order_required_pointers
        CHECK (
            lifecycle_state = 'DRAFT' OR
            (current_policy_binding_id IS NOT NULL AND current_instruction_revision_id IS NOT NULL)
        );

CREATE TABLE work_checklist_item_instance (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    source_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    source_template_revision integer NULL,
    item_key varchar(64) NOT NULL,
    label varchar(240) NOT NULL,
    required boolean NOT NULL,
    sort_order integer NOT NULL CHECK (sort_order >= 0),
    completion_state varchar(24) NOT NULL CHECK (completion_state IN ('PENDING','COMPLETE','NOT_APPLICABLE')),
    completed_at timestamptz NULL,
    completed_by uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    evidence_requirement_key varchar(64) NULL,
    notes text NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (completion_state = 'PENDING' OR completed_at IS NOT NULL OR completion_state = 'NOT_APPLICABLE'),
    UNIQUE (work_order_id, item_key)
);

CREATE TABLE work_assignment (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    target_type varchar(40) NOT NULL CHECK (target_type IN ('USER','CREW','TEAM','SUBCONTRACTOR_ORGANIZATION')),
    target_id uuid NOT NULL,
    lead boolean NOT NULL DEFAULT false,
    assigned_at timestamptz NOT NULL,
    assigned_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NULL,
    state varchar(16) NOT NULL CHECK (state IN ('ACTIVE','ENDED','REPLACED')),
    source_operation_id uuid NOT NULL UNIQUE,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE TABLE work_blocker (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    blocker_type_code varchar(64) NOT NULL,
    severity_code varchar(64) NULL,
    description text NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    owner_target_type varchar(64) NULL,
    owner_target_id uuid NULL,
    state varchar(16) NOT NULL CHECK (state IN ('OPEN','RESOLVED','WAIVED')),
    resolved_at timestamptz NULL,
    resolution text NULL,
    client_visible boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (state = 'OPEN' OR resolved_at IS NOT NULL)
);

CREATE TABLE work_review_decision (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    submitted_work_version bigint NOT NULL CHECK (submitted_work_version >= 1),
    review_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    review_policy_revision integer NOT NULL CHECK (review_policy_revision > 0),
    review_step_id varchar(64) NULL,
    reviewer_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    decision varchar(24) NOT NULL CHECK (decision IN ('ACCEPT','REWORK','REJECT','TECHNICAL_HOLD')),
    reason text NULL,
    decided_at timestamptz NOT NULL,
    delegation_id uuid NULL,
    correlation_id varchar(128) NOT NULL,
    UNIQUE (work_order_id, submitted_work_version, review_step_id, reviewer_user_id, decision)
);

CREATE TABLE work_requirement_instance (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    requirement_family varchar(24) NOT NULL CHECK (requirement_family IN ('READINESS','EVIDENCE','ASSET','MATERIAL','DOCUMENT')),
    requirement_key varchar(64) NOT NULL,
    source_config_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    source_config_revision integer NULL,
    required boolean NOT NULL,
    satisfaction_state varchar(24) NOT NULL CHECK (satisfaction_state IN ('PENDING','SATISFIED','BLOCKED','WAIVED','NOT_APPLICABLE')),
    satisfied_by_ref varchar(255) NULL,
    waived boolean NOT NULL DEFAULT false,
    waiver_ref uuid NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (work_order_id, requirement_family, requirement_key)
);
