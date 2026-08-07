package top.fusb.lingxi.runtime.langchain.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.IllegalFormatException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LangChainPromptKit {

    private static final String PROMPT_RESOURCE_ROOT = "/prompts/";
    private static final Map<String, String> PROMPT_CACHE = new ConcurrentHashMap<>();

    private LangChainPromptKit() {
    }

    /**
     * 从统一资源目录读取并缓存 UTF-8 Prompt。
     *
     * @param resourceName prompts 目录下的资源文件名
     * @return 去除首尾空白后的 Prompt 内容
     * @throws IllegalArgumentException 资源文件名为空或包含目录跳转时抛出
     * @throws IllegalStateException Prompt 资源不存在或读取失败时抛出
     */
    public static String load(String resourceName) {
        if (resourceName == null || resourceName.isBlank()
                || resourceName.contains("..") || resourceName.startsWith("/")) {
            throw new IllegalArgumentException("Prompt 资源文件名无效");
        }
        return PROMPT_CACHE.computeIfAbsent(resourceName, LangChainPromptKit::read);
    }

    /**
     * 读取 Prompt 模板并使用 Java 格式占位符装配动态上下文。
     *
     * @param resourceName prompts 目录下的模板资源文件名
     * @param arguments 按模板占位符顺序传入的动态值
     * @return 已完成动态值装配的 Prompt
     * @throws IllegalArgumentException 模板占位符与参数不匹配时抛出
     * @throws IllegalStateException Prompt 资源不存在或读取失败时抛出
     */
    public static String format(String resourceName, Object... arguments) {
        try {
            return load(resourceName).formatted(arguments);
        } catch (IllegalFormatException exception) {
            throw new IllegalArgumentException("Prompt 模板参数不匹配：" + resourceName, exception);
        }
    }

    private static String read(String resourceName) {
        String resourcePath = PROMPT_RESOURCE_ROOT + resourceName;
        try (InputStream input = LangChainPromptKit.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("Prompt 资源未找到：" + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Prompt 资源失败：" + resourcePath, exception);
        }
    }
}
