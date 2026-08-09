CREATE TABLE sys_department (
    id BIGINT UNSIGNED NOT NULL,
    parent_id BIGINT UNSIGNED NULL,
    parent_key BIGINT UNSIGNED GENERATED ALWAYS AS (IFNULL(parent_id, 0)) STORED,
    name VARCHAR(100) NOT NULL,
    leader_id BIGINT UNSIGNED NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_department_parent_name (parent_key, name),
    KEY idx_department_parent (parent_id),
    CONSTRAINT fk_department_parent FOREIGN KEY (parent_id) REFERENCES sys_department (id),
    CONSTRAINT ck_department_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
    id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(50) NOT NULL,
    data_scope VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    system_role BOOLEAN NOT NULL DEFAULT FALSE,
    remark VARCHAR(500) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code),
    CONSTRAINT ck_role_status CHECK (status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT ck_role_data_scope CHECK (data_scope IN ('ALL', 'DEPARTMENT', 'DEPARTMENT_AND_CHILDREN', 'SELF', 'CUSTOM'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_permission (
    id BIGINT UNSIGNED NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code),
    CONSTRAINT ck_permission_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user (
    id BIGINT UNSIGNED NOT NULL,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    name VARCHAR(50) NOT NULL,
    gender VARCHAR(16) NOT NULL,
    phone VARCHAR(20) NULL,
    email VARCHAR(200) NULL,
    avatar_url VARCHAR(500) NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    leader_id BIGINT UNSIGNED NULL,
    status VARCHAR(16) NOT NULL,
    auth_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    credential_expires_at DATETIME(3) NULL,
    remark VARCHAR(500) NULL,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_department_status (department_id, status),
    KEY idx_user_leader (leader_id),
    CONSTRAINT fk_user_department FOREIGN KEY (department_id) REFERENCES sys_department (id),
    CONSTRAINT fk_user_leader FOREIGN KEY (leader_id) REFERENCES sys_user (id),
    CONSTRAINT ck_user_gender CHECK (gender IN ('MALE', 'FEMALE', 'UNKNOWN')),
    CONSTRAINT ck_user_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE sys_department
    ADD CONSTRAINT fk_department_leader FOREIGN KEY (leader_id) REFERENCES sys_user (id);

CREATE TABLE sys_menu (
    id BIGINT UNSIGNED NOT NULL,
    parent_id BIGINT UNSIGNED NULL,
    name VARCHAR(50) NOT NULL,
    icon VARCHAR(100) NULL,
    type VARCHAR(16) NOT NULL,
    route_path VARCHAR(200) NULL,
    component VARCHAR(200) NULL,
    permission_id BIGINT UNSIGNED NULL,
    sort_order INT UNSIGNED NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    enabled_route_key VARCHAR(200) GENERATED ALWAYS AS (
        CASE WHEN status = 'ENABLED' AND route_path IS NOT NULL THEN route_path ELSE NULL END
    ) STORED,
    version INT UNSIGNED NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_menu_enabled_route (enabled_route_key),
    KEY idx_menu_parent_sort (parent_id, sort_order),
    KEY idx_menu_permission (permission_id),
    CONSTRAINT fk_menu_parent FOREIGN KEY (parent_id) REFERENCES sys_menu (id),
    CONSTRAINT fk_menu_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id),
    CONSTRAINT ck_menu_type CHECK (type IN ('DIRECTORY', 'MENU', 'BUTTON')),
    CONSTRAINT ck_menu_status CHECK (status IN ('ENABLED', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
    user_id BIGINT UNSIGNED NOT NULL,
    role_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_role_role (role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_permission (
    role_id BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permission_permission (permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_menu (
    role_id BIGINT UNSIGNED NOT NULL,
    menu_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, menu_id),
    KEY idx_role_menu_menu (menu_id),
    CONSTRAINT fk_role_menu_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_menu_menu FOREIGN KEY (menu_id) REFERENCES sys_menu (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role_department (
    role_id BIGINT UNSIGNED NOT NULL,
    department_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (role_id, department_id),
    KEY idx_role_department_department (department_id),
    CONSTRAINT fk_role_department_role FOREIGN KEY (role_id) REFERENCES sys_role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_department_department FOREIGN KEY (department_id) REFERENCES sys_department (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE outbox_event (
    event_id CHAR(36) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    message_key VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at DATETIME(3) NULL,
    last_error VARCHAR(1000) NULL,
    PRIMARY KEY (event_id),
    KEY idx_outbox_pending (status, next_attempt_at, created_at),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'SENDING', 'SENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
