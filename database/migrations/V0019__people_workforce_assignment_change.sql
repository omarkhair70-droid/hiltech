-- Phase 3 / Slice 05 — Workforce Assignment Change Workflow.
-- Preserve assignment history by linking each replacement revision to the row it supersedes.

ALTER TABLE workforce_assignment
    ADD COLUMN supersedes_assignment_id uuid NULL;

ALTER TABLE workforce_assignment
    ADD CONSTRAINT fk_workforce_assignment_supersedes
    FOREIGN KEY (supersedes_assignment_id)
    REFERENCES workforce_assignment(id)
    ON DELETE RESTRICT;

ALTER TABLE workforce_assignment
    ADD CONSTRAINT chk_workforce_assignment_not_self_superseding
    CHECK (
        supersedes_assignment_id IS NULL
        OR supersedes_assignment_id <> id
    );

CREATE UNIQUE INDEX uq_workforce_assignment_superseded_once
    ON workforce_assignment (
        supersedes_assignment_id
    )
    WHERE supersedes_assignment_id IS NOT NULL;
