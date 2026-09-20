-- Phase 3 / Slice 04 — Onboarding + Basic Self-Service.
-- Adds typed/versioned onboarding policy, durable onboarding cases,
-- explicit manual requirement resolution, and recoverable identity-invitation state.

ALTER TABLE config_revision
    DROP CONSTRAINT config_revision_family_check;

ALTER TABLE config_revision
    ADD CONSTRAINT config_revision_family_check
    CHECK (
        family IN (
            'work-types',
            'assignment-policies',
            'readiness-policies',
            'evidence-policies',
            'review-policies',
            'tracking-policies',
            'asset-types',
            'stock-categories',
            'storage-locations',
            'code-policies',
            'project-health-policies',
            'templates',
            'onboarding-policies'
        )
    );

CREATE TABLE onboarding_policy (
    config_revision_id uuid PRIMARY KEY
        REFERENCES config_revision(id) ON DELETE RESTRICT
);

CREATE TABLE onboarding_policy_requirement (
    id uuid PRIMARY KEY,
    config_revision_id uuid NOT NULL
        REFERENCES onboarding_policy(config_revision_id)
        ON DELETE RESTRICT,
    requirement_key varchar(64) NOT NULL
        CHECK (
            requirement_key ~ '^[A-Z][A-Z0-9_-]{0,63}$'
        ),
    requirement_type varchar(32) NOT NULL
        CHECK (
            requirement_type IN (
                'PROFILE_FIELD',
                'IDENTITY_READY',
                'WORKFORCE_ASSIGNMENT',
                'EMPLOYEE_DOCUMENT',
                'CERTIFICATION',
                'MANUAL_CONFIRMATION'
            )
        ),
    label varchar(240) NOT NULL
        CHECK (
            length(trim(label)) BETWEEN 1 AND 240
        ),
    responsibility varchar(16) NOT NULL
        CHECK (
            responsibility IN (
                'EMPLOYEE',
                'HILTECH',
                'SHARED'
            )
        ),
    blocking boolean NOT NULL DEFAULT true,
    waiver_allowed boolean NOT NULL DEFAULT false,
    self_service_visible boolean NOT NULL DEFAULT true,
    employee_may_submit boolean NOT NULL DEFAULT false,
    evidence_required boolean NOT NULL DEFAULT false,
    profile_field_code varchar(32) NULL,
    document_type_code varchar(80) NULL
        CHECK (
            document_type_code IS NULL
            OR document_type_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    certification_type_code varchar(80) NULL
        CHECK (
            certification_type_code IS NULL
            OR certification_type_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    manual_confirmation_code varchar(80) NULL
        CHECK (
            manual_confirmation_code IS NULL
            OR manual_confirmation_code ~ '^[A-Z][A-Z0-9_-]{0,79}$'
        ),
    sort_order integer NOT NULL DEFAULT 0
        CHECK (sort_order >= 0),

    UNIQUE (
        config_revision_id,
        requirement_key
    ),

    CHECK (
        (
            requirement_type = 'PROFILE_FIELD'
            AND profile_field_code IN ('MOBILE', 'EMAIL')
            AND document_type_code IS NULL
            AND certification_type_code IS NULL
            AND manual_confirmation_code IS NULL
            AND evidence_required = false
        )
        OR
        (
            requirement_type = 'IDENTITY_READY'
            AND profile_field_code IS NULL
            AND document_type_code IS NULL
            AND certification_type_code IS NULL
            AND manual_confirmation_code IS NULL
            AND evidence_required = false
        )
        OR
        (
            requirement_type = 'WORKFORCE_ASSIGNMENT'
            AND profile_field_code IS NULL
            AND document_type_code IS NULL
            AND certification_type_code IS NULL
            AND manual_confirmation_code IS NULL
            AND evidence_required = false
        )
        OR
        (
            requirement_type = 'EMPLOYEE_DOCUMENT'
            AND profile_field_code IS NULL
            AND document_type_code IS NOT NULL
            AND certification_type_code IS NULL
            AND manual_confirmation_code IS NULL
        )
        OR
        (
            requirement_type = 'CERTIFICATION'
            AND profile_field_code IS NULL
            AND document_type_code IS NULL
            AND certification_type_code IS NOT NULL
            AND manual_confirmation_code IS NULL
            AND evidence_required = false
        )
        OR
        (
            requirement_type = 'MANUAL_CONFIRMATION'
            AND profile_field_code IS NULL
            AND document_type_code IS NULL
            AND certification_type_code IS NULL
            AND manual_confirmation_code IS NOT NULL
            AND evidence_required = false
        )
    )
);

CREATE INDEX idx_onboarding_policy_requirement_order
    ON onboarding_policy_requirement (
        config_revision_id,
        sort_order,
        requirement_key
    );

CREATE TABLE onboarding_case (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    policy_config_revision_id uuid NOT NULL
        REFERENCES onboarding_policy(config_revision_id)
        ON DELETE RESTRICT,
    state varchar(16) NOT NULL
        CHECK (state IN ('OPEN', 'ACTIVATED')),
    started_at timestamptz NOT NULL,
    started_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    activated_at timestamptz NULL,
    activated_by_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    CONSTRAINT fk_onboarding_case_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    CHECK (
        (
            state = 'OPEN'
            AND activated_at IS NULL
            AND activated_by_user_id IS NULL
        )
        OR
        (
            state = 'ACTIVATED'
            AND activated_at IS NOT NULL
            AND activated_by_user_id IS NOT NULL
        )
    ),
    CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_onboarding_case_one_open_employee
    ON onboarding_case (employee_id)
    WHERE state = 'OPEN';

CREATE INDEX idx_onboarding_case_org_state
    ON onboarding_case (
        organization_id,
        state,
        employee_id
    );

CREATE TABLE onboarding_manual_requirement_resolution (
    id uuid PRIMARY KEY,
    onboarding_case_id uuid NOT NULL
        REFERENCES onboarding_case(id) ON DELETE RESTRICT,
    requirement_key varchar(64) NOT NULL
        CHECK (
            requirement_key ~ '^[A-Z][A-Z0-9_-]{0,63}$'
        ),
    resolution varchar(16) NOT NULL
        CHECK (
            resolution IN ('SATISFIED', 'WAIVED')
        ),
    reason varchar(500) NULL
        CHECK (
            reason IS NULL
            OR length(trim(reason)) BETWEEN 1 AND 500
        ),
    resolved_at timestamptz NOT NULL,
    resolved_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    UNIQUE (
        onboarding_case_id,
        requirement_key
    ),

    CHECK (
        resolution <> 'WAIVED'
        OR reason IS NOT NULL
    )
);

CREATE TABLE employee_identity_invitation (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    requested_login varchar(320) NOT NULL
        CHECK (
            length(trim(requested_login)) BETWEEN 3 AND 320
        ),
    auth_provider varchar(255) NOT NULL
        CHECK (
            length(trim(auth_provider)) BETWEEN 1 AND 255
        ),
    provider_subject varchar(255) NULL
        CHECK (
            provider_subject IS NULL
            OR length(trim(provider_subject)) BETWEEN 1 AND 255
        ),
    user_identity_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    state varchar(24) NOT NULL
        CHECK (
            state IN (
                'PENDING_PROVIDER',
                'PROVIDER_CREATED',
                'LOCAL_PENDING',
                'READY',
                'FAILED_RETRYABLE'
            )
        ),
    delivery_mode varchar(32) NOT NULL
        CHECK (
            delivery_mode IN (
                'EMAIL_ACTION_LINK',
                'TEMPORARY_PASSWORD_HANDOFF'
            )
        ),
    delivery_state varchar(24) NOT NULL
        CHECK (
            delivery_state IN (
                'PENDING',
                'SENT',
                'READY',
                'NOT_CONFIGURED',
                'FAILED_RETRYABLE'
            )
        ),
    safe_failure_code varchar(120) NULL,
    created_at timestamptz NOT NULL,
    created_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),

    CONSTRAINT fk_employee_identity_invitation_employee_org
        FOREIGN KEY (employee_id, organization_id)
        REFERENCES employee(id, organization_id)
        ON DELETE RESTRICT,

    UNIQUE (user_identity_id),
    UNIQUE (auth_provider, provider_subject),

    CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_employee_identity_invitation_current_employee
    ON employee_identity_invitation (employee_id)
    WHERE state IN (
        'PENDING_PROVIDER',
        'PROVIDER_CREATED',
        'LOCAL_PENDING',
        'READY'
    );

CREATE INDEX idx_employee_identity_invitation_org_state
    ON employee_identity_invitation (
        organization_id,
        state,
        employee_id
    );
