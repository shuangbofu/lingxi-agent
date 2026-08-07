package top.fusb.lingxi.runtime.api.maintenance;

public interface RuntimeMaintenance {

    RuntimeMaintenanceStatus status();

    RuntimeMaintenanceOperation startInstall();

    RuntimeMaintenanceOperation installStatus();
}
