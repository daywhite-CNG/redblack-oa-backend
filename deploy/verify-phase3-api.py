#!/usr/bin/env python3
import json
import sys
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


PUBLIC_OPERATIONS = {
    "GET /leave-applications", "POST /leave-applications",
    "GET /leave-applications/{applicationId}", "PUT /leave-applications/{applicationId}",
    "DELETE /leave-applications/{applicationId}",
    "POST /leave-applications/{applicationId}/submit",
    "POST /leave-applications/{applicationId}/withdraw",
    "GET /leave-applications/{applicationId}/timeline",
    "GET /approval-tasks", "GET /approval-tasks/{taskId}",
    "POST /approval-tasks/{taskId}/approve", "POST /approval-tasks/{taskId}/reject",
    "POST /approval-tasks/{taskId}/transfer",
}


class ApiClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.covered = set()

    def call(self, method, path, expected, token=None, body=None, idempotency_key=None, operation=None):
        headers = {"Accept": "application/json", "X-Request-Id": "phase3_" + uuid.uuid4().hex}
        payload = None
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        request = Request(self.base_url + path, data=payload, headers=headers, method=method)
        try:
            with urlopen(request, timeout=25) as response:
                status = response.status
                raw = response.read()
        except HTTPError as error:
            status = error.code
            raw = error.read()
        expected_values = {expected} if isinstance(expected, int) else set(expected)
        data = json.loads(raw.decode("utf-8")) if raw else None
        if status not in expected_values:
            raise AssertionError(f"{method} {path}: expected {sorted(expected_values)}, got {status}: {data}")
        if data is not None and "requestId" not in data:
            raise AssertionError(f"{method} {path}: response has no requestId")
        if operation:
            self.covered.add(operation)
        return status, data


def response_data(result):
    return result[1]["data"]


def key():
    return str(uuid.uuid4())


def token_from(client, username, password):
    result = client.call("POST", "/auth/login", 200,
                         body={"username": username, "password": password, "rememberMe": False})
    data = response_data(result)
    assert data["expiresIn"] == 28800
    return data["accessToken"]


def leave_body(reason="第三阶段虚拟机真实联调请假"):
    return {
        "leaveType": "PERSONAL",
        "startTime": "2026-08-15T09:00:00+08:00",
        "endTime": "2026-08-15T18:30:00+08:00",
        "urgency": "NORMAL",
        "reason": reason,
        "handoverUserId": "10002",
        "contactPhone": "13800138000",
        "attachmentIds": [],
    }


def create_leave(client, employee, body=None, idempotent_replay=False):
    request_body = body if body is not None else leave_body()
    request_key = key()
    created = response_data(client.call(
        "POST", "/leave-applications", 201, token=employee, body=request_body,
        idempotency_key=request_key, operation="POST /leave-applications"))
    if idempotent_replay:
        replay = response_data(client.call(
            "POST", "/leave-applications", 201, token=employee, body=request_body,
            idempotency_key=request_key, operation="POST /leave-applications"))
        assert replay["id"] == created["id"]
        assert replay["applicationNo"] == created["applicationNo"]
    return created


def submit(client, employee, application, replay=False):
    path = f"/leave-applications/{application['id']}/submit"
    body = {"version": application["version"]}
    request_key = key()
    result = response_data(client.call(
        "POST", path, 200, token=employee, body=body, idempotency_key=request_key,
        operation="POST /leave-applications/{applicationId}/submit"))
    if replay:
        replay_result = response_data(client.call(
            "POST", path, 200, token=employee, body=body, idempotency_key=request_key,
            operation="POST /leave-applications/{applicationId}/submit"))
        assert replay_result == result
    return result


def task_for(client, token, application_id, view="pending"):
    page = response_data(client.call("GET", f"/approval-tasks?view={view}&page=1&pageSize=100",
                                     200, token=token, operation="GET /approval-tasks"))
    return next(item for item in page["items"] if item["applicationId"] == application_id)


