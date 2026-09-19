-- Phase 2 / Slice 04 — minimal Approval Engine foundation.

CREATE TABLE approval_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    authority_key varchar(80) NOT NULL
        CHECK (authority_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    principal_type varchar(16) NOT NULL
        CHECK (principal_type IN ('USER', 'TEAM')),
    principal_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    principal_team_id uuid NULL
        REFERENCES team(id) ON DELETE RESTRICT,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    active boolean NOT NULL DEFAULT true,
    created_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CHECK (
        effective_to IS NULL
        OR effective_to > effective_from
    ),
    CHECK (
        (
            principal_type = 'USER'
            AND principal_user_id IS NOT NULL
            AND principal_team_id IS NULL
        )
        OR
        (
            principal_type = 'TEAM'
            AND principal_team_id IS NOT NULL
            AND principal_user_id IS NULL
        )
    )
);

CREATE UNIQUE INDEX uq_approval_authority_active_key
    ON approval_authority_binding (
        organization_id,
        authority_key
    )
    WHERE active = true;

CREATE INDEX idx_approval_authority_principal_user
    ON approval_authority_binding (
        principal_user_id
    )
    WHERE principal_user_id IS NOT NULL;

CREATE INDEX idx_approval_authority_principal_team
    ON approval_authority_binding (
        principal_team_id
    )
    WHERE principal_team_id IS NOT NULL;

CREATE TABLE approval_policy_version (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    policy_key varchar(96) NOT NULL
        CHECK (policy_key ~ '^[A-Z][A-Z0-9_]{2,95}$'),
    version_number integer NOT NULL
        CHECK (version_number >= 1),
    lifecycle_state varchar(16) NOT NULL
        CHECK (
            lifecycle_state IN (
                'DRAFT',
                'ACTIVE',
                'RETIRED'
            )
        ),
    approval_required boolean NOT NULL,
    step_mode varchar(32) NOT NULL
        CHECK (
            step_mode IN (
                'NO_APPROVAL_REQUIRED',
                'SINGLE'
            )
        ),
    authority_key varchar(80) NULL,
    reason_required boolean NOT NULL DEFAULT false,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    created_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    UNIQUE (
        organization_id,
        policy_key,
        version_number
    ),
    CHECK (
        effective_to IS NULL
        OR effective_to > effective_from
    ),
    CHECK (
        (
            approval_required = false
            AND step_mode = 'NO_APPROVAL_REQUIRED'
            AND authority_key IS NULL
        )
        OR
        (
            approval_required = true
            AND step_mode = 'SINGLE'
            AND authority_key IS NOT NULL
            AND authority_key ~ '^[A-Z][A-Z0-9_]{2,79}$'
        )
    )
);

CREATE UNIQUE INDEX uq_approval_policy_active_key
    ON approval_policy_version (
        organization_id,
        policy_key
    )
    WHERE lifecycle_state = 'ACTIVE';

