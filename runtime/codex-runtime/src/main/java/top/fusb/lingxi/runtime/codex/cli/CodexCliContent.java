package top.fusb.lingxi.runtime.codex.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodexCliContent {

    private String type;
    private String text;
    private String message;
    private String content;
}
