[Reading 174 lines from line 1 (total: 175 lines, 0 remaining)]

-- The field app reads only safe metadata. Publication/binding authority remains an
-- external/control-plane concern; this migration does not invent a role or approval endpoint.

CREATE TABLE controlled_document (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NULL REFERENCES site(id) ON DELETE RESTRICT,
    code varchar(64) NOT NULL,
    title varchar(240) NOT NULL,
    document_type_code varchar(64) NOT NULL,
    lifecycle_state varchar(24) NOT NULL
        CHECK (lifecycle_state IN ('ACTIVE','WITHDRAWN')),
    current_revision_id uuid NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (length(btrim(code)) > 0),
    CHECK (length(btrim(title)) > 0),
    CHECK (length(btrim(document_type_code)) > 0),
    UNIQUE (project_id, code),
    UNIQUE (id, project_id)
);

CREATE TABLE controlled_document_revision (
    id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES controlled_document(id) ON DELETE RESTRICT,
    revision_code varchar(64) NOT NULL,
    revision_sequence integer NOT NULL CHECK (revision_sequence >= 1),
    lifecycle_state varchar(24) NOT NULL
        CHECK (lifecycle_state IN ('APPROVED','SUPERSEDED','WITHDRAWN')),
    content_evidence_id uuid NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    external_source_ref varchar(512) NULL,
    approved_at timestamptz NOT NULL,
    approved_by_source_ref varchar(255) NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (length(btrim(revision_code)) > 0),
    CHECK (
        (content_evidence_id IS NOT NULL AND external_source_ref IS NULL)
        OR
        (content_evidence_id IS NULL AND external_source_ref IS NOT NULL
            AND length(btrim(external_source_ref)) > 0)
    ),
    UNIQUE (document_id, revision_code),
    UNIQUE (document_id, revision_sequence),
    UNIQUE (id, document_id)
);

ALTER TABLE controlled_document
    ADD CONSTRAINT fk_controlled_document_current_revision
    FOREIGN KEY (current_revision_id, id)
    REFERENCES controlled_document_revision(id, document_id)
    ON DELETE RESTRICT;

CREATE FUNCTION enforce_controlled_document_current_revision()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    revision_state varchar(24);
BEGIN
    IF NEW.current_revision_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT lifecycle_state INTO revision_state
      FROM controlled_document_revision
     WHERE id = NEW.current_revision_id
       AND document_id = NEW.id;
    IF NOT FOUND OR revision_state <> 'APPROVED' THEN
        RAISE EXCEPTION 'Current controlled document revision must be APPROVED and belong to the document'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_controlled_document_current_revision
BEFORE INSERT OR UPDATE OF current_revision_id
ON controlled_document
FOR EACH ROW EXECUTE FUNCTION enforce_controlled_document_current_revision();

