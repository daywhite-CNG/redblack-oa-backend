package com.redblack.audit.api;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class AuditContractCoverageTest {
    @Test
    @SuppressWarnings("unchecked")
    void publicOperationsAndStatusesMatchAuthoritativeContract() throws Exception {
        Map<String, Object> document = new Yaml().load(Files.readString(
                Path.of("..", "contracts", "openapi", "redblack-oa-v1.openapi.yaml")));
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Map<String, Set<String>> expected = new java.util.TreeMap<>();
        for (var entry : paths.entrySet()) {
            if (!entry.getKey().startsWith("/operation-logs")) continue;
            Map<String, Object> operation = (Map<String, Object>) ((Map<String, Object>) entry.getValue()).get("get");
            expected.put("GET /api/v1" + entry.getKey(),
                    new TreeSet<>(((Map<String, Object>) operation.get("responses")).keySet()));
        }
        Map<String, Set<String>> actual = new java.util.TreeMap<>();
        String root = OperationLogController.class.getAnnotation(RequestMapping.class).value()[0];
        for (var method : OperationLogController.class.getDeclaredMethods()) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            if (mapping == null) continue;
            var responses = method.getAnnotation(io.swagger.v3.oas.annotations.responses.ApiResponses.class);
            Set<String> codes = new TreeSet<>();
            java.util.Arrays.stream(responses.value()).forEach(response -> codes.add(response.responseCode()));
            actual.put("GET " + root + (mapping.value().length == 0 ? "" : mapping.value()[0]), codes);
        }
        assertThat(expected).hasSize(2);
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
