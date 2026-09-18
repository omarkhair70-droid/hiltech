#!/usr/bin/env python3
import json
import os
import time
import urllib.request

BASE = os.environ.get("FGA_API_URL", "http://127.0.0.1:8082").rstrip("/")


def request(method, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"content-type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as response:
        body = response.read().decode("utf-8")
        return json.loads(body) if body else {}


def direct(type_name):
    return (
        {"this": {}},
        {"directly_related_user_types": [{"type": type_name}]},
    )


def computed(relation):
    return {"computedUserset": {"relation": relation}}


def union(*children):
    return {"union": {"child": list(children)}}


def wait_ready():
    deadline = time.time() + 60
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(BASE + "/healthz", timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError("OpenFGA did not become ready")


def main():
    wait_ready()

    user_pm, user_pm_meta = direct("user")
    user_tech, user_tech_meta = direct("user")
    user_supervisor, user_supervisor_meta = direct("user")
    work_project, work_project_meta = direct("project")
    work_assignee, work_assignee_meta = direct("user")
    work_reviewer, work_reviewer_meta = direct("user")
    work_viewer, work_viewer_meta = direct("user")

    model = {
        "schema_version": "1.1",
        "type_definitions": [
            {"type": "user"},
            {
                "type": "project",
                "relations": {
                    "pm": user_pm,
                    "technician": user_tech,
                    "supervisor": user_supervisor,
                    "viewer": union(
                        computed("pm"),
                        computed("technician"),
                        computed("supervisor"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "pm": user_pm_meta,
                        "technician": user_tech_meta,
                        "supervisor": user_supervisor_meta,
                    }
                },
            },
            {
                "type": "work_order",
                "relations": {
                    "project": work_project,
                    "assignee": work_assignee,
                    "reviewer": work_reviewer,
                    "viewer": union(
                        work_viewer,
                        computed("assignee"),
                        computed("reviewer"),
                    ),
                },
                "metadata": {
                    "relations": {
                        "project": work_project_meta,
                        "assignee": work_assignee_meta,
                        "reviewer": work_reviewer_meta,
                        "viewer": work_viewer_meta,
                    }
                },
            },
        ],
    }

    store = request("POST", "/stores", {"name": "hiltech-spike15"})
    store_id = store["id"]
    model_result = request(
        "POST",
        f"/stores/{store_id}/authorization-models",
        model,
    )
    model_id = model_result["authorization_model_id"]

    tuples = [
        {"user": "user:pm1", "relation": "pm", "object": "project:project-a"},
        {"user": "user:tech1", "relation": "technician", "object": "project:project-a"},
        {"user": "user:supervisor1", "relation": "supervisor", "object": "project:project-a"},
        {"user": "project:project-a", "relation": "project", "object": "work_order:wo-42"},
        {"user": "user:tech1", "relation": "assignee", "object": "work_order:wo-42"},
        {"user": "user:supervisor1", "relation": "reviewer", "object": "work_order:wo-42"},
        {"user": "user:pm1", "relation": "viewer", "object": "work_order:wo-42"},
    ]

    request(
        "POST",
        f"/stores/{store_id}/write",
        {
            "writes": {"tuple_keys": tuples},
            "authorization_model_id": model_id,
        },
    )

    def check(user, relation, obj):
        result = request(
            "POST",
            f"/stores/{store_id}/check",
            {
                "tuple_key": {
                    "user": f"user:{user}",
                    "relation": relation,
                    "object": obj,
                },
                "authorization_model_id": model_id,
            },
        )
        return bool(result["allowed"])

    assert check("pm1", "pm", "project:project-a")
    assert check("tech1", "assignee", "work_order:wo-42")
    assert check("supervisor1", "reviewer", "work_order:wo-42")
    assert check("pm1", "viewer", "work_order:wo-42")
    assert not check("pm1", "reviewer", "work_order:wo-42")
    assert not check("outsider1", "viewer", "work_order:wo-42")

    env_path = os.environ.get("GITHUB_ENV")
    if not env_path:
        raise RuntimeError("GITHUB_ENV not available")

    with open(env_path, "a", encoding="utf-8") as output:
        output.write(f"FGA_STORE_ID={store_id}\n")
        output.write(f"FGA_MODEL_ID={model_id}\n")
        output.write(f"FGA_API_URL={BASE}\n")

    print(
        "HILTECH_SPIKE15_OPENFGA_PASS "
        f"store={store_id} model={model_id} "
        "pm=ALLOW tech=ALLOW supervisor=ALLOW outsider=DENY"
    )


if __name__ == "__main__":
    main()