CREATE TABLE work_document_revision_binding (
    id uuid PRIMARY KEY,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    requirement_instance_id uuid NOT NULL REFERENCES work_requirement_instance(id) ON DELETE RESTRICT,
    document_id uuid NOT NULL REFERENCES controlled_document(id) ON DELETE RESTRICT,
    revision_id uuid NOT NULL,
    bound_work_version bigint NOT NULL CHECK (bound_work_version >= 1),
    source_ref varchar(255) NULL,
    bound_at timestamptz NOT NULL,
    superseded_at timestamptz NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (superseded_at IS NULL OR superseded_at >= bound_at),
    FOREIGN KEY (revision_id, document_id)
        REFERENCES controlled_document_revision(id, document_id)
        ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_work_document_revision_binding_active
    ON work_document_revision_binding (requirement_instance_id)
    WHERE superseded_at IS NULL;

CREATE INDEX idx_work_document_revision_binding_work
    ON work_document_revision_binding (work_order_id, requirement_instance_id)
    WHERE superseded_at IS NULL;



CREATE FUNCTION enforce_work_document_binding_context()
RETURNS trigger
LANGUAGE plpgsql
AS $
DECLARE
    wo_org uuid;
    wo_project uuid;
    wo_site uuid;
    wo_version bigint;
    req_family varchar(24);
    req_work uuid;
    doc_org uuid;
    doc_project uuid;
    doc_site uuid;
    doc_current uuid;
    doc_state varchar(24);
    rev_state varchar(24);
BEGIN
    SELECT organization_id, project_id, site_id, version
      INTO wo_org, wo_project, wo_site, wo_version
      FROM work_order
     WHERE id = NEW.work_order_id;

    SELECT requirement_family, work_order_id
      INTO req_family, req_work
      FROM work_requirement_instance
     WHERE id = NEW.requirement_instance_id;

    SELECT organization_id, project_id, site_id, current_revision_id, lifecycle_state
      INTO doc_org, doc_project, doc_site, doc_current, doc_state
      FROM controlled_document
     WHERE id = NEW.document_id;

    SELECT lifecycle_state
      INTO rev_state
      FROM controlled_document_revision
     WHERE id = NEW.revision_id
       AND document_id = NEW.document_id;

    IF wo_org IS NULL OR req_family IS NULL OR doc_org IS NULL OR rev_state IS NULL
       OR req_family <> 'DOCUMENT'
       OR req_work <> NEW.work_order_id
       OR doc_org <> wo_org
       OR doc_project <> wo_project
       OR (doc_site IS NOT NULL AND doc_site <> wo_site)
       OR doc_state <> 'ACTIVE'
       OR rev_state <> 'APPROVED'
       OR doc_current IS DISTINCT FROM NEW.revision_id
       OR NEW.bound_work_version <> wo_version THEN
        RAISE EXCEPTION 'Work document binding must pin the current approved in-scope revision at the current WorkOrder version'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$;

CREATE TRIGGER trg_work_document_binding_context
BEFORE INSERT ON work_document_revision_binding
FOR EACH ROW EXECUTE FUNCTION enforce_work_document_binding_context();

CREATE FUNCTION guard_controlled_document_revision_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Controlled document revisions cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.document_id <> OLD.document_id
       OR NEW.revision_code <> OLD.revision_code
       OR NEW.revision_sequence <> OLD.revision_sequence
       OR NEW.content_evidence_id IS DISTINCT FROM OLD.content_evidence_id
       OR NEW.external_source_ref IS DISTINCT FROM OLD.external_source_ref
       OR NEW.approved_at <> OLD.approved_at
       OR NEW.approved_by_source_ref IS DISTINCT FROM OLD.approved_by_source_ref
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Controlled document revision content/provenance is immutable'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.lifecycle_state <> 'APPROVED'
       AND EXISTS (
            SELECT 1 FROM controlled_document d
            WHERE d.current_revision_id = OLD.id
       ) THEN
        RAISE EXCEPTION 'Current controlled document revision must remain APPROVED until a new current revision is selected'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.lifecycle_state = 'SUPERSEDED' AND NEW.lifecycle_state = 'APPROVED'
       OR OLD.lifecycle_state = 'WITHDRAWN' AND NEW.lifecycle_state <> 'WITHDRAWN' THEN
        RAISE EXCEPTION 'Controlled document revision lifecycle cannot move backwards'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER controlled_document_revision_guard
BEFORE UPDATE OR DELETE ON controlled_document_revision
FOR EACH ROW EXECUTE FUNCTION guard_controlled_document_revision_update();

CREATE FUNCTION guard_work_document_binding_history()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Work document bindings cannot be deleted'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.work_order_id <> OLD.work_order_id
       OR NEW.requirement_instance_id <> OLD.requirement_instance_id
       OR NEW.document_id <> OLD.document_id
       OR NEW.revision_id <> OLD.revision_id
       OR NEW.bound_work_version <> OLD.bound_work_version
       OR NEW.source_ref IS DISTINCT FROM OLD.source_ref
       OR NEW.bound_at <> OLD.bound_at
       OR (OLD.superseded_at IS NOT NULL AND NEW.superseded_at IS DISTINCT FROM OLD.superseded_at) THEN
        RAISE EXCEPTION 'Work document binding identity is immutable; supersede and insert a new binding'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER work_document_binding_history_guard
BEFORE UPDATE OR DELETE ON work_document_revision_binding
FOR EACH ROW EXECUTE FUNCTION guard_work_document_binding_history();

[executed on device: DESKTOP-L9O2KSP (022bb228-ba9d-462a-adf0-934892797c7b)]