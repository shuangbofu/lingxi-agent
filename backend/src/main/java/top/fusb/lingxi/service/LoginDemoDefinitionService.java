package top.fusb.lingxi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.fusb.lingxi.dto.LoginDemoDefinition;
import top.fusb.lingxi.kit.TextKit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Service
public class LoginDemoDefinitionService {

    private static final String DEFINITION_PATH = "login-demo.json";

    private final LoginDemoDefinition definition;

    public LoginDemoDefinitionService(ObjectMapper objectMapper) {
        try (InputStream input = new ClassPathResource(DEFINITION_PATH).getInputStream()) {
            definition = objectMapper.readValue(input, LoginDemoDefinition.class);
            validate(definition);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载登录页演示定义：" + DEFINITION_PATH, exception);
        }
    }

    public LoginDemoDefinition get() {
        return definition;
    }

    private void validate(LoginDemoDefinition value) {
        if (value == null
                || TextKit.blankToNull(value.getQuestion()) == null
                || TextKit.blankToNull(value.getInputPlaceholder()) == null
                || TextKit.blankToNull(value.getThinkingTitle()) == null
                || TextKit.blankToNull(value.getAgentMessage()) == null
                || TextKit.blankToNull(value.getProcessedLabel()) == null
                || TextKit.blankToNull(value.getEmptyProcessText()) == null
                || TextKit.blankToNull(value.getResult()) == null
                || value.getSteps() == null || value.getSteps().isEmpty()
                || value.getSteps().stream().anyMatch(step -> step == null
                || TextKit.blankToNull(step.getLabel()) == null
                || step.getIcon() == null)) {
            throw new IllegalStateException("登录页演示定义缺少必要文案");
        }
        LoginDemoDefinition.Timings timings = value.getTimings();
        if (timings == null
                || !positive(timings.getTypingIntervalMs())
                || !positive(timings.getSubmitDelayMs())
                || !positive(timings.getLaunchDurationMs())
                || !positive(timings.getThinkingDelayMs())
                || !positive(timings.getMessageDelayMs())
                || !positive(timings.getStepDelayMs())
                || !positive(timings.getResultDelayMs())
                || !positive(timings.getCompletedDelayMs())
                || !positive(timings.getResetDelayMs())) {
            throw new IllegalStateException("登录页演示定义的播放时序必须为正整数");
        }
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
