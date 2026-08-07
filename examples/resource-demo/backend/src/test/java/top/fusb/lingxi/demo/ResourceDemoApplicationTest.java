package top.fusb.lingxi.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import top.fusb.lingxi.demo.repository.ProjectRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:resource-demo-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "demo.public-base-url=http://localhost:8091"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceDemoApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProjectRepository projectRepository;

    @Test
    void exposesStandardBundleCatalogWithExternalScenariosAndPackages() throws Exception {
        mockMvc.perform(get("/setup/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.name").value("Lingxi 示例资源套件"))
                .andExpect(jsonPath("$.capabilities", hasSize(3)))
                .andExpect(jsonPath("$.capabilities[0].packageUrl",
                        startsWith("http://localhost:8091/setup/packages/")))
                .andExpect(jsonPath("$.configurations", hasSize(2)))
                .andExpect(jsonPath("$.configurations[0].config.baseUrl").value("http://localhost:8091"))
                .andExpect(jsonPath("$.scenarios", hasSize(10)))
                .andExpect(jsonPath("$.scenarios[0].code").value("business-question"))
                .andExpect(jsonPath("$.scenarios[6].code").value("log-analysis"))
                .andExpect(jsonPath("$.scenarios[6].capabilities", hasItem("http-log-read")))
                .andExpect(jsonPath("$.scenarios[6].color").value("#dc2626"))
                .andExpect(jsonPath("$.scenarios[6].packageUrl",
                        startsWith("http://localhost:8091/setup/scenario-packages/")));

        for (String code : List.of("project-hub", "wiki-ingest", "http-log-read")) {
            byte[] content = mockMvc.perform(get("/setup/packages/{code}.zip", code))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "application/zip"))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(content).hasSizeGreaterThan(100).startsWith((byte) 'P', (byte) 'K');
        }

        byte[] scenarioPackage = mockMvc.perform(get(
                        "/setup/scenario-packages/{code}.zip", "log-analysis"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(scenarioPackage).hasSizeGreaterThan(100).startsWith((byte) 'P', (byte) 'K');
    }

    @Test
    void exposesEnvironmentScopedProjectAndResourceContracts() throws Exception {
        Long projectId = projectRepository.findByCodeIgnoreCase("openai-codex").orElseThrow().getId();

        mockMvc.perform(get("/api/project-context/projects")
                        .header("Authorization", "Bearer lingxi-demo-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].code").isNotEmpty());

        mockMvc.perform(get("/api/project-context/repositories")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectId", String.valueOf(projectId))
                        .param("environmentCode", "main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].code").value("codex"))
                .andExpect(jsonPath("$.data[0].repositoryUrl").value("https://github.com/openai/codex.git"))
                .andExpect(jsonPath("$.data[0].environmentCode").value("main"));

        mockMvc.perform(get("/api/project-context/resource-configs")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectId", String.valueOf(projectId))
                        .param("environmentCode", "main")
                        .param("configType", "http-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].environmentCode").value("main"));

        mockMvc.perform(get("/api/project-context/resource-configs")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectId", String.valueOf(projectId))
                        .param("environmentCode", "main")
                        .param("configType", "jdbc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void searchesMarkdownWithLuceneModesAndMaintainsIndex() throws Exception {
        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openai-codex")
                        .param("query", "Agent 执行循环")
                        .param("mode", "LITERAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].sourceDocumentId").isNumber());

        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openai-codex")
                        .param("query", "Agent 工具")
                        .param("mode", "ALL_TERMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Codex 项目阅读线索"));

        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openai-codex")
                        .param("query", "Agent 完全不存在的检索词")
                        .param("mode", "ANY_TERMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Codex 项目阅读线索"));

        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openhands")
                        .param("query", "Codex CLI")
                        .param("mode", "LITERAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));

        String createdBody = """
                {"projectCode":"openai-codex","title":"索引同步测试","path":"测试/索引同步.md",\
                "category":"测试","content":"indexfreshalpha"}
                """;
        String createdJson = mockMvc.perform(post("/api/admin/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createdBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(createdJson);
        long documentId = created.path("data").path("id").asLong();

        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openai-codex")
                        .param("query", "indexfreshalpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceDocumentId").value(documentId));

        String updatedBody = """
                {"projectCode":"openai-codex","title":"索引同步测试","path":"测试/索引同步.md",\
                "category":"测试","content":"indexfreshbeta"}
                """;
        mockMvc.perform(put("/api/admin/documents/{documentId}", documentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatedBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2));

        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openai-codex")
                        .param("query", "indexfreshalpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openai-codex")
                        .param("query", "indexfreshbeta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sourceDocumentId").value(documentId));

        mockMvc.perform(delete("/api/admin/documents/{documentId}", documentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mockMvc.perform(get("/api/project-context/wiki/source-search")
                        .header("Authorization", "Bearer lingxi-demo-token")
                        .param("projectCode", "openai-codex")
                        .param("query", "indexfreshbeta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void exposesHttpLogContract() throws Exception {
        mockMvc.perform(get("/files/logs/list")
                        .param("token", "lingxi-demo-token")
                        .param("root", "/demo/logs/openai-codex/main"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].path").value("agent-runner.log"));

        mockMvc.perform(get("/files/logs/tail")
                        .param("token", "lingxi-demo-token")
                        .param("root", "/demo/logs/openai-codex/main")
                        .param("file", "agent-runner.log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(org.hamcrest.Matchers.containsString("demo-trace-1001")));
    }
}
