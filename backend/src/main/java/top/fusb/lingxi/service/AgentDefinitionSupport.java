package top.fusb.lingxi.service;

import org.springframework.stereotype.Component;
import top.fusb.lingxi.definition.ModuleGuideDefinition;
import top.fusb.lingxi.definition.ModuleParameterDefinition;
import top.fusb.lingxi.dto.AgentDefinitionGuideResponse;
import top.fusb.lingxi.dto.AgentDefinitionParameterResponse;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AgentDefinitionSupport {

    private static final Pattern CAPABILITY_CODE_PATTERN = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /**
     * 规范化场景声明的能力编码，允许引用尚未安装的外置 Skill。
     *
     * @param capabilities 场景声明的能力编码
     * @return 去除空值并保持原顺序的能力编码集合
     * @throws BizException 能力编码不符合标准 Skill 名称格式时抛出
     */
    public Set<String> parseCapabilities(Set<String> capabilities) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (capabilities == null) {
            return result;
        }
        for (String capability : capabilities) {
            if (capability == null || capability.isBlank()) {
                continue;
            }
            String code = capability.trim();
            if (code.length() > 64 || !CAPABILITY_CODE_PATTERN.matcher(code).matches()) {
                throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED,
                        "能力编码必须是最多 64 位的小写连字符格式：" + code);
            }
            result.add(code);
        }
        return result;
    }

    /**
     * 将文件中的参数定义转换为管理接口响应。
     *
     * @param definition manifest 或 lingxi.json 中的参数定义
     * @return 不包含数据库 ID 的参数响应
     */
    public AgentDefinitionParameterResponse parameterResponse(ModuleParameterDefinition definition) {
        AgentDefinitionParameterResponse response = new AgentDefinitionParameterResponse();
        response.setKey(definition.getKey());
        response.setName(definition.getName());
        response.setType(definition.getType());
        response.setRequired(Boolean.TRUE.equals(definition.getRequired()));
        response.setDescription(definition.getDescription());
        response.setOptions(definition.getOptions());
        response.setDefaultValue(definition.getDefaultValue());
        response.setVisible(definition.getVisible() == null || Boolean.TRUE.equals(definition.getVisible()));
        response.setSortOrder(definition.getSortOrder());
        return response;
    }

    /**
     * 将文件中的 Guide 定义及正文转换为管理接口响应。
     *
     * @param definition manifest 或 lingxi.json 中的 Guide 定义
     * @param content Guide 文件正文
     * @return 不包含数据库 ID 的 Guide 响应
     */
    public AgentDefinitionGuideResponse guideResponse(ModuleGuideDefinition definition, String content) {
        AgentDefinitionGuideResponse response = new AgentDefinitionGuideResponse();
        response.setKey(definition.getKey());
        response.setTitle(definition.getTitle());
        response.setDescription(definition.getDescription());
        response.setContent(content);
        response.setSortOrder(definition.getSortOrder());
        return response;
    }
}
