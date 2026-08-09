#!/usr/bin/env python3
import json
import sys
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen


PUBLIC_OPERATIONS = {
    "POST /auth/login", "POST /auth/logout", "GET /auth/me",
    "GET /account/profile", "PUT /account/profile", "PUT /account/password",
    "GET /users", "POST /users", "GET /users/options", "GET /users/{userId}",
    "PUT /users/{userId}", "PATCH /users/{userId}/status",
    "POST /users/{userId}/reset-password", "DELETE /users/{userId}",
    "GET /departments/tree", "POST /departments", "GET /departments/{departmentId}",
    "PUT /departments/{departmentId}", "PATCH /departments/{departmentId}/status",
    "DELETE /departments/{departmentId}",
    "GET /roles", "POST /roles", "GET /roles/{roleId}", "PUT /roles/{roleId}",
    "PATCH /roles/{roleId}/status", "DELETE /roles/{roleId}",
    "GET /roles/{roleId}/permissions", "PUT /roles/{roleId}/permissions",
    "GET /roles/{roleId}/members",
    "GET /menus/tree", "POST /menus", "GET /menus/{menuId}", "PUT /menus/{menuId}",
    "PATCH /menus/{menuId}/status", "DELETE /menus/{menuId}",
}


class ApiClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.covered = set()

    def call(self, method, path, expected, token=None, body=None, idempotency_key=None, operation=None):
        headers = {"Accept": "application/json", "X-Request-Id": "vm_" + uuid.uuid4().hex}
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
            with urlopen(request, timeout=20) as response:
                status = response.status
                raw = response.read()
        except HTTPError as error:
            status = error.code
            raw = error.read()
        expected_values = {expected} if isinstance(expected, int) else set(expected)
        data = json.loads(raw.decode("utf-8")) if raw else None
        if status not in expected_values:
            raise AssertionError(f"{method} {path}: expected {sorted(expected_values)}, got {status}: {data}")
        if operation:
            self.covered.add(operation)
        return status, data


def response_data(result):
    return result[1]["data"]


def new_idempotency_key():
    return str(uuid.uuid4())


def token_from(client, username, password):
    result = client.call("POST", "/auth/login", 200,
                         body={"username": username, "password": password, "rememberMe": False},
                         operation="POST /auth/login")
    data = response_data(result)
    assert data["expiresIn"] == 28800
    return data["accessToken"]


