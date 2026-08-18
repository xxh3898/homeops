package dev.homeops.system.api;

import dev.homeops.agent.logs.ContainerLogQueryService;
import dev.homeops.agent.logs.InvalidContainerLogTailException;
import java.util.List;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ContainerLogController {

    private static final int DEFAULT_TAIL = 100;

    private final ContainerLogQueryService queryService;

    public ContainerLogController(ContainerLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/v1/containers/{id}/logs")
    public ContainerLogResponse containerLogs(
            @PathVariable String id,
            @RequestParam MultiValueMap<String, String> parameters) {
        int tail = parseTail(parameters);
        return ContainerLogResponse.from(id, tail, queryService.read(id, tail));
    }

    private static int parseTail(MultiValueMap<String, String> parameters) {
        if (parameters.isEmpty()) {
            return DEFAULT_TAIL;
        }
        if (parameters.size() != 1 || !parameters.containsKey("tail")) {
            throw new InvalidContainerLogTailException();
        }
        List<String> values = parameters.get("tail");
        if (values == null || values.size() != 1) {
            throw new InvalidContainerLogTailException();
        }
        return switch (values.getFirst()) {
            case "50" -> 50;
            case "100" -> 100;
            case "200" -> 200;
            default -> throw new InvalidContainerLogTailException();
        };
    }
}
