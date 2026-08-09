package com.redblack.office.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import org.yaml.snakeyaml.Yaml;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class OfficeContractCoverageTest {
    private static final Set<String> HTTP = Set.of("get", "post", "put", "delete", "patch");
    private static final List<Class<?>> CONTROLLERS = List.of(WorkbenchController.class, NoticeController.class,
            NotificationController.class, FileController.class);

    @Test
    void publicOperationsAndResponseStatusesMatchAuthoritativeContract() throws Exception {
        Map<String, Set<String>> expected = expected();
        Map<String, Set<String>> actual = new TreeMap<>();
        for (Class<?> controller : CONTROLLERS) {
            String root = controller.getAnnotation(RequestMapping.class).value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                mapping(method).ifPresent(mapping -> {
                    var responses = method.getAnnotation(io.swagger.v3.oas.annotations.responses.ApiResponses.class);
                    assertThat(responses).as(method.toString()).isNotNull();
                    Set<String> codes = new TreeSet<>();
                    Arrays.stream(responses.value()).forEach(response -> codes.add(response.responseCode()));
                    actual.put(mapping.method + " " + root + mapping.path, codes);
                });
            }
        }
        assertThat(expected).hasSize(18);
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }

    @Test
    void noticeRequestRecordsKeepContractFields() throws Exception {
        Map<String, Object> schemas = schemas();
        assertFields(schemas, OfficeApiModels.SaveNoticeRequest.class,
                "title", "summary", "content", "type", "scopeType", "targetDepartmentIds", "isPinned",
                "scheduledPublishAt", "attachmentIds");
        assertFields(schemas, OfficeApiModels.UpdateNoticeRequest.class,
                "title", "summary", "content", "type", "scopeType", "targetDepartmentIds", "isPinned",
                "scheduledPublishAt", "attachmentIds", "version");
        assertFields(schemas, OfficeApiModels.NoticeWithdrawRequest.class, "version", "reason");
        assertFields(schemas, OfficeApiModels.ChangePinRequest.class, "isPinned", "version");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Set<String>> expected() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) document().get("paths");
        Map<String, Set<String>> result = new TreeMap<>();
        for (var entry : paths.entrySet()) {
            if (!(entry.getKey().equals("/workbench") || entry.getKey().startsWith("/notices")
                    || entry.getKey().startsWith("/notifications") || entry.getKey().startsWith("/files"))) continue;
            Map<String, Object> path = (Map<String, Object>) entry.getValue();
            for (String method : path.keySet()) {
                if (!HTTP.contains(method)) continue;
                Map<String, Object> operation = (Map<String, Object>) path.get(method);
                Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
                result.put(method.toUpperCase(Locale.ROOT) + " /api/v1" + entry.getKey(),
                        new TreeSet<>(responses.keySet()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemas() throws Exception {
        return (Map<String, Object>) ((Map<String, Object>) document().get("components")).get("schemas");
    }

    @SuppressWarnings("unchecked")
    private void assertFields(Map<String, Object> schemas, Class<?> type, String... fields) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(type.getSimpleName());
        Set<String> contract = properties(schemas, schema);
        Set<String> code = new TreeSet<>();
        Arrays.stream(type.getRecordComponents()).forEach(component -> code.add(component.getName()));
        assertThat(contract).containsExactlyInAnyOrder(fields);
        assertThat(code).containsExactlyInAnyOrderElementsOf(contract);
    }

    @SuppressWarnings("unchecked")
    private Set<String> properties(Map<String, Object> schemas, Map<String, Object> schema) {
        Set<String> fields = new TreeSet<>();
        Map<String, Object> direct = (Map<String, Object>) schema.get("properties");
        if (direct != null) fields.addAll(direct.keySet());
        List<Map<String, Object>> allOf = (List<Map<String, Object>>) schema.get("allOf");
        if (allOf != null) for (Map<String, Object> part : allOf) {
            String ref = (String) part.get("$ref");
            fields.addAll(properties(schemas, ref == null ? part
                    : (Map<String, Object>) schemas.get(ref.substring(ref.lastIndexOf('/') + 1))));
        }
        return fields;
    }

    private Map<String, Object> document() throws Exception {
        return new Yaml().load(Files.readString(Path.of("..", "contracts", "openapi", "redblack-oa-v1.openapi.yaml")));
    }
    private Optional<Mapping> mapping(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) return Optional.of(new Mapping("GET", first(method.getAnnotation(GetMapping.class).value())));
        if (method.isAnnotationPresent(PostMapping.class)) return Optional.of(new Mapping("POST", first(method.getAnnotation(PostMapping.class).value())));
        if (method.isAnnotationPresent(PutMapping.class)) return Optional.of(new Mapping("PUT", first(method.getAnnotation(PutMapping.class).value())));
        if (method.isAnnotationPresent(DeleteMapping.class)) return Optional.of(new Mapping("DELETE", first(method.getAnnotation(DeleteMapping.class).value())));
        if (method.isAnnotationPresent(PatchMapping.class)) return Optional.of(new Mapping("PATCH", first(method.getAnnotation(PatchMapping.class).value())));
        return Optional.empty();
    }
    private String first(String[] values) { return values.length == 0 ? "" : values[0]; }
    private record Mapping(String method, String path) { }
}
