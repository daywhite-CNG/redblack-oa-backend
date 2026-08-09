package com.redblack.identity.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityContractCoverageTest {
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    @Test
    void publicControllerOperationsExactlyMatchIdentityOpenApiOperations() throws Exception {
        Set<String> expected = identityOperationsFromOpenApi();
        Set<String> actual = new TreeSet<>();
        for (Class<?> controller : List.of(AuthController.class, UserController.class,
                DepartmentController.class, RoleController.class, MenuController.class)) {
            String root = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                mapping(method).ifPresent(mapping -> actual.add(mapping.httpMethod() + " " + root + mapping.path()));
            }
        }
        assertThat(expected).hasSize(35);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @SuppressWarnings("unchecked")
    private Set<String> identityOperationsFromOpenApi() throws Exception {
        Path contract = Path.of("..", "contracts", "openapi", "redblack-oa-v1.openapi.yaml");
        Map<String, Object> document = new Yaml().load(Files.readString(contract));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Set<String> operations = new TreeSet<>();
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            if (!isIdentityPath(entry.getKey())) {
                continue;
            }
            Map<String, Object> pathItem = (Map<String, Object>) entry.getValue();
            pathItem.keySet().stream().filter(HTTP_METHODS::contains)
                    .forEach(method -> operations.add(method.toUpperCase(Locale.ROOT) + " /api/v1" + entry.getKey()));
        }
        return operations;
    }

    private boolean isIdentityPath(String path) {
        return List.of("/auth/", "/account/", "/users", "/departments", "/roles", "/menus")
                .stream().anyMatch(path::startsWith);
    }

    private Optional<Mapping> mapping(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return Optional.of(new Mapping("GET", first(method.getAnnotation(GetMapping.class).value())));
        }
        if (method.isAnnotationPresent(PostMapping.class)) {
            return Optional.of(new Mapping("POST", first(method.getAnnotation(PostMapping.class).value())));
        }
        if (method.isAnnotationPresent(PutMapping.class)) {
            return Optional.of(new Mapping("PUT", first(method.getAnnotation(PutMapping.class).value())));
        }
        if (method.isAnnotationPresent(PatchMapping.class)) {
            return Optional.of(new Mapping("PATCH", first(method.getAnnotation(PatchMapping.class).value())));
        }
        if (method.isAnnotationPresent(DeleteMapping.class)) {
            return Optional.of(new Mapping("DELETE", first(method.getAnnotation(DeleteMapping.class).value())));
        }
        return Optional.empty();
    }

    private String first(String[] paths) {
        return paths.length == 0 ? "" : paths[0];
    }

    private record Mapping(String httpMethod, String path) {
    }
}
