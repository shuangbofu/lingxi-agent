package top.fusb.lingxi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Data
@Configuration
@ConfigurationProperties(prefix = "lingxi")
public class LingxiProperties {

    private Task task = new Task();
    private AgentRuntime runtime = new AgentRuntime();
    private CapabilityRuntime capabilityRuntime = new CapabilityRuntime();
    private Definitions definitions = new Definitions();
    private User user = new User();

    @Data
    public static class Task {
        private int workerCount = 2;
        private String contentDir = "./data/tasks";
    }

    @Data
    public static class AgentRuntime {
        private String defaultCode;
    }

    @Data
    public static class CapabilityRuntime {
        private String baseUrl = "http://127.0.0.1:8080";
        private String pythonExecutable = "python3";
        private String packageCacheDir = "./data/capability-python-packages";
        private String locale = "zh_CN.utf8";
    }

    @Data
    public static class Definitions {
        private String installedSkillDir = "./data/installed-skills";
        private String installedScenarioDir = "./data/installed-scenarios";
        private Set<String> resourceSkillCodes = Set.of();
    }

    @Data
    public static class User {
        private String defaultPassword;
        private String avatarDir = "./data/avatars";
    }
}
