package top.fusb.lingxi.runtime.codex.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexEventParserTest {

    private final CodexEventParser parser = new CodexEventParser(new ObjectMapper());

    @Test
    void shouldParseAndUnwrapLegacyEvent() throws Exception {
        CodexCliEvent root = parser.parse("""
                {"type":"event_msg","payload":{"type":"item.completed","item":{"type":"command_execution","exit_code":0}}}
                """);

        CodexCliEvent event = parser.unwrap(root);

        assertEquals("item.completed", event.getType());
        assertEquals("command_execution", event.getItem().getType());
        assertEquals(0, event.getItem().getExitCode());
    }
}
