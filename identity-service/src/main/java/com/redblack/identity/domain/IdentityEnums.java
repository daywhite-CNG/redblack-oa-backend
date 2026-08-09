package com.redblack.identity.domain;

public final class IdentityEnums {
    private IdentityEnums() {
    }

    public enum EnabledStatus { ENABLED, DISABLED }

    public enum Gender { MALE, FEMALE, UNKNOWN }

    public enum DataScope { ALL, DEPARTMENT, DEPARTMENT_AND_CHILDREN, SELF, CUSTOM }

    public enum MenuType { DIRECTORY, MENU, BUTTON }
}
