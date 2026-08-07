package top.fusb.lingxi.runtime.api.model;

public record RuntimeModelConfig(
        String apiKey,
        String apiKeySource,
        String baseUrl,
        String model,
        String reasoningEffort,
        Integer contextWindowTokens,
        RuntimeModelProtocol protocol,
        String instructionPrompt,
        boolean imageInputSupported,
        String thinkingFieldName,
        String providerType
) {

    public RuntimeModelConfig(String apiKey, String apiKeySource, String baseUrl, String model,
                              String reasoningEffort, Integer contextWindowTokens,
                              RuntimeModelProtocol protocol, String instructionPrompt,
                              boolean imageInputSupported, String thinkingFieldName) {
        this(apiKey, apiKeySource, baseUrl, model, reasoningEffort, contextWindowTokens,
                protocol, instructionPrompt, imageInputSupported, thinkingFieldName, null);
    }

    public RuntimeModelConfig(String apiKey, String apiKeySource, String baseUrl, String model,
                              String reasoningEffort, Integer contextWindowTokens,
                              RuntimeModelProtocol protocol, String instructionPrompt,
                              boolean imageInputSupported) {
        this(apiKey, apiKeySource, baseUrl, model, reasoningEffort,
                contextWindowTokens, protocol, instructionPrompt, imageInputSupported, null, null);
    }

    public RuntimeModelConfig(String apiKey, String apiKeySource, String baseUrl, String model,
                              String reasoningEffort, Integer contextWindowTokens,
                              RuntimeModelProtocol protocol, boolean imageInputSupported) {
        this(apiKey, apiKeySource, baseUrl, model, reasoningEffort,
                contextWindowTokens, protocol, null, imageInputSupported, null, null);
    }

    public RuntimeModelConfig(String apiKey, String apiKeySource, String baseUrl, String model,
                              String reasoningEffort, Integer contextWindowTokens,
                              boolean imageInputSupported) {
        this(apiKey, apiKeySource, baseUrl, model, reasoningEffort,
                contextWindowTokens, RuntimeModelProtocol.RESPONSES, null, imageInputSupported, null, null);
    }
}
