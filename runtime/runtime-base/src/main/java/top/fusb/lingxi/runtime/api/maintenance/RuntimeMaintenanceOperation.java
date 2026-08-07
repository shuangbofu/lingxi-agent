package top.fusb.lingxi.runtime.api.maintenance;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RuntimeMaintenanceOperation {

    private String status;
    private String osType;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime endedAt;
    private Integer exitCode;
    private String stdoutText;
    private String stderrText;
}
