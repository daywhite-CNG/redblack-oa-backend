#!/usr/bin/env python3
import concurrent.futures
import json
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


BASE_URL = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1/api/v1").rstrip("/")
RESULTS = []


def call(method, path, token=None, body=None, idempotency_key=None, timeout=20):
    headers = {"Accept": "application/json", "X-Request-Id": "phase3_extra_" + uuid.uuid4().hex}
    payload = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = Request(BASE_URL + path, data=payload, headers=headers, method=method)
    started = time.monotonic()
    try:
        with urlopen(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
    except HTTPError as error:
        status = error.code
        raw = error.read()
    elapsed_ms = round((time.monotonic() - started) * 1000, 1)
    data = json.loads(raw.decode("utf-8")) if raw else None
    return status, data, elapsed_ms


def token(username, password):
    status, payload, _ = call("POST", "/auth/login", body={
        "username": username, "password": password, "rememberMe": False,
    })
    if status != 200:
        raise RuntimeError(f"login failed for {username}: {status} {payload}")
    return payload["data"]["accessToken"]


def leave_body(reason="第三阶段补充测试申请"):
    return {
        "leaveType": "PERSONAL",
        "startTime": "2026-08-25T09:00:00+08:00",
        "endTime": "2026-08-25T18:30:00+08:00",
        "urgency": "NORMAL",
        "reason": reason,
        "handoverUserId": "10002",
        "contactPhone": "13800138000",
        "attachmentIds": [],
    }


def record(name, passed, expected, actual, detail=None):
    RESULTS.append({
        "name": name,
        "passed": bool(passed),
        "expected": expected,
        "actual": actual,
        "detail": detail,
    })


def has_error_shape(payload):
    return isinstance(payload, dict) and all(key in payload for key in ("code", "message", "requestId", "timestamp"))


def main():
    employee = token("employee", "123456")
    leader = token("leader", "123456")
    drafts = []

    status, payload, _ = call("GET", "/leave-applications?scope=mine&page=1&pageSize=20")
    record("unauthenticated_error_shape", status == 401 and has_error_shape(payload),
           "401 with standard error body", f"{status} {payload}")

    status, payload, _ = call("POST", "/leave-applications", token=employee, body=leave_body())
    record("missing_idempotency_header", status == 400 and has_error_shape(payload),
           "400 VALIDATION_FAILED", f"{status} {payload}")

    status, payload, _ = call("GET", "/leave-applications/not-a-number", token=employee)
    record("invalid_path_id", status == 400 and has_error_shape(payload),
           "400 VALIDATION_FAILED", f"{status} {payload}")

    bad_enum = leave_body()
    bad_enum["leaveType"] = "INVALID"
    status, payload, _ = call("POST", "/leave-applications", token=employee, body=bad_enum,
                              idempotency_key=str(uuid.uuid4()))
    record("invalid_enum", status == 400 and has_error_shape(payload),
           "400 VALIDATION_FAILED", f"{status} {payload}")

    status, payload, _ = call("GET", "/leave-applications?scope=mine&sort=unknown,desc",
                              token=employee)
    record("invalid_sort", status == 400 and payload.get("code") == "VALIDATION_FAILED",
           "400 VALIDATION_FAILED", f"{status} {payload}")

    status, payload, _ = call("POST", "/leave-applications", token=employee, body=leave_body(),
                              idempotency_key="not-a-uuid")
    record("invalid_idempotency_key", status == 400 and payload.get("code") == "VALIDATION_FAILED",
           "400 VALIDATION_FAILED", f"{status} {payload}")

    idem_key = str(uuid.uuid4())
    status, created_payload, _ = call("POST", "/leave-applications", token=employee,
                                      body=leave_body("幂等与数据精度补充测试"), idempotency_key=idem_key)
    created = created_payload["data"]
    drafts.append(created)
    record("duration_accuracy", status == 201 and float(created["leaveDurationHours"]) == 9.5,
           "201 and 9.5 hours", f"{status} {created.get('leaveDurationHours')}")

    changed = leave_body("同一幂等键不同请求体")
    status, payload, _ = call("POST", "/leave-applications", token=employee, body=changed,
                              idempotency_key=idem_key)
    record("idempotency_key_reuse", status == 409 and payload.get("code") == "IDEMPOTENCY_KEY_REUSED",
           "409 IDEMPOTENCY_KEY_REUSED", f"{status} {payload}")

    status, payload, _ = call("DELETE", f"/leave-applications/{created['id']}", token=employee)
    record("missing_delete_version", status == 400 and has_error_shape(payload),
           "400 VALIDATION_FAILED", f"{status} {payload}")

    status, created_payload, _ = call("POST", "/leave-applications", token=employee,
                                      body=leave_body("审批并发原子性补充测试"),
                                      idempotency_key=str(uuid.uuid4()))
    concurrent_application = created_payload["data"]
    status, submitted_payload, _ = call(
        "POST", f"/leave-applications/{concurrent_application['id']}/submit", token=employee,
        body={"version": concurrent_application["version"]}, idempotency_key=str(uuid.uuid4()))
    submitted = submitted_payload["data"]
    task_status, task_page, _ = call("GET", "/approval-tasks?view=pending&page=1&pageSize=100", token=leader)
    task = next(item for item in task_page["data"]["items"]
                if item["applicationId"] == concurrent_application["id"])

    def approve_once(comment):
        return call("POST", f"/approval-tasks/{task['id']}/approve", token=leader,
                    body={"version": task["version"], "comment": comment},
                    idempotency_key=str(uuid.uuid4()), timeout=30)

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        attempts = list(pool.map(approve_once, ("并发审批请求A", "并发审批请求B")))
    statuses = sorted(item[0] for item in attempts)
    conflict_codes = [item[1].get("code") for item in attempts if item[0] == 409]
    record("concurrent_approval_single_winner",
           statuses == [200, 409] and conflict_codes == ["APPROVAL_TASK_ALREADY_PROCESSED"],
           "one 200 and one 409 APPROVAL_TASK_ALREADY_PROCESSED",
           {"statuses": statuses, "conflictCodes": conflict_codes})

    _, final_payload, _ = call("GET", f"/leave-applications/{concurrent_application['id']}", token=employee)
    _, timeline_payload, _ = call("GET", f"/leave-applications/{concurrent_application['id']}/timeline",
                                  token=employee)
    approve_count = sum(1 for round_data in timeline_payload["data"]
                        for item in round_data["records"] if item["action"] == "APPROVE")
    record("concurrent_final_data_consistency",
           final_payload["data"]["status"] == "APPROVED" and approve_count == 1,
           "APPROVED with exactly one APPROVE record",
           {"status": final_payload["data"]["status"], "approveRecords": approve_count})

    def read_mine(_):
        return call("GET", "/leave-applications?scope=mine&page=1&pageSize=20", token=employee)

    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        reads = list(pool.map(read_mine, range(100)))
    read_statuses = [item[0] for item in reads]
    latencies = [item[2] for item in reads]
    record("parallel_read_stability_100",
           all(status == 200 for status in read_statuses) and max(latencies) < 5000,
           "100/100 HTTP 200 and max latency < 5000 ms",
           {"passed": sum(status == 200 for status in read_statuses),
            "maxMs": max(latencies), "avgMs": round(sum(latencies) / len(latencies), 1)})

    for draft in drafts:
        call("DELETE", f"/leave-applications/{draft['id']}?version={draft['version']}", token=employee)

    passed = sum(item["passed"] for item in RESULTS)
    print(json.dumps({"total": len(RESULTS), "passed": passed,
                      "failed": len(RESULTS) - passed, "results": RESULTS},
                     ensure_ascii=False, indent=2))
    return 0 if passed == len(RESULTS) else 1


if __name__ == "__main__":
    raise SystemExit(main())
