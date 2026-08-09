#!/usr/bin/env python3
import json
import sys
import time
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


PUBLIC_OPERATIONS = {
    "GET /workbench",
    "GET /notices", "POST /notices", "GET /notices/{noticeId}",
    "PUT /notices/{noticeId}", "DELETE /notices/{noticeId}",
    "POST /notices/{noticeId}/publish", "POST /notices/{noticeId}/withdraw",
    "PATCH /notices/{noticeId}/pin", "POST /notices/{noticeId}/read",
    "GET /notifications", "GET /notifications/unread-count",
    "POST /notifications/{notificationId}/read", "POST /notifications/read-all",
    "POST /files", "GET /files/{fileId}", "DELETE /files/{fileId}",
    "GET /files/{fileId}/content",
    "GET /operation-logs", "GET /operation-logs/{logId}",
}


class ApiClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.covered = set()

    def call(self, method, path, expected, token=None, body=None, idempotency_key=None,
             operation=None, raw_body=None, content_type=None):
        request_id = "phase4_" + uuid.uuid4().hex
        headers = {"Accept": "application/json", "X-Request-Id": request_id}
        payload = raw_body
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif content_type:
            headers["Content-Type"] = content_type
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
        if data is not None:
            for field in ("requestId",):
                if field not in data:
                    raise AssertionError(f"{method} {path}: response has no {field}")
            if status >= 400:
                for field in ("code", "message", "timestamp"):
                    if field not in data:
                        raise AssertionError(f"{method} {path}: error has no {field}")
        if operation:
            self.covered.add(operation)
        return status, data


def data(result):
    return result[1]["data"]


def key():
    return str(uuid.uuid4())


def token(client, username):
    login = client.call("POST", "/auth/login", 200,
                        body={"username": username, "password": "123456", "rememberMe": False})
    value = data(login)
    assert value["expiresIn"] == 28800
    return value["accessToken"]


def notice_body(suffix, content=None):
    return {
        "title": "第四阶段虚拟机公告-" + suffix,
        "summary": "真实 MySQL、Redis、Kafka 联调公告 " + suffix,
        "content": content or "<p>第四阶段联调内容</p>",
        "type": "COMPANY",
        "scopeType": "ALL",
        "targetDepartmentIds": [],
        "isPinned": False,
        "scheduledPublishAt": None,
        "attachmentIds": [],
    }


def multipart_png():
    boundary = "----redblack" + uuid.uuid4().hex
    content = b"\x89PNG\r\n\x1a\nphase4"
    body = (
        f"--{boundary}\r\n"
        'Content-Disposition: form-data; name="file"; filename="phase4.png"\r\n'
        "Content-Type: image/png\r\n\r\n"
    ).encode("ascii") + content + f"\r\n--{boundary}--\r\n".encode("ascii")
    return body, "multipart/form-data; boundary=" + boundary


def wait_for(client, action, timeout=30):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            value = action()
            if value:
                return value
        except (AssertionError, StopIteration) as error:
            last = error
        time.sleep(1)
    raise AssertionError(f"event projection did not converge in {timeout}s: {last}")


