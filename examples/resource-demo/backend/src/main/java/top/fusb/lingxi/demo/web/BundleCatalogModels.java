package top.fusb.lingxi.demo.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class BundleCatalogModels {

    private BundleCatalogModels() {
    }

    public record BundleCatalog(int schemaVersion, String name, String version, String description,
                                List<BundleCapability> capabilities,
                                List<BundleConfiguration> configurations,
                                List<JsonNode> scenarios) {
    }

    public record BundleCapability(String code, String name, String description, String packageUrl, String iconUrl) {
    }

    public record BundleConfiguration(String capabilityCode, String name, String description,
                                      ServiceConnection config) {
    }

    public record ServiceConnection(String baseUrl, String token) {
    }
}
