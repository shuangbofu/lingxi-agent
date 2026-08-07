package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.definition.ModuleDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import top.fusb.lingxi.definition.ParameterOptionSourceDefinition;
import top.fusb.lingxi.dto.DefinitionParameterOption;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefinitionParameterOptionServiceTest {

    private final ModuleDefinitionService moduleDefinitionService = mock(ModuleDefinitionService.class);
    private final CapabilityConfigService capabilityConfigService = mock(CapabilityConfigService.class);
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/project-context/wiki/document-categories", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = """
                    {"code":"SUCCESS","data":[
                      {"id":12,"name":"接口规范"},
                      {"id":27,"name":"值班手册"}
                    ]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldLoadCategoryOptionsFromConfiguredSynergyService() {
        ModuleDefinition scenario = new ModuleDefinition();
        scenario.setCode("document-reading");
        ModuleParameterDefinition parameter = new ModuleParameterDefinition();
        parameter.setKey("documentCategory");
        ParameterOptionSourceDefinition source = new ParameterOptionSourceDefinition();
        source.setCapabilityCode("wiki-ingest");
        source.setPath("/api/project-context/wiki/document-categories");
        source.setLabelField("name");
        source.setValueField("id");
        parameter.setOptionSource(source);
        scenario.setParameters(List.of(parameter));
        when(moduleDefinitionService.listInstalledScenarios()).thenReturn(List.of(scenario));
        when(capabilityConfigService.enabledConfigData("wiki-ingest")).thenReturn(Map.of(
                "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(),
                "token", "test-token"
        ));

        DefinitionParameterOptionService service = new DefinitionParameterOptionService(
                moduleDefinitionService, capabilityConfigService, new ObjectMapper());

        List<DefinitionParameterOption> result = service.options("document-reading", "documentCategory");

        assertThat(result).extracting(DefinitionParameterOption::getLabel)
                .containsExactly("接口规范", "值班手册");
        assertThat(result).extracting(DefinitionParameterOption::getValue)
                .containsExactly("12", "27");
        assertThat(authorization.get()).isEqualTo("Bearer test-token");
    }
}
