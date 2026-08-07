package top.fusb.lingxi.runtime.web;

import top.fusb.lingxi.config.RequirePermission;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.runtime.mcp.McpServerRequest;
import top.fusb.lingxi.runtime.mcp.McpServerResponse;
import top.fusb.lingxi.runtime.mcp.McpServerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mcp-servers")
public class McpServerController {

    private final McpServerService mcpServerService;

    @GetMapping
    @RequirePermission("SYSTEM_ADMIN")
    public List<McpServerResponse> list() {
        return mcpServerService.list();
    }

    @GetMapping("/page")
    @RequirePermission("SYSTEM_ADMIN")
    public PageResult<McpServerResponse> page(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "15") int size) {
        return mcpServerService.page(page, size);
    }

    @PostMapping
    @RequirePermission("SYSTEM_ADMIN")
    public McpServerResponse create(@Valid @RequestBody McpServerRequest request) {
        return mcpServerService.create(request);
    }

    @PutMapping("/{id}")
    @RequirePermission("SYSTEM_ADMIN")
    public McpServerResponse update(@PathVariable String id, @Valid @RequestBody McpServerRequest request) {
        return mcpServerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @RequirePermission("SYSTEM_ADMIN")
    public void delete(@PathVariable String id) {
        mcpServerService.delete(id);
    }
}
