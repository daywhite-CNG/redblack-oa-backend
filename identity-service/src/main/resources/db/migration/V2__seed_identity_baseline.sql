INSERT INTO sys_department (id, parent_id, name, sort_order, status)
VALUES (20001, NULL, 'Red&Black', 0, 'ENABLED'),
       (20002, 20001, '研发部', 10, 'ENABLED');

INSERT INTO sys_role (id, code, name, data_scope, status, system_role, remark)
VALUES (30001, 'SYSTEM_ADMIN', '系统管理员', 'ALL', 'ENABLED', TRUE, 'V1 预置角色'),
       (30002, 'DEPARTMENT_LEADER', '部门领导', 'DEPARTMENT', 'ENABLED', TRUE, 'V1 预置角色'),
       (30003, 'EMPLOYEE', '普通员工', 'SELF', 'ENABLED', TRUE, 'V1 预置角色');

INSERT INTO sys_permission (id, code, name) VALUES
  (71001, 'dashboard:view', '查看工作台'),
  (71002, 'account:profile:read', '查看个人资料'),
  (71003, 'account:profile:update', '修改个人资料'),
  (71004, 'system:user:read', '查询用户'),
  (71005, 'system:user:create', '新增用户'),
  (71006, 'system:user:update', '编辑用户'),
  (71007, 'system:user:delete', '删除用户'),
  (71008, 'system:user:reset-password', '重置用户密码'),
  (71009, 'system:department:read', '查询部门'),
  (71010, 'system:department:create', '新增部门'),
  (71011, 'system:department:update', '编辑部门'),
  (71012, 'system:department:delete', '删除部门'),
  (71013, 'system:role:read', '查询角色'),
  (71014, 'system:role:create', '新增角色'),
  (71015, 'system:role:update', '编辑角色'),
  (71016, 'system:role:delete', '删除角色'),
  (71017, 'system:role:grant', '分配角色权限'),
  (71018, 'system:menu:read', '查询菜单'),
  (71019, 'system:menu:create', '新增菜单'),
  (71020, 'system:menu:update', '编辑菜单'),
  (71021, 'system:menu:delete', '删除菜单'),
  (71022, 'leave:create', '新建请假'),
  (71023, 'leave:read:self', '查看本人申请'),
  (71024, 'leave:read:scope', '按范围查看申请'),
  (71025, 'leave:update:self', '编辑本人申请'),
  (71026, 'leave:submit', '提交申请'),
  (71027, 'leave:withdraw', '撤回申请'),
  (71028, 'approval:task:read', '查看审批任务'),
  (71029, 'approval:task:approve', '同意审批'),
  (71030, 'approval:task:reject', '驳回审批'),
  (71031, 'approval:task:transfer', '转交审批'),
  (71032, 'notice:read', '阅读公告'),
  (71033, 'notice:create', '新建公告'),
  (71034, 'notice:update', '编辑公告'),
  (71035, 'notice:publish', '发布公告'),
  (71036, 'notice:withdraw', '撤回公告'),
  (71037, 'notice:delete', '删除公告'),
  (71038, 'audit:operation-log:read', '查询操作日志');

INSERT INTO sys_user (id, username, password_hash, name, gender, department_id, leader_id, status, remark)
VALUES (10001, 'admin', '$2a$12$itwchbM87ay7kHYj2uORS./5fYTS/oxJMw93CDlu0dnVQSQVMdXp2', '系统管理员', 'UNKNOWN', 20001, NULL, 'ENABLED', 'V1 演示账号'),
       (10002, 'leader', '$2a$12$itwchbM87ay7kHYj2uORS./5fYTS/oxJMw93CDlu0dnVQSQVMdXp2', '部门领导', 'UNKNOWN', 20002, NULL, 'ENABLED', 'V1 演示账号'),
       (10003, 'employee', '$2a$12$itwchbM87ay7kHYj2uORS./5fYTS/oxJMw93CDlu0dnVQSQVMdXp2', '普通员工', 'UNKNOWN', 20002, 10002, 'ENABLED', 'V1 演示账号');

UPDATE sys_department SET leader_id = 10002 WHERE id = 20002;

