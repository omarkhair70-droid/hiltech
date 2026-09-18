#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = os.environ.get("FGA_API_URL", "http://127.0.0.1:8080").rstrip("/")


def request(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"content-type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            body = response.read().decode("utf-8")
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8")
        raise RuntimeError(f"{method} {path} -> {exc.code}: {body}") from exc


def direct(*types):
    return {
        "this": {}
    }, {
        "directly_related_user_types": [
            {"type": t} if isinstance(t, str) else t
            for t in types
        ]
    }


def computed(relation):
    return {"computedUserset": {"relation": relation}}


def from_relation(tupleset_relation, computed_relation):
    return {
        "tupleToUserset": {
            "tupleset": {"relation": tupleset_relation},
            "computedUserset": {"relation": computed_relation},
        }
    }


def union(*children):
    return {"union": {"child": list(children)}}


def build_model():
    org_relations = {}
    org_metadata = {}
    for relation in [
        "member",
        "employee",
        "owner",
        "finance",
        "warehouse",
        "procurement",
        "approval_delegate",
    ]:
        org_relations[relation], org_metadata[relation] = direct("user")

    project_hiltech, project_hiltech_meta = direct("organization")
    project_client, project_client_meta = direct("organization")
    project_pm, project_pm_meta = direct("user")
    project_tech, project_tech_meta = direct("user")

    work_project, work_project_meta = direct("project")
    work_assignee, work_assignee_meta = direct("user")

    asset_org, asset_org_meta = direct("organization")
    asset_project, asset_project_meta = direct("project")

    invoice_org, invoice_org_meta = direct("organization")
    invoice_client, invoice_client_meta = direct("organization")

    payroll_org, payroll_org_meta = direct("organization")

    po_org, po_org_meta = direct("organization")
    po_supplier, po_supplier_meta = direct("organization")

    return {
        "schema_version": "1.1",
        "type_definitions": [
            {"type": "user"},
            {
                "type": "organization",
                "relations": org_relations,
                "metadata": {"relations": org_metadata},
            },
            {
                "type": "project",
                "relations": {
                    "hiltech_org": project_hiltech,
                    "client_org": project_client,
                    "pm": project_pm,
                    "technician": project_tech,
                    "viewer": union(
                        computed("pm"),
                        computed("technician"),
                        from_relation("hiltech_org", "employee"),
                        from_relation("hiltech_org", "owner"),
                        from_relation("client_org", "member"),
                    ),
                    "variation_approver": union(
                        computed("pm"),
                        from_relation("hiltech_org", "owner"),
                        from_relation("hiltech_org", "approval_delegate"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "hiltech_org": project_hiltech_meta,
                        "client_org": project_client_meta,
                        "pm": project_pm_meta,
                        "technician": project_tech_meta,
                    }
                },
            },
            {
                "type": "work_order",
                "relations": {
                    "project": work_project,
                    "assignee": work_assignee,
                    "viewer": union(
                        computed("assignee"),
                        from_relation("project", "pm"),
                        from_relation("project", "viewer"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "project": work_project_meta,
                        "assignee": work_assignee_meta,
                    }
                },
            },
            {
                "type": "asset",
                "relations": {
                    "org": asset_org,
                    "project": asset_project,
                    "can_checkout": union(
                        from_relation("org", "warehouse"),
                        from_relation("project", "technician"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "org": asset_org_meta,
                        "project": asset_project_meta,
                    }
                },
            },
            {
                "type": "invoice",
                "relations": {
                    "org": invoice_org,
                    "client_org": invoice_client,
                    "viewer": union(
                        from_relation("org", "finance"),
                        from_relation("org", "owner"),
                        from_relation("client_org", "member"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "org": invoice_org_meta,
                        "client_org": invoice_client_meta,
                    }
                },
            },
            {
                "type": "payroll_run",
                "relations": {
                    "org": payroll_org,
                    "viewer": union(
                        from_relation("org", "finance"),
                        from_relation("org", "owner"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "org": payroll_org_meta,
                    }
                },
            },
            {
                "type": "purchase_order",
                "relations": {
                    "org": po_org,
                    "supplier_org": po_supplier,
                    "viewer": union(
                        from_relation("org", "procurement"),
                        from_relation("org", "owner"),
                        from_relation("supplier_org", "member"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "org": po_org_meta,
                        "supplier_org": po_supplier_meta,
                    }
                },
            },
        ],
    }


def tuple_key(user, relation, obj):
    return {"user": user, "relation": relation, "object": obj}


def write_tuples(store_id, model_id, tuples):
    request(
        "POST",
        f"/stores/{store_id}/write",
        {
            "writes": {"tuple_keys": tuples},
            "authorization_model_id": model_id,
        },
    )


def delete_tuples(store_id, model_id, tuples):
    request(
        "POST",
        f"/stores/{store_id}/write",
        {
            "deletes": {"tuple_keys": tuples},
            "authorization_model_id": model_id,
        },
    )


def check(store_id, model_id, user, relation, obj):
    result = request(
        "POST",
        f"/stores/{store_id}/check",
        {
            "tuple_key": tuple_key(user, relation, obj),
            "authorization_model_id": model_id,
        },
    )
    return bool(result["allowed"])


def expect(store_id, model_id, user, relation, obj, allowed):
    actual = check(store_id, model_id, user, relation, obj)
    label = f"{user} {relation} {obj}"
    print(f"CHECK {label}: actual={actual} expected={allowed}")
    if actual != allowed:
        raise AssertionError(f"{label}: expected {allowed}, got {actual}")


def wait_for_health():
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/healthz", timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            time.sleep(0.5)
    raise RuntimeError("OpenFGA did not become healthy")


def main():
    wait_for_health()

    store = request("POST", "/stores", {"name": "hiltech-spike"})
    store_id = store["id"]

    model_response = request(
        "POST",
        f"/stores/{store_id}/authorization-models",
        build_model(),
    )
    model_id = model_response["authorization_model_id"]

    tuples = [
        # HILTECH org.
        tuple_key("user:mohamed", "member", "organization:hiltech"),
        tuple_key("user:mohamed", "employee", "organization:hiltech"),
        tuple_key("user:mohamed", "owner", "organization:hiltech"),
        tuple_key("user:ahmed", "member", "organization:hiltech"),
        tuple_key("user:ahmed", "employee", "organization:hiltech"),
        tuple_key("user:ahmed", "finance", "organization:hiltech"),
        tuple_key("user:pm1", "member", "organization:hiltech"),
        tuple_key("user:pm1", "employee", "organization:hiltech"),
        tuple_key("user:tech1", "member", "organization:hiltech"),
        tuple_key("user:tech1", "employee", "organization:hiltech"),
        tuple_key("user:warehouse1", "member", "organization:hiltech"),
        tuple_key("user:warehouse1", "employee", "organization:hiltech"),
        tuple_key("user:warehouse1", "warehouse", "organization:hiltech"),
        tuple_key("user:proc1", "member", "organization:hiltech"),
        tuple_key("user:proc1", "employee", "organization:hiltech"),
        tuple_key("user:proc1", "procurement", "organization:hiltech"),

        # External orgs.
        tuple_key("user:client1", "member", "organization:client-acme"),
        tuple_key("user:supplier1", "member", "organization:supplier-x"),

        # Project A.
        tuple_key("organization:hiltech", "hiltech_org", "project:project-a"),
        tuple_key("organization:client-acme", "client_org", "project:project-a"),
        tuple_key("user:pm1", "pm", "project:project-a"),
        tuple_key("user:tech1", "technician", "project:project-a"),

        # Work / asset / money documents.
        tuple_key("project:project-a", "project", "work_order:wo-42"),
        tuple_key("user:tech1", "assignee", "work_order:wo-42"),
        tuple_key("organization:hiltech", "org", "asset:fluke-03"),
        tuple_key("project:project-a", "project", "asset:fluke-03"),
        tuple_key("organization:hiltech", "org", "invoice:inv-100"),
        tuple_key("organization:client-acme", "client_org", "invoice:inv-100"),
        tuple_key("organization:hiltech", "org", "payroll_run:2026-09"),
        tuple_key("organization:hiltech", "org", "purchase_order:po-77"),
        tuple_key("organization:supplier-x", "supplier_org", "purchase_order:po-77"),
    ]
    write_tuples(store_id, model_id, tuples)

    # Project / work isolation.
    expect(store_id, model_id, "user:mohamed", "viewer", "project:project-a", True)
    expect(store_id, model_id, "user:pm1", "viewer", "project:project-a", True)
    expect(store_id, model_id, "user:tech1", "viewer", "project:project-a", True)
    expect(store_id, model_id, "user:client1", "viewer", "project:project-a", True)
    expect(store_id, model_id, "user:supplier1", "viewer", "project:project-a", False)

    expect(store_id, model_id, "user:tech1", "viewer", "work_order:wo-42", True)
    expect(store_id, model_id, "user:client1", "viewer", "work_order:wo-42", True)
    expect(store_id, model_id, "user:supplier1", "viewer", "work_order:wo-42", False)

    # Physical custody permission.
    expect(store_id, model_id, "user:tech1", "can_checkout", "asset:fluke-03", True)
    expect(store_id, model_id, "user:warehouse1", "can_checkout", "asset:fluke-03", True)
    expect(store_id, model_id, "user:client1", "can_checkout", "asset:fluke-03", False)

    # Finance/privacy boundaries.
    expect(store_id, model_id, "user:mohamed", "viewer", "payroll_run:2026-09", True)
    expect(store_id, model_id, "user:ahmed", "viewer", "payroll_run:2026-09", True)
    expect(store_id, model_id, "user:tech1", "viewer", "payroll_run:2026-09", False)
    expect(store_id, model_id, "user:client1", "viewer", "payroll_run:2026-09", False)

    expect(store_id, model_id, "user:ahmed", "viewer", "invoice:inv-100", True)
    expect(store_id, model_id, "user:client1", "viewer", "invoice:inv-100", True)
    expect(store_id, model_id, "user:tech1", "viewer", "invoice:inv-100", False)
    expect(store_id, model_id, "user:supplier1", "viewer", "invoice:inv-100", False)

    # Supplier isolation.
    expect(store_id, model_id, "user:proc1", "viewer", "purchase_order:po-77", True)
    expect(store_id, model_id, "user:supplier1", "viewer", "purchase_order:po-77", True)
    expect(store_id, model_id, "user:client1", "viewer", "purchase_order:po-77", False)

    # Approval authority + temporary delegation lifecycle.
    expect(store_id, model_id, "user:mohamed", "variation_approver", "project:project-a", True)
    expect(store_id, model_id, "user:pm1", "variation_approver", "project:project-a", True)
    expect(store_id, model_id, "user:ahmed", "variation_approver", "project:project-a", False)

    delegation = tuple_key(
        "user:ahmed",
        "approval_delegate",
        "organization:hiltech",
    )
    write_tuples(store_id, model_id, [delegation])
    expect(store_id, model_id, "user:ahmed", "variation_approver", "project:project-a", True)

    delete_tuples(store_id, model_id, [delegation])
    expect(store_id, model_id, "user:ahmed", "variation_approver", "project:project-a", False)

    print(
        json.dumps(
            {
                "status": "PASS",
                "store_id": store_id,
                "authorization_model_id": model_id,
                "base_tuple_count": len(tuples),
                "types": 8,
                "notes": [
                    "Object/action authorization proven.",
                    "Field-level redaction remains server policy.",
                    "Temporary delegation proven by tuple lifecycle.",
                ],
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"SPIKE-09 FAILED: {exc}", file=sys.stderr)
        raise
