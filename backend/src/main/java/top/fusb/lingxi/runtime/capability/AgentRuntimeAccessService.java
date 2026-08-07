package top.fusb.lingxi.runtime.capability;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRuntimeAccessService {

    public static final String TOKEN_HEADER = "X-Agent-Runtime-Token";
    public static final String TASK_ID_ATTRIBUTE = AgentRuntimeAccessService.class.getName() + ".taskId";
    private static final Set<String> COMMON_RUNTIME_PATHS = Set.of(
            "/api/agent-runtime/capability-configs",
            "/api/agent-runtime/context",
            "/api/agent-runtime/resources",
            "/api/agent-runtime/interactions"
    );
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, RuntimeGrant> grants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> taskTokenHashes = new ConcurrentHashMap<>();

    /**
     * 为正在运行的任务签发一次性运行时令牌。
     *
     * @param taskId 任务 ID
     * @param capabilityCodes 当前任务允许使用的能力模块 code
     * @param moduleRuntimePaths 已挂载能力模块声明的内部接口路径前缀
     * @return 只在当前任务运行期间有效的随机令牌
     */
    public String issue(Long taskId, Set<String> capabilityCodes, Set<String> moduleRuntimePaths) {
        revoke(taskId);
        byte[] value = new byte[32];
        SECURE_RANDOM.nextBytes(value);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        String tokenHash = hash(token);
        Set<String> runtimePaths = new java.util.LinkedHashSet<>(COMMON_RUNTIME_PATHS);
        if (moduleRuntimePaths != null) {
            runtimePaths.addAll(moduleRuntimePaths);
        }
        grants.put(tokenHash, new RuntimeGrant(
                taskId,
                capabilityCodes == null ? Set.of() : Set.copyOf(capabilityCodes),
                Set.copyOf(runtimePaths)
        ));
        taskTokenHashes.put(taskId, tokenHash);
        return token;
    }

    /**
     * 校验能力运行时令牌，并限制请求只能访问令牌所属任务。
     *
     * @param token 请求头中的运行时令牌
     * @param requestedTaskId 请求参数中的任务 ID，可为空
     * @return 令牌所属任务及允许的能力模块
     * @throws BizException 令牌无效或跨任务访问时抛出
     */
    public RuntimeGrant require(String token, Long requestedTaskId) {
        RuntimeGrant grant = token == null || token.isBlank() ? null : grants.get(hash(token.trim()));
        if (grant == null) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.UNAUTHORIZED, "能力运行时凭证无效");
        }
        if (requestedTaskId != null && !requestedTaskId.equals(grant.taskId())) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "能力运行时不能访问其他任务");
        }
        return grant;
    }

    /**
     * 校验当前令牌是否允许访问指定运行时接口路径。
     *
     * @param grant 已通过令牌校验的任务授权
     * @param requestPath 当前请求路径
     * @return 无返回值
     * @throws BizException 路径未由平台协议或已挂载模块声明时抛出
     */
    public void requirePath(RuntimeGrant grant, String requestPath) {
        String path = requestPath == null ? "" : requestPath;
        boolean allowed = grant.runtimePaths().stream()
                .anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
        if (!allowed) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.FORBIDDEN, "当前能力无权访问该运行时接口");
        }
    }

    /**
     * 任务结束后撤销运行时令牌。
     *
     * @param taskId 任务 ID
     * @return 无返回值
     */
    public void revoke(Long taskId) {
        if (taskId == null) {
            return;
        }
        String tokenHash = taskTokenHashes.remove(taskId);
        if (tokenHash != null) {
            grants.remove(tokenHash);
        }
    }

    private String hash(String token) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash agent runtime token", e);
        }
    }

    public record RuntimeGrant(Long taskId, Set<String> capabilityCodes, Set<String> runtimePaths) {
    }
}
