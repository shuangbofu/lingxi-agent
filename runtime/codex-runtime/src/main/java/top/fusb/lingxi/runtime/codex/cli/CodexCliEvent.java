package top.fusb.lingxi.runtime.codex.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodexCliEvent {

    private String timestamp;
    private String type;
    private String role;
    private String status;
    private String id;
    private String name;
    private String callId;
    private String itemId;
    private String itemType;
    private String command;
    private JsonNode arguments;
    private String output;
    private String aggregatedOutput;
    private String message;
    private String text;
    private JsonNode summary;
    private JsonNode info;
    private JsonNode result;
    private CodexCliEvent payload;
    private CodexCliEvent item;
    private CodexCliError error;
    private List<CodexCliContent> content;
    private Integer exitCode;

    @JsonProperty("call_id")
    public void setCallId(String callId) {
        this.callId = callId;
    }

    @JsonProperty("item_id")
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    @JsonProperty("item_type")
    public void setItemType(String itemType) {
        this.itemType = itemType;
    }

    @JsonProperty("exit_code")
    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    @JsonProperty("aggregated_output")
    public void setAggregatedOutput(String aggregatedOutput) {
        this.aggregatedOutput = aggregatedOutput;
    }
}