INSERT INTO sys_user_role (user_id, role_id)
VALUES (10001, 30001), (10002, 30002), (10003, 30003);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 30001, id FROM sys_permission WHERE status = 'ENABLED';

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 30002, id FROM sys_permission WHERE code IN (
  'dashboard:view','account:profile:read','account:profile:update','system:user:read',
  'system:department:read','leave:create','leave:read:self','leave:read:scope',
  'leave:update:self','leave:submit','leave:withdraw','approval:task:read',
  'approval:task:approve','approval:task:reject','approval:task:transfer','notice:read'
);

INSERT INTO sys_role_permission (role_id, permission_id)
SELECT 30003, id FROM sys_permission WHERE code IN (
  'dashboard:view','account:profile:read','account:profile:update','leave:create',
  'leave:read:self','leave:update:self','leave:submit','leave:withdraw','notice:read'
);

INSERT INTO sys_menu (id, parent_id, name, icon, type, route_path, component, permission_id, sort_order, status, visible) VALUES
  (40001, NULL, '工作台', 'DashboardOutlined', 'MENU', '/dashboard', 'Dashboard', 71001, 10, 'ENABLED', TRUE),
  (40010, NULL, '审批中心', 'AuditOutlined', 'DIRECTORY', NULL, NULL, NULL, 20, 'ENABLED', TRUE),
  (40011, 40010, '发起请假', NULL, 'MENU', '/approval/leave/new', 'Approval/LeaveForm', 71022, 10, 'ENABLED', TRUE),
  (40012, 40010, '待我审批', NULL, 'MENU', '/approval/pending', 'Approval/Pending', 71028, 20, 'ENABLED', TRUE),
  (40013, 40010, '我已审批', NULL, 'MENU', '/approval/completed', 'Approval/Completed', 71028, 30, 'ENABLED', TRUE),
  (40014, 40010, '我的申请', NULL, 'MENU', '/approval/mine', 'Approval/Mine', 71023, 40, 'ENABLED', TRUE),
  (40020, NULL, '组织管理', 'TeamOutlined', 'DIRECTORY', NULL, NULL, NULL, 30, 'ENABLED', TRUE),
  (40021, 40020, '用户管理', NULL, 'MENU', '/organization/users', 'Organization/Users', 71004, 10, 'ENABLED', TRUE),
  (40022, 40020, '部门管理', NULL, 'MENU', '/organization/departments', 'Organization/Departments', 71009, 20, 'ENABLED', TRUE),
  (40023, 40020, '角色管理', NULL, 'MENU', '/organization/roles', 'Organization/Roles', 71013, 30, 'ENABLED', TRUE),
  (40024, 40020, '菜单管理', NULL, 'MENU', '/organization/menus', 'Organization/Menus', 71018, 40, 'ENABLED', TRUE),
  (40030, NULL, '公告管理', 'NotificationOutlined', 'DIRECTORY', NULL, NULL, NULL, 40, 'ENABLED', TRUE),
  (40031, 40030, '公告列表', NULL, 'MENU', '/notices', 'Notices/List', 71032, 10, 'ENABLED', TRUE),
  (40032, 40030, '发布公告', NULL, 'MENU', '/notices/new', 'Notices/Form', 71033, 20, 'ENABLED', TRUE),
  (40040, NULL, '系统审计', 'FileSearchOutlined', 'DIRECTORY', NULL, NULL, NULL, 50, 'ENABLED', TRUE),
  (40041, 40040, '操作日志', NULL, 'MENU', '/audit/operation-logs', 'Audit/OperationLogs', 71038, 10, 'ENABLED', TRUE),
  (40050, NULL, '个人中心', 'UserOutlined', 'DIRECTORY', NULL, NULL, NULL, 60, 'ENABLED', TRUE),
  (40051, 40050, '个人资料', NULL, 'MENU', '/account/profile', 'Account/Profile', 71002, 10, 'ENABLED', TRUE);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rp.role_id, m.id
FROM sys_role_permission rp
JOIN sys_menu m ON m.permission_id = rp.permission_id;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, m.parent_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.parent_id IS NOT NULL;
