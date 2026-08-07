package top.fusb.lingxi.runtime.mcp;

import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpTransport;
import top.fusb.lingxi.runtime.api.mcp.RuntimeMcpToolPresentation;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Entity
@Table(name = "mcp_server", uniqueConstraints = @UniqueConstraint(name = "uk_mcp_server_code", columnNames = "code"))
public class McpServerEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 80)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Lob
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RuntimeMcpTransport transport;

    @Column(length = 1000)
    private String command;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> arguments = new ArrayList<>();

    @Column(length = 2000)
    private String url;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> environment = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, String> headers = new LinkedHashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_server_runtime", joinColumns = @JoinColumn(name = "mcp_server_id"))
    @Column(name = "runtime_code", nullable = false, length = 80)
    private Set<String> runtimeCodes = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_server_activation_feature", joinColumns = @JoinColumn(name = "mcp_server_id"))
    @Column(name = "feature_code", nullable = false, length = 120)
    private Set<String> activationFeatures = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_server_tool", joinColumns = @JoinColumn(name = "mcp_server_id"))
    @Column(name = "tool_name", nullable = false, length = 160)
    private Set<String> toolAllowlist = new LinkedHashSet<>();

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, RuntimeMcpToolPresentation> toolPresentations = new LinkedHashMap<>();

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean builtin;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
