package com.redblack.approval.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalContractCoverageTest {
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "delete");

    @Test
    void publicControllerOperationsExactlyMatchApprovalOpenApiOperations() throws Exception {
        Set<String> expected = approvalOperationsFromOpenApi();
        Set<String> actual = new TreeSet<>();
        for (Class<?> controller : List.of(LeaveApplicationController.class, ApprovalTaskController.class)) {
            String root = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                mapping(method).ifPresent(mapping -> actual.add(mapping.httpMethod() + " " + root + mapping.path()));
            }
        }
        assertThat(expected).hasSize(13);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void requestRecordsKeepTheAuthoritativeSchemaFields() throws Exception {
        Map<String, Object> schemas = schemas();
        assertSchemaFields(schemas, ApprovalApiModels.SaveLeaveApplicationRequest.class,
                "leaveType", "startTime", "endTime", "urgency", "reason", "handoverUserId", "contactPhone",
                "attachmentIds");
        assertSchemaFields(schemas, ApprovalApiModels.UpdateLeaveApplicationRequest.class,
                "leaveType", "startTime", "endTime", "urgency", "reason", "handoverUserId", "contactPhone",
                "attachmentIds", "version");
        assertSchemaFields(schemas, ApprovalApiModels.VersionRequest.class, "version");
        assertSchemaFields(schemas, ApprovalApiModels.WithdrawLeaveRequest.class, "version", "reason");
        assertSchemaFields(schemas, ApprovalApiModels.ApproveTaskRequest.class, "version", "comment");
        assertSchemaFields(schemas, ApprovalApiModels.RejectTaskRequest.class, "version", "comment");
        assertSchemaFields(schemas, ApprovalApiModels.TransferTaskRequest.class, "version", "targetUserId", "reason");
    }

    @Test
    void controllerResponseStatusesExactlyMatchTheAuthoritativeContract() throws Exception {
        Map<String, Set<String>> expected = responseStatusesFromOpenApi();
        Map<String, Set<String>> actual = new java.util.TreeMap<>();
        for (Class<?> controller : List.of(LeaveApplicationController.class, ApprovalTaskController.class)) {
            String root = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                Optional<Mapping> mapping = mapping(method);
                if (mapping.isEmpty()) {
                    continue;
                }
                var responses = method.getAnnotation(
                        io.swagger.v3.oas.annotations.responses.ApiResponses.class);
                assertThat(responses).as(method.toString()).isNotNull();
                Set<String> codes = new TreeSet<>();
                for (var response : responses.value()) {
                    codes.add(response.responseCode());
                }
                actual.put(mapping.get().httpMethod() + " " + root + mapping.get().path(), codes);
            }
        }
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @SuppressWarnings("unchecked")
    private Set<String> approvalOperationsFromOpenApi() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) document().get("paths");
        Set<String> operations = new TreeSet<>();
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            if (!entry.getKey().startsWith("/leave-applications")
                    && !entry.getKey().startsWith("/approval-tasks")) {
                continue;
            }
            Map<String, Object> pathItem = (Map<String, Object>) entry.getValue();
            pathItem.keySet().stream().filter(HTTP_METHODS::contains)
                    .forEach(method -> operations.add(method.toUpperCase(Locale.ROOT)
                            + " /api/v1" + entry.getKey()));
        }
        return operations;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> responseStatusesFromOpenApi() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) document().get("paths");
        Map<String, Set<String>> statuses = new java.util.TreeMap<>();
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            if (!entry.getKey().startsWith("/leave-applications")
                    && !entry.getKey().startsWith("/approval-tasks")) {
                continue;
            }
            Map<String, Object> pathItem = (Map<String, Object>) entry.getValue();
            for (String method : pathItem.keySet()) {
                if (!HTTP_METHODS.contains(method)) {
                    continue;
                }
                Map<String, Object> operation = (Map<String, Object>) pathItem.get(method);
                Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
                statuses.put(method.toUpperCase(Locale.ROOT) + " /api/v1" + entry.getKey(),
                        new TreeSet<>(responses.keySet()));
            }
        }
        return statuses;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas() throws Exception {
        Map<String, Object> components = (Map<String, Object>) document().get("components");
        return (Map<String, Object>) components.get("schemas");
    }

    @SuppressWarnings("unchecked")
    private void assertSchemaFields(Map<String, Object> schemas, Class<?> requestType, String... expected) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(requestType.getSimpleName());
        Set<String> contractFields = propertyNames(schemas, schema);
        Set<String> codeFields = new TreeSet<>();
        for (var component : requestType.getRecordComponents()) {
            codeFields.add(component.getName());
        }
        assertThat(contractFields).containsExactlyInAnyOrder(expected);
        assertThat(codeFields).containsExactlyInAnyOrderElementsOf(contractFields);
    }

    @SuppressWarnings("unchecked")
    private Set<String> propertyNames(Map<String, Object> schemas, Map<String, Object> schema) {
        Set<String> fields = new TreeSet<>();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties != null) {
            fields.addAll(properties.keySet());
        }
        List<Map<String, Object>> allOf = (List<Map<String, Object>>) schema.get("allOf");
        if (allOf != null) {
            for (Map<String, Object> part : allOf) {
                String reference = (String) part.get("$ref");
                Map<String, Object> resolved = reference == null ? part
                        : (Map<String, Object>) schemas.get(reference.substring(reference.lastIndexOf('/') + 1));
                fields.addAll(propertyNames(schemas, resolved));
            }
        }
        return fields;
    }

    private Map<String, Object> document() throws Exception {
        Path contract = Path.of("..", "contracts", "openapi", "redblack-oa-v1.openapi.yaml");
        return new Yaml().load(Files.readString(contract));
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