def main():
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1/api/v1"
    client = ApiClient(base_url)
    suffix = uuid.uuid4().hex[:8]
    admin = token(client, "admin")
    employee = token(client, "employee")

    workbench = data(client.call("GET", "/workbench", 200, token=employee,
                                 operation="GET /workbench"))
    assert isinstance(workbench["metrics"], list) and isinstance(workbench["recentNotices"], list)
    client.call("GET", "/notices?view=published&page=1&pageSize=20", 200, token=employee,
                operation="GET /notices")
    denied = client.call("GET", "/notices?view=manage&page=1&pageSize=20", 403, token=employee)[1]
    assert denied["code"] == "ACCESS_DENIED"

    body = notice_body(suffix, '<p>安全内容</p><script>alert("x")</script>')
    create_key = key()
    created = data(client.call("POST", "/notices", 201, token=admin, body=body,
                               idempotency_key=create_key, operation="POST /notices"))
    replay = data(client.call("POST", "/notices", 201, token=admin, body=body,
                              idempotency_key=create_key))
    assert replay["id"] == created["id"]
    notice_id = created["id"]
    assert "<script" not in created["content"].lower()

    detail = data(client.call("GET", f"/notices/{notice_id}", 200, token=admin,
                              operation="GET /notices/{noticeId}"))
    hidden = client.call("GET", f"/notices/{notice_id}", 404, token=employee)[1]
    assert hidden["code"] == "RESOURCE_NOT_FOUND"

    updated_body = notice_body(suffix)
    updated_body["summary"] = "更新后的第四阶段真实联调公告"
    updated_body["version"] = detail["version"]
    updated = data(client.call("PUT", f"/notices/{notice_id}", 200, token=admin,
                               body=updated_body, operation="PUT /notices/{noticeId}"))
    pinned = data(client.call("PATCH", f"/notices/{notice_id}/pin", 200, token=admin,
                              body={"isPinned": True, "version": updated["version"]},
                              operation="PATCH /notices/{noticeId}/pin"))
    conflict = client.call("PATCH", f"/notices/{notice_id}/pin", 409, token=admin,
                           body={"isPinned": False, "version": updated["version"]})[1]
    assert conflict["code"] == "RESOURCE_VERSION_CONFLICT"

    published = data(client.call("POST", f"/notices/{notice_id}/publish", 200, token=admin,
                                 body={"version": pinned["version"]}, idempotency_key=key(),
                                 operation="POST /notices/{noticeId}/publish"))
    assert published["status"] == "PUBLISHED"
    employee_detail = data(client.call("GET", f"/notices/{notice_id}", 200, token=employee))
    assert employee_detail["id"] == notice_id
    client.call("POST", f"/notices/{notice_id}/read", 204, token=employee,
                operation="POST /notices/{noticeId}/read")
    client.call("POST", f"/notices/{notice_id}/read", 204, token=employee)
    read_detail = data(client.call("GET", f"/notices/{notice_id}", 200, token=employee))
    assert read_detail["isRead"] is True and read_detail["readCount"] == 1

    def notification_for_notice():
        page = data(client.call("GET", "/notifications?page=1&pageSize=100", 200, token=employee,
                                operation="GET /notifications"))
        return next((item for item in page["items"] if item["businessType"] == "NOTICE"
                     and item["businessId"] == notice_id), None)

    notification = wait_for(client, notification_for_notice)
    unread = data(client.call("GET", "/notifications/unread-count", 200, token=employee,
                              operation="GET /notifications/unread-count"))
    assert unread["count"] >= 1
    client.call("POST", f"/notifications/{notification['id']}/read", 204, token=employee,
                operation="POST /notifications/{notificationId}/read")
    client.call("POST", "/notifications/read-all", 200, token=employee,
                operation="POST /notifications/read-all")

    draft = data(client.call("POST", "/notices", 201, token=admin,
                             body=notice_body("delete-" + suffix), idempotency_key=key()))
    client.call("DELETE", f"/notices/{draft['id']}?version={draft['version']}", 204, token=admin,
                operation="DELETE /notices/{noticeId}")

    withdrawn = data(client.call("POST", f"/notices/{notice_id}/withdraw", 200, token=admin,
                                 body={"version": published["version"], "reason": "第四阶段验收清理"},
                                 idempotency_key=key(), operation="POST /notices/{noticeId}/withdraw"))
    assert withdrawn["status"] == "WITHDRAWN"
    assert client.call("GET", f"/notices/{notice_id}", 404, token=employee)[1]["code"] == "RESOURCE_NOT_FOUND"

    missing_id = "9223372036854770000"
    client.call("GET", f"/files/{missing_id}", 404, token=employee,
                operation="GET /files/{fileId}")
    client.call("DELETE", f"/files/{missing_id}", 404, token=employee,
                operation="DELETE /files/{fileId}")
    client.call("GET", f"/files/{missing_id}/content", 404, token=employee,
                operation="GET /files/{fileId}/content")
    upload_body, upload_type = multipart_png()
    unavailable = client.call("POST", "/files", 503, token=employee, raw_body=upload_body,
                              content_type=upload_type, idempotency_key=key(), operation="POST /files")[1]
    assert unavailable["code"] == "DEPENDENCY_UNAVAILABLE"

    def audit_for_notice():
        page = data(client.call("GET", "/operation-logs?operationType=NOTICE_CREATED&page=1&pageSize=100",
                                200, token=admin, operation="GET /operation-logs"))
        return next((item for item in page["items"] if item["businessId"] == notice_id), None)

    audit = wait_for(client, audit_for_notice)
    audit_detail = data(client.call("GET", f"/operation-logs/{audit['id']}", 200, token=admin,
                                    operation="GET /operation-logs/{logId}"))
    assert audit_detail["businessId"] == notice_id and audit_detail["operationType"] == "NOTICE_CREATED"
    assert client.call("GET", "/operation-logs?page=1&pageSize=20", 403, token=employee)[1]["code"] == "ACCESS_DENIED"

    missing = PUBLIC_OPERATIONS - client.covered
    extra = client.covered - PUBLIC_OPERATIONS
    if missing or extra:
        raise AssertionError(f"operation coverage mismatch missing={sorted(missing)} extra={sorted(extra)}")
    print(f"PHASE4_API_OK operations={len(client.covered)}/20 notice={notice_id} notification={notification['id']} audit={audit['id']} oss=503_expected")


if __name__ == "__main__":
    main()