def main():
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1/api/v1"
    client = ApiClient(base_url)
    suffix = uuid.uuid4().hex[:8]
    admin = token_from(client, "admin", "123456")
    employee = token_from(client, "employee", "123456")
    leader = token_from(client, "leader", "123456")
    temporary_user = None

    try:
        temporary_user = response_data(client.call(
            "POST", "/users", 201, token=admin, idempotency_key=key(),
            body={
                "username": "phase3_" + suffix,
                "name": "第三阶段临时审批人",
                "password": "Initial123!",
                "gender": "UNKNOWN",
                "phone": None,
                "email": None,
                "departmentId": "20002",
                "leaderId": None,
                "roleIds": ["30002"],
                "status": "ENABLED",
                "remark": "phase3 vm integration",
            }))
        temporary_token = token_from(client, "phase3_" + suffix, "Initial123!")

        assert client.call("GET", "/approval-tasks?view=pending&page=1&pageSize=20", 403,
                           token=employee, operation="GET /approval-tasks")[1]["code"] == "ACCESS_DENIED"

        application = create_leave(client, employee, idempotent_replay=True)
        application_id = application["id"]
        detail = response_data(client.call(
            "GET", f"/leave-applications/{application_id}", 200, token=employee,
            operation="GET /leave-applications/{applicationId}"))
        updated_body = leave_body("第三阶段更新后的真实联调请假原因")
        updated_body["version"] = detail["version"]
        application = response_data(client.call(
            "PUT", f"/leave-applications/{application_id}", 200, token=employee, body=updated_body,
            operation="PUT /leave-applications/{applicationId}"))
        stale = client.call(
            "PUT", f"/leave-applications/{application_id}", 409, token=employee, body=updated_body,
            operation="PUT /leave-applications/{applicationId}")[1]
        assert stale["code"] == "RESOURCE_VERSION_CONFLICT"
        submitted = submit(client, employee, application, replay=True)
        assert submitted["status"] == "PENDING" and submitted["submissionRound"] == 1

        mine = response_data(client.call(
            "GET", "/leave-applications?scope=mine&page=1&pageSize=100", 200, token=employee,
            operation="GET /leave-applications"))
        assert any(item["id"] == application_id for item in mine["items"])
        task = task_for(client, leader, application_id)
        task_id = task["id"]
        client.call("GET", f"/approval-tasks/{task_id}", 200, token=leader,
                    operation="GET /approval-tasks/{taskId}")
        assert client.call("GET", f"/approval-tasks/{task_id}", 404, token=admin,
                           operation="GET /approval-tasks/{taskId}")[1]["code"] == "RESOURCE_NOT_FOUND"
        not_assignee = client.call(
            "POST", f"/approval-tasks/{task_id}/approve", 403, token=admin,
            idempotency_key=key(), body={"version": task["version"], "comment": "管理员不能越过归属"},
            operation="POST /approval-tasks/{taskId}/approve")[1]
        assert not_assignee["code"] == "APPROVAL_TASK_NOT_ASSIGNEE"
        invalid_transfer = client.call(
            "POST", f"/approval-tasks/{task_id}/transfer", 422, token=leader,
            idempotency_key=key(), body={"version": task["version"], "targetUserId": "10003",
                                        "reason": "不能转给申请人"},
            operation="POST /approval-tasks/{taskId}/transfer")[1]
        assert invalid_transfer["code"] == "TRANSFER_TARGET_INVALID"
        transferred = response_data(client.call(
            "POST", f"/approval-tasks/{task_id}/transfer", 200, token=leader,
            idempotency_key=key(), body={"version": task["version"],
                                        "targetUserId": temporary_user["id"],
                                        "reason": "转交给同部门临时审批人"},
            operation="POST /approval-tasks/{taskId}/transfer"))
        assert transferred["taskStatus"] == "TRANSFERRED" and transferred["newTaskId"]
        already = client.call(
            "POST", f"/approval-tasks/{task_id}/approve", 409, token=leader,
            idempotency_key=key(), body={"version": transferred.get("version", 2), "comment": None},
            operation="POST /approval-tasks/{taskId}/approve")[1]
        assert already["code"] == "APPROVAL_TASK_ALREADY_PROCESSED"
        new_task = task_for(client, temporary_token, application_id)
        approved = response_data(client.call(
            "POST", f"/approval-tasks/{new_task['id']}/approve", 200, token=temporary_token,
            idempotency_key=key(), body={"version": new_task["version"], "comment": "同意真实联调申请"},
            operation="POST /approval-tasks/{taskId}/approve"))
        assert approved["applicationStatus"] == "APPROVED"
        final_application = response_data(client.call(
            "GET", f"/leave-applications/{application_id}", 200, token=employee,
            operation="GET /leave-applications/{applicationId}"))
        assert final_application["status"] == "APPROVED"
        timeline = response_data(client.call(
            "GET", f"/leave-applications/{application_id}/timeline", 200, token=employee,
            operation="GET /leave-applications/{applicationId}/timeline"))
        actions = [record["action"] for round_data in timeline for record in round_data["records"]]
        assert "TRANSFER" in actions and "APPROVE" in actions
        task_for(client, leader, application_id, "completed")
        task_for(client, temporary_token, application_id, "completed")

        rejected_application = create_leave(client, employee)
        rejected_submission = submit(client, employee, rejected_application)
        rejected_task = task_for(client, leader, rejected_application["id"])
        rejected = response_data(client.call(
            "POST", f"/approval-tasks/{rejected_task['id']}/reject", 200, token=leader,
            idempotency_key=key(), body={"version": rejected_task["version"],
                                        "comment": "请补充更完整的请假说明"},
            operation="POST /approval-tasks/{taskId}/reject"))
        assert rejected["applicationStatus"] == "REJECTED"
        rejected_detail = response_data(client.call(
            "GET", f"/leave-applications/{rejected_application['id']}", 200, token=employee,
            operation="GET /leave-applications/{applicationId}"))
        resubmit_body = leave_body("补充说明后重新提交的请假原因")
        resubmit_body["version"] = rejected_detail["version"]
        rejected_application = response_data(client.call(
            "PUT", f"/leave-applications/{rejected_application['id']}", 200, token=employee,
            body=resubmit_body, operation="PUT /leave-applications/{applicationId}"))
        resubmitted = submit(client, employee, rejected_application)
        assert resubmitted["submissionRound"] == 2

        withdrawn_application = create_leave(client, employee)
        withdrawn_submission = submit(client, employee, withdrawn_application)
        withdraw_path = f"/leave-applications/{withdrawn_application['id']}/withdraw"
        withdraw_body = {"version": withdrawn_submission["version"], "reason": "联调撤回后重新检查"}
        withdraw_key = key()
        withdrawn = response_data(client.call(
            "POST", withdraw_path, 200, token=employee, idempotency_key=withdraw_key, body=withdraw_body,
            operation="POST /leave-applications/{applicationId}/withdraw"))
        replayed = response_data(client.call(
            "POST", withdraw_path, 200, token=employee, idempotency_key=withdraw_key, body=withdraw_body,
            operation="POST /leave-applications/{applicationId}/withdraw"))
        assert withdrawn == replayed and withdrawn["status"] == "WITHDRAWN"
        client.call("DELETE", f"/leave-applications/{withdrawn_application['id']}?version={withdrawn['version']}",
                    204, token=employee, operation="DELETE /leave-applications/{applicationId}")
        client.call("GET", f"/leave-applications/{withdrawn_application['id']}", 404, token=employee,
                    operation="GET /leave-applications/{applicationId}")

        sick = create_leave(client, employee, {
            **leave_body("病假证明边界联调"), "leaveType": "SICK", "attachmentIds": []
        })
        sick_error = client.call(
            "POST", f"/leave-applications/{sick['id']}/submit", 422, token=employee,
            idempotency_key=key(), body={"version": sick["version"]},
            operation="POST /leave-applications/{applicationId}/submit")[1]
        assert sick_error["code"] == "SICK_ATTACHMENT_REQUIRED"
        client.call("DELETE", f"/leave-applications/{sick['id']}?version={sick['version']}", 204,
                    token=employee, operation="DELETE /leave-applications/{applicationId}")
        binding_error = client.call(
            "POST", "/leave-applications", 422, token=employee, idempotency_key=key(),
            body={**leave_body(), "attachmentIds": ["50001"]},
            operation="POST /leave-applications")[1]
        assert binding_error["code"] == "FILE_BINDING_INVALID"

        missing = PUBLIC_OPERATIONS - client.covered
        if missing:
            raise AssertionError("phase3 operations not covered: " + ", ".join(sorted(missing)))
        print(f"PHASE3_API_OK operations={len(client.covered)} transferredApplication={application_id} "
              f"rejectedApplication={rejected_application['id']}")
    finally:
        if temporary_user is not None:
            status, payload = client.call(
                "DELETE", f"/users/{temporary_user['id']}?version={temporary_user['version']}",
                (204, 404, 409), token=admin)
            if status not in (204, 404):
                print("WARNING: temporary approver cleanup failed: " + json.dumps(payload, ensure_ascii=False),
                      file=sys.stderr)


if __name__ == "__main__":
    main()
