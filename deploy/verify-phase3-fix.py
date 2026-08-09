#!/usr/bin/env python3
import json
import subprocess
import sys
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen


IDENTITY_CONTAINER = "redblack-oa-identity-service-1"


class ApiClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def call(self, method, path, token=None, body=None, idempotency_key=None, timeout=10):
        headers = {"Accept": "application/json", "X-Request-Id": "req_" + uuid.uuid4().hex}
        if token:
            headers["Authorization"] = "Bearer " + token
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=timeout) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except HTTPError as error:
            raw = error.read()
            return error.code, json.loads(raw) if raw else None


def expect_error(status, payload, expected_status, expected_code):
    assert status == expected_status, (status, payload)
    assert payload["code"] == expected_code, payload
    assert payload.get("message"), payload
    assert payload.get("requestId"), payload
    assert payload.get("timestamp"), payload


def login(client, username):
    status, payload = client.call("POST", "/auth/login", body={
        "username": username,
        "password": "123456",
        "rememberMe": False,
    })
    assert status == 200, payload
    return payload["data"]


def wait_for_identity_health():
    for _ in range(30):
        result = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Health.Status}}", IDENTITY_CONTAINER],
            capture_output=True, text=True, check=False)
        if result.returncode == 0 and result.stdout.strip() == "healthy":
            return
        time.sleep(2)
    raise AssertionError("identity-service did not return to healthy state")


def future_leave_period():
    start = (datetime.now(timezone.utc) + timedelta(days=2)).replace(
        hour=1, minute=0, second=0, microsecond=0)
    while start.weekday() >= 5:
        start += timedelta(days=1)
    return start, start + timedelta(hours=2)


def main():
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1/api/v1"
    client = ApiClient(base_url)
    employee = login(client, "employee")
    leader = login(client, "leader")
    employee_token = employee["accessToken"]
    leader_token = leader["accessToken"]

    binding_cases = [
        client.call("POST", "/leave-applications", token=employee_token, body={}),
        client.call("GET", "/leave-applications/not-a-number", token=employee_token),
        client.call("DELETE", "/leave-applications/1", token=employee_token),
    ]
    for status, payload in binding_cases:
        expect_error(status, payload, 400, "VALIDATION_FAILED")

    warm_path = "/leave-applications?scope=mine&page=1&pageSize=1&sort=updatedAt%2Cdesc"
    warm_status, warm_payload = client.call("GET", warm_path, token=employee_token)
    assert warm_status == 200, warm_payload

    subprocess.run(["docker", "pause", IDENTITY_CONTAINER], check=True, capture_output=True, text=True)
    started = time.monotonic()
    try:
        status, payload = client.call("GET", warm_path, token=employee_token, timeout=8)
        elapsed = time.monotonic() - started
        expect_error(status, payload, 503, "DEPENDENCY_UNAVAILABLE")
        assert elapsed < 5, elapsed
    finally:
        subprocess.run(["docker", "unpause", IDENTITY_CONTAINER], check=False,
                       capture_output=True, text=True)
    wait_for_identity_health()

    start, end = future_leave_period()
    create_status, create_payload = client.call(
        "POST", "/leave-applications", token=employee_token, idempotency_key=str(uuid.uuid4()), body={
            "leaveType": "ANNUAL",
            "startTime": start.isoformat(),
            "endTime": end.isoformat(),
            "urgency": "NORMAL",
            "reason": "并发审批回归验证",
            "handoverUserId": leader["user"]["id"],
            "contactPhone": "13800000000",
            "attachmentIds": [],
        })
    assert create_status == 201, create_payload
    application = create_payload["data"]
    submit_status, submit_payload = client.call(
        "POST", "/leave-applications/" + application["id"] + "/submit",
        token=employee_token, idempotency_key=str(uuid.uuid4()), body={"version": application["version"]})
    assert submit_status == 200, submit_payload

    application_no = quote(submit_payload["data"]["applicationNo"])
    tasks_status, tasks_payload = client.call(
        "GET", "/approval-tasks?view=pending&applicationNo=" + application_no
               + "&page=1&pageSize=20&sort=createdAt%2Cdesc", token=leader_token)
    assert tasks_status == 200, tasks_payload
    task = next(item for item in tasks_payload["data"]["items"]
                if item["applicationId"] == application["id"])

    def approve():
        return client.call(
            "POST", "/approval-tasks/" + task["id"] + "/approve", token=leader_token,
            idempotency_key=str(uuid.uuid4()), body={"version": task["version"], "comment": "同意"})

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(lambda _: approve(), range(2)))
    statuses = sorted(status for status, _ in results)
    assert statuses == [200, 409], results

    print("PHASE3_FIX_OK bindingErrors=3 dependencyTimeoutSeconds=%.3f concurrentStatuses=200,409 application=%s"
          % (elapsed, application["id"]))


if __name__ == "__main__":
    main()