CREATE TABLE approval_request (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    subject_type varchar(80) NOT NULL
        CHECK (subject_type ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    subject_id uuid NOT NULL,
    subject_version bigint NOT NULL
        CHECK (subject_version >= 1),
    policy_key varchar(96) NOT NULL,
    policy_version integer NOT NULL
        CHECK (policy_version >= 1),
    requester_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    state varchar(32) NOT NULL
        CHECK (
            state IN (
                'PENDING',
                'APPROVED',
                'REJECTED',
                'CHANGE_REQUESTED',
                'SUPERSEDED',
                'CANCELLED'
            )
        ),
    reason_code varchar(80) NOT NULL
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    safe_reason_summary varchar(500) NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    correlation_id varchar(128) NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    UNIQUE (
        organization_id,
        subject_type,
        subject_id,
        subject_version,
        policy_key
    ),
    FOREIGN KEY (
        organization_id,
        policy_key,
        policy_version
    )
        REFERENCES approval_policy_version (
            organization_id,
            policy_key,
            version_number
        )
        ON DELETE RESTRICT,
    CHECK (
        (
            state = 'PENDING'
            AND completed_at IS NULL
        )
        OR
        (
            state <> 'PENDING'
            AND completed_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_approval_request_subject
    ON approval_request (
        subject_type,
        subject_id,
        subject_version
    );

CREATE INDEX idx_approval_request_requester
    ON approval_request (
        requester_user_id,
        created_at DESC,
        id DESC
    );

CREATE TABLE approval_step (
    id uuid PRIMARY KEY,
    approval_request_id uuid NOT NULL
        REFERENCES approval_request(id) ON DELETE RESTRICT,
    sequence_number integer NOT NULL
        CHECK (sequence_number = 1),
    mode varchar(16) NOT NULL
        CHECK (mode = 'SINGLE'),
    authority_key varchar(80) NOT NULL
        CHECK (authority_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    state varchar(16) NOT NULL
        CHECK (
            state IN (
                'PENDING',
                'COMPLETED',
                'SUPERSEDED',
                'CANCELLED'
            )
        ),
    required_count integer NOT NULL DEFAULT 1
        CHECK (required_count = 1),
    UNIQUE (
        approval_request_id,
        sequence_number
    ),
    UNIQUE (
        id,
        approval_request_id
    )
);

CREATE TABLE approval_assignment (
    id uuid PRIMARY KEY,
    approval_request_id uuid NOT NULL,
    approval_step_id uuid NOT NULL,
    principal_type varchar(16) NOT NULL
        CHECK (principal_type IN ('USER', 'TEAM')),
    principal_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    principal_team_id uuid NULL
        REFERENCES team(id) ON DELETE RESTRICT,
    state varchar(16) NOT NULL
        CHECK (
            state IN (
                'ASSIGNED',
                'ACTED',
                'REVOKED'
            )
        ),
    assigned_at timestamptz NOT NULL,
    acted_at timestamptz NULL,
    FOREIGN KEY (
        approval_step_id,
        approval_request_id
    )
        REFERENCES approval_step (
            id,
            approval_request_id
        )
        ON DELETE RESTRICT,
    UNIQUE (
        id,
        approval_request_id
    ),
    CHECK (
        (
            principal_type = 'USER'
            AND principal_user_id IS NOT NULL
            AND principal_team_id IS NULL
        )
        OR
        (
            principal_type = 'TEAM'
            AND principal_team_id IS NOT NULL
            AND principal_user_id IS NULL
        )
    ),
    CHECK (
        (
            state = 'ASSIGNED'
            AND acted_at IS NULL
        )
        OR
        (
            state <> 'ASSIGNED'
        )
    )
);

CREATE INDEX idx_approval_assignment_user_state
    ON approval_assignment (
        principal_user_id,
        state,
        assigned_at DESC,
        id DESC
    )
    WHERE principal_user_id IS NOT NULL;

CREATE INDEX idx_approval_assignment_team_state
    ON approval_assignment (
        principal_team_id,
        state,
        assigned_at DESC,
        id DESC
    )
    WHERE principal_team_id IS NOT NULL;

CREATE TABLE approval_decision (
    id uuid PRIMARY KEY,
    approval_request_id uuid NOT NULL,
    approval_assignment_id uuid NOT NULL,
    actor_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    decision varchar(24) NOT NULL
        CHECK (
            decision IN (
                'APPROVE',
                'REJECT',
                'REQUEST_CHANGE'
            )
        ),
    decided_at timestamptz NOT NULL,
    comment_text varchar(1000) NULL,
    subject_version bigint NOT NULL
        CHECK (subject_version >= 1),
    operation_id uuid NOT NULL UNIQUE,
    correlation_id varchar(128) NULL,
    FOREIGN KEY (
        approval_assignment_id,
        approval_request_id
    )
        REFERENCES approval_assignment (
            id,
            approval_request_id
        )
        ON DELETE RESTRICT,
    UNIQUE (approval_assignment_id),
    CHECK (
        decision = 'APPROVE'
        OR (
            comment_text IS NOT NULL
            AND length(trim(comment_text)) > 0
        )
    )
);

CREATE INDEX idx_approval_decision_request
    ON approval_decision (
        approval_request_id,
        decided_at DESC,
        id DESC
    );
