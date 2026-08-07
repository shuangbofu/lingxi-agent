package top.fusb.lingxi.runtime.codex.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodexCliError {

    private String message;
}