def main():
    base_url = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1/api/v1"
    client = ApiClient(base_url)
    suffix = uuid.uuid4().hex[:8]
    admin = token_from(client, "admin", "123456")
    department_id = outside_department_id = scope_child_id = role_id = menu_id = user_id = child_id = None

    try:
        me = response_data(client.call("GET", "/auth/me", 200, token=admin, operation="GET /auth/me"))
        assert me["username"] == "admin"

        client.call("GET", "/users?page=1&pageSize=20", 200, token=admin, operation="GET /users")
        client.call("GET", "/users/options?limit=20", 200, token=admin, operation="GET /users/options")
        client.call("GET", "/users/10003", 200, token=admin, operation="GET /users/{userId}")

        employee = token_from(client, "employee", "123456")
        denied = client.call("GET", "/users?page=1&pageSize=10", 403, token=employee,
                             operation="GET /users")[1]
        assert denied["code"] == "ACCESS_DENIED"

        leader = token_from(client, "leader", "123456")
        leader_users = response_data(client.call("GET", "/users?page=1&pageSize=100", 200, token=leader,
                                                 operation="GET /users"))
        assert leader_users["items"]
        assert all(item["department"]["id"] == "20002" for item in leader_users["items"])
        hidden = client.call("GET", "/users/10001", 404, token=leader,
                             operation="GET /users/{userId}")[1]
        assert hidden["code"] == "RESOURCE_NOT_FOUND"

        client.call("GET", "/departments/tree?enabledOnly=false", 200, token=admin,
                    operation="GET /departments/tree")
        department = response_data(client.call(
            "POST", "/departments", 201, token=admin, idempotency_key=new_idempotency_key(),
            body={"parentId": "20001", "name": "联调部门" + suffix, "leaderId": None,
                  "sortOrder": 90, "status": "ENABLED"}, operation="POST /departments"))
        department_id = department["id"]
        client.call("GET", f"/departments/{department_id}", 200, token=admin,
                    operation="GET /departments/{departmentId}")
        department = response_data(client.call(
            "PUT", f"/departments/{department_id}", 200, token=admin,
            body={"parentId": "20001", "name": "联调部门更新" + suffix, "leaderId": None,
                  "sortOrder": 91, "status": "ENABLED", "version": department["version"]},
            operation="PUT /departments/{departmentId}"))
        department = response_data(client.call(
            "PATCH", f"/departments/{department_id}/status", 200, token=admin,
            body={"status": "DISABLED", "version": department["version"]},
            operation="PATCH /departments/{departmentId}/status"))
        department = response_data(client.call(
            "PATCH", f"/departments/{department_id}/status", 200, token=admin,
            body={"status": "ENABLED", "version": department["version"]},
            operation="PATCH /departments/{departmentId}/status"))
        outside_department = response_data(client.call(
            "POST", "/departments", 201, token=admin, idempotency_key=new_idempotency_key(),
            body={"parentId": "20001", "name": "范围外联调部门" + suffix, "leaderId": None,
                  "sortOrder": 92, "status": "ENABLED"}, operation="POST /departments"))
        outside_department_id = outside_department["id"]

        client.call("GET", "/roles?page=1&pageSize=20", 200, token=admin, operation="GET /roles")
        role = response_data(client.call(
            "POST", "/roles", 201, token=admin, idempotency_key=new_idempotency_key(),
            body={"code": "VM_E2E_" + suffix.upper(), "name": "虚拟机联调角色",
                  "dataScope": "DEPARTMENT", "customDepartmentIds": [], "status": "ENABLED",
                  "remark": "phase2 real integration"}, operation="POST /roles"))
        role_id = role["id"]
        client.call("GET", f"/roles/{role_id}", 200, token=admin, operation="GET /roles/{roleId}")
        role = response_data(client.call(
            "PUT", f"/roles/{role_id}", 200, token=admin,
            body={"name": "虚拟机联调角色更新", "dataScope": "DEPARTMENT",
                  "customDepartmentIds": [], "status": "ENABLED", "remark": "updated",
                  "version": role["version"]}, operation="PUT /roles/{roleId}"))
        permissions = response_data(client.call(
            "PUT", f"/roles/{role_id}/permissions", 200, token=admin,
            body={"permissionIds": ["71002", "71003", "71005", "71006", "71007", "71008",
                                    "71009", "71010", "71011", "71012"],
                  "dataScope": "DEPARTMENT", "customDepartmentIds": [], "version": role["version"]},
            operation="PUT /roles/{roleId}/permissions"))
        client.call("GET", f"/roles/{role_id}/permissions", 200, token=admin,
                    operation="GET /roles/{roleId}/permissions")
        role = response_data(client.call(
            "PATCH", f"/roles/{role_id}/status", 200, token=admin,
            body={"status": "DISABLED", "version": permissions["version"]},
            operation="PATCH /roles/{roleId}/status"))
        role = response_data(client.call(
            "PATCH", f"/roles/{role_id}/status", 200, token=admin,
            body={"status": "ENABLED", "version": role["version"]},
            operation="PATCH /roles/{roleId}/status"))

        client.call("GET", "/menus/tree", 200, token=admin, operation="GET /menus/tree")
        menu = response_data(client.call(
            "POST", "/menus", 201, token=admin, idempotency_key=new_idempotency_key(),
            body={"parentId": None, "name": "联调目录" + suffix, "icon": None,
                  "type": "DIRECTORY", "routePath": None, "component": None, "permission": None,
                  "sortOrder": 99, "status": "ENABLED", "visible": True}, operation="POST /menus"))
        menu_id = menu["id"]
        client.call("GET", f"/menus/{menu_id}", 200, token=admin, operation="GET /menus/{menuId}")
        menu = response_data(client.call(
            "PUT", f"/menus/{menu_id}", 200, token=admin,
            body={"parentId": None, "name": "联调目录更新" + suffix, "icon": "ToolOutlined",
                  "type": "DIRECTORY", "routePath": None, "component": None, "permission": None,
                  "sortOrder": 98, "status": "ENABLED", "visible": True, "version": menu["version"]},
            operation="PUT /menus/{menuId}"))
        menu = response_data(client.call(
            "PATCH", f"/menus/{menu_id}/status", 200, token=admin,
            body={"status": "DISABLED", "version": menu["version"]},
            operation="PATCH /menus/{menuId}/status"))
        menu = response_data(client.call(
            "PATCH", f"/menus/{menu_id}/status", 200, token=admin,
            body={"status": "ENABLED", "version": menu["version"]},
            operation="PATCH /menus/{menuId}/status"))
        client.call("DELETE", f"/menus/{menu_id}?version={menu['version']}", 204, token=admin,
                    operation="DELETE /menus/{menuId}")
        menu_id = None

        user = response_data(client.call(
            "POST", "/users", 201, token=admin, idempotency_key=new_idempotency_key(),
            body={"username": "vme2e_" + suffix, "name": "联调用户", "password": "Initial123!",
                  "gender": "UNKNOWN", "phone": None, "email": None, "departmentId": department_id,
                  "leaderId": None, "roleIds": [role_id], "status": "ENABLED", "remark": "phase2"},
            operation="POST /users"))
        user_id = user["id"]
        user = response_data(client.call(
            "PUT", f"/users/{user_id}", 200, token=admin,
            body={"name": "联调用户更新", "gender": "UNKNOWN", "phone": None,
                  "email": f"vm-{suffix}@example.com", "departmentId": department_id, "leaderId": None,
                  "roleIds": [role_id], "status": "ENABLED", "remark": "updated",
                  "version": user["version"]}, operation="PUT /users/{userId}"))
        user = response_data(client.call(
            "PATCH", f"/users/{user_id}/status", 200, token=admin,
            body={"status": "DISABLED", "version": user["version"]},
            operation="PATCH /users/{userId}/status"))
        user = response_data(client.call(
            "PATCH", f"/users/{user_id}/status", 200, token=admin,
            body={"status": "ENABLED", "version": user["version"]},
            operation="PATCH /users/{userId}/status"))
        reset = response_data(client.call(
            "POST", f"/users/{user_id}/reset-password", 200, token=admin,
            idempotency_key=new_idempotency_key(), body={"version": user["version"]},
            operation="POST /users/{userId}/reset-password"))
        assert len(reset["temporaryPassword"]) == 16
        client.call("GET", f"/roles/{role_id}/members?page=1&pageSize=20", 200, token=admin,
                    operation="GET /roles/{roleId}/members")

        test_user = token_from(client, "vme2e_" + suffix, reset["temporaryPassword"])
        profile = response_data(client.call("GET", "/account/profile", 200, token=test_user,
                                            operation="GET /account/profile"))
        profile = response_data(client.call(
            "PUT", "/account/profile", 200, token=test_user,
            body={"name": "联调账号资料", "gender": "UNKNOWN", "phone": None,
                  "email": f"account-{suffix}@example.com", "version": profile["version"]},
            operation="PUT /account/profile"))
        client.call("PUT", "/account/password", 204, token=test_user,
                    body={"currentPassword": reset["temporaryPassword"], "newPassword": "Changed123!"},
                    operation="PUT /account/password")
        test_user = token_from(client, "vme2e_" + suffix, "Changed123!")

        scope_create_status, scope_create = client.call(
            "POST", "/departments", (201, 404), token=test_user,
            idempotency_key=new_idempotency_key(),
            body={"parentId": outside_department_id, "name": "越权子部门" + suffix, "leaderId": None,
                  "sortOrder": 93, "status": "ENABLED"}, operation="POST /departments")
        if scope_create_status == 201:
            scope_child_id = scope_create["data"]["id"]
            raise AssertionError("department create escaped current data scope")
        assert scope_create["code"] == "RESOURCE_NOT_FOUND"
        outside_department = response_data(client.call(
            "GET", f"/departments/{outside_department_id}", 200, token=admin,
            operation="GET /departments/{departmentId}"))
        assert client.call(
            "PUT", f"/departments/{outside_department_id}", 404, token=test_user,
            body={"parentId": "20001", "name": "范围外联调部门" + suffix, "leaderId": None,
                  "sortOrder": 92, "status": "ENABLED", "version": outside_department["version"]},
            operation="PUT /departments/{departmentId}")[1]["code"] == "RESOURCE_NOT_FOUND"
        assert client.call(
            "PATCH", f"/departments/{outside_department_id}/status", 404, token=test_user,
            body={"status": "DISABLED", "version": outside_department["version"]},
            operation="PATCH /departments/{departmentId}/status")[1]["code"] == "RESOURCE_NOT_FOUND"
        assert client.call(
            "DELETE", f"/departments/{outside_department_id}?version={outside_department['version']}",
            404, token=test_user, operation="DELETE /departments/{departmentId}")[1]["code"] \
            == "RESOURCE_NOT_FOUND"

        admin_view = response_data(client.call("GET", "/users/10001", 200, token=admin,
                                               operation="GET /users/{userId}"))
        outside_payload = {"name": "系统管理员", "gender": "UNKNOWN", "phone": None, "email": None,
                           "departmentId": "20001", "leaderId": None, "roleIds": ["30001"],
                           "status": "ENABLED", "remark": "V1 演示账号", "version": admin_view["version"]}
        assert client.call("PUT", "/users/10001", 404, token=test_user, body=outside_payload,
                           operation="PUT /users/{userId}")[1]["code"] == "RESOURCE_NOT_FOUND"
        assert client.call("PATCH", "/users/10001/status", 404, token=test_user,
                           body={"status": "DISABLED", "version": admin_view["version"]},
                           operation="PATCH /users/{userId}/status")[1]["code"] == "RESOURCE_NOT_FOUND"
        assert client.call("POST", "/users/10001/reset-password", 404, token=test_user,
                           idempotency_key=new_idempotency_key(), body={"version": admin_view["version"]},
                           operation="POST /users/{userId}/reset-password")[1]["code"] == "RESOURCE_NOT_FOUND"
        assert client.call("DELETE", f"/users/10001?version={admin_view['version']}", 404, token=test_user,
                           operation="DELETE /users/{userId}")[1]["code"] == "RESOURCE_NOT_FOUND"
        assert client.call(
            "POST", "/users", 404, token=test_user, idempotency_key=new_idempotency_key(),
            body={"username": "admin", "name": "范围外用户", "password": "Initial123!",
                  "gender": "UNKNOWN", "phone": None, "email": None, "departmentId": "20001",
                  "leaderId": None, "roleIds": [role_id], "status": "ENABLED", "remark": None},
            operation="POST /users")[1]["code"] == "RESOURCE_NOT_FOUND"

        child_request = {"username": "vmchild_" + suffix, "name": "幂等联调子用户",
                         "password": "Initial123!", "gender": "UNKNOWN", "phone": None, "email": None,
                         "departmentId": department_id, "leaderId": None, "roleIds": [role_id],
                         "status": "ENABLED", "remark": "idempotency authorization"}
        replay_key = new_idempotency_key()
        child = response_data(client.call("POST", "/users", 201, token=test_user, body=child_request,
                                          idempotency_key=replay_key, operation="POST /users"))
        child_id = child["id"]

        current_permissions = response_data(client.call(
            "GET", f"/roles/{role_id}/permissions", 200, token=admin,
            operation="GET /roles/{roleId}/permissions"))
        client.call("PUT", f"/roles/{role_id}/permissions", 200, token=admin,
                    body={"permissionIds": ["71002", "71003"], "dataScope": "DEPARTMENT",
                          "customDepartmentIds": [], "version": current_permissions["version"]},
                    operation="PUT /roles/{roleId}/permissions")
        replay = client.call("POST", "/users", 403, token=test_user, body=child_request,
                             idempotency_key=replay_key, operation="POST /users")[1]
        assert replay["code"] == "ACCESS_DENIED"

        client.call("POST", "/auth/logout", 204, token=test_user, operation="POST /auth/logout")
        rejected = client.call("GET", "/auth/me", 401, token=test_user, operation="GET /auth/me")[1]
        assert rejected["code"] == "UNAUTHENTICATED"
    finally:
        for candidate in (child_id, user_id):
            if candidate:
                try:
                    status, found = client.call("GET", f"/users/{candidate}", (200, 404), token=admin,
                                                operation="GET /users/{userId}")
                    if status == 200:
                        client.call("DELETE", f"/users/{candidate}?version={found['data']['version']}", 204,
                                    token=admin, operation="DELETE /users/{userId}")
                except Exception as cleanup_error:
                    print(f"cleanup user {candidate} failed: {cleanup_error}", file=sys.stderr)
        if menu_id:
            try:
                status, found = client.call("GET", f"/menus/{menu_id}", (200, 404), token=admin,
                                            operation="GET /menus/{menuId}")
                if status == 200:
                    client.call("DELETE", f"/menus/{menu_id}?version={found['data']['version']}", 204,
                                token=admin, operation="DELETE /menus/{menuId}")
            except Exception as cleanup_error:
                print(f"cleanup menu {menu_id} failed: {cleanup_error}", file=sys.stderr)
        if role_id:
            try:
                status, found = client.call("GET", f"/roles/{role_id}", (200, 404), token=admin,
                                            operation="GET /roles/{roleId}")
                if status == 200:
                    client.call("DELETE", f"/roles/{role_id}?version={found['data']['version']}", 204,
                                token=admin, operation="DELETE /roles/{roleId}")
            except Exception as cleanup_error:
                print(f"cleanup role {role_id} failed: {cleanup_error}", file=sys.stderr)
        for candidate in (scope_child_id, outside_department_id, department_id):
            if candidate:
                try:
                    status, found = client.call("GET", f"/departments/{candidate}", (200, 404), token=admin,
                                                operation="GET /departments/{departmentId}")
                    if status == 200:
                        client.call("DELETE", f"/departments/{candidate}?version={found['data']['version']}", 204,
                                    token=admin, operation="DELETE /departments/{departmentId}")
                except Exception as cleanup_error:
                    print(f"cleanup department {candidate} failed: {cleanup_error}", file=sys.stderr)

    client.call("POST", "/auth/logout", 204, token=admin, operation="POST /auth/logout")
    missing = sorted(PUBLIC_OPERATIONS - client.covered)
    if missing:
        raise AssertionError("Public operations not exercised: " + ", ".join(missing))
    print(f"Identity API acceptance passed: {len(client.covered)}/35 public operations exercised.")


if __name__ == "__main__":
    main()
