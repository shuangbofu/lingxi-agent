package top.fusb.lingxi.runtime.api.maintenance;

import lombok.Data;

@Data
public class RuntimeMaintenanceStatus {

    private String executable;
    private String osType;
    private boolean available;
    private String versionText;
    private String currentVersion;
    private String latestVersion;
    private Boolean updateAvailable;
    private String releaseUrl;
    private String updateCheckError;
    private String errorText;
}
