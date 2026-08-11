package top.fusb.lingxi.runtime.config;

import top.fusb.lingxi.kit.TextKit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeConfigService {

    private static final long CONFIG_ID = 1L;
    private static final int DEFAULT_TASK_CONCURRENCY = 2;
    private static final int DEFAULT_TASK_EXECUTION_TIMEOUT_MINUTES = 60;
    private static final String DEFAULT_GLOBAL_BOUNDARY_PROMPT = """
            - 只处理当前任务目标，不处理通用闲聊、写作、翻译、外部常识问答或与当前任务无关的问题。
            - 除当前任务明确要求外，不修改文件、不提交、不推送、不执行破坏性操作。
            - 不访问与当前任务无关的私有文件、系统配置、凭证或外部资源。
            - 不把未经验证的信息包装成确定结论；证据不足时说明不确定性。
            - 已经形成充分证据后停止扩展，避免无效消耗。
            - 输出时避免无信息量的 AI 套话、空泛转折、自我反驳和无效铺垫；只有存在明确对照对象时才使用对比式表达。
            """;

    private final RuntimeConfigRepository runtimeConfigRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ModelCatalogService modelCatalogService;

    /**
     * 获取全局执行设置实体，不存在时返回未持久化的默认实体。
     *
     * @return 全局执行设置
     */
    public RuntimeConfigEntity getEntity() {
        return runtimeConfigRepository.findById(CONFIG_ID).orElseGet(() -> {
            RuntimeConfigEntity entity = new RuntimeConfigEntity();
            entity.setId(CONFIG_ID);
            entity.setUpdatedAt(LocalDateTime.now());
            return entity;
        });
    }

    /**
     * 查询全局执行设置。
     *
     * @return 全局执行设置响应
     */
    @Transactional(readOnly = true)
    public RuntimeConfigResponse detail() {
        return toResponse(getEntity());
    }

    /**
     * 保存全局执行设置。
     *
     * @param request 全局执行设置参数
     * @return 已保存的全局执行设置
     */
    @Transactional
    public RuntimeConfigResponse save(RuntimeConfigRequest request) {
        RuntimeConfigEntity entity = getEntity();
        entity.setMaxTaskConcurrency(normalizeTaskConcurrency(request.getMaxTaskConcurrency()));
        entity.setTaskExecutionTimeoutMinutes(
                normalizeTaskExecutionTimeoutMinutes(request.getTaskExecutionTimeoutMinutes()));
        entity.setGlobalDailyTokenLimit(request.getGlobalDailyTokenLimit());
        entity.setGlobalWeeklyTokenLimit(request.getGlobalWeeklyTokenLimit());
        entity.setGlobalMonthlyTokenLimit(request.getGlobalMonthlyTokenLimit());
        entity.setGlobalBoundaryPrompt(TextKit.blankToNull(request.getGlobalBoundaryPrompt()));
        entity.setUpdatedAt(LocalDateTime.now());
        RuntimeConfigEntity saved = runtimeConfigRepository.save(entity);
        eventPublisher.publishEvent(new RuntimeConfigChangedEvent());
        log.info("保存系统设置 maxTaskConcurrency={} taskExecutionTimeoutMinutes={} dailyLimit={} weeklyLimit={} monthlyLimit={} globalBoundaryConfigured={}",
                saved.getMaxTaskConcurrency(), saved.getTaskExecutionTimeoutMinutes(),
                saved.getGlobalDailyTokenLimit(), saved.getGlobalWeeklyTokenLimit(),
                saved.getGlobalMonthlyTokenLimit(), TextKit.blankToNull(saved.getGlobalBoundaryPrompt()) != null);
        return toResponse(saved);
    }

    /**
     * 返回平台任务总并行度。
     *
     * @return 1 到 20 之间的并行度
     */
    public int taskConcurrency() {
        return effectiveTaskConcurrency(getEntity());
    }

    /**
     * 返回平台单次任务最长执行秒数。
     *
     * @return 系统设置中的任务执行分钟数转换后的秒数
     */
    public long taskExecutionTimeoutSeconds() {
        return TimeUnit.MINUTES.toSeconds(effectiveTaskExecutionTimeoutMinutes(getEntity()));
    }

    /**
     * 读取平台、供应商和模型三级任务并行度策略。
     *
     * @return 可供任务调度器使用的并行度策略
     */
    public RuntimeConcurrencyPolicy concurrencyPolicy() {
        return modelCatalogService.concurrencyPolicy(taskConcurrency());
    }

    /**
     * 返回有效的全局任务边界提示词。
     *
     * @return 管理员配置值或平台默认值
     */
    public String globalBoundaryPrompt() {
        String configured = TextKit.blankToNull(getEntity().getGlobalBoundaryPrompt());
        return configured == null ? DEFAULT_GLOBAL_BOUNDARY_PROMPT.trim() : configured;
    }

    private RuntimeConfigResponse toResponse(RuntimeConfigEntity entity) {
        RuntimeConfigResponse response = new RuntimeConfigResponse();
        response.setMaxTaskConcurrency(effectiveTaskConcurrency(entity));
        response.setTaskExecutionTimeoutMinutes(effectiveTaskExecutionTimeoutMinutes(entity));
        response.setGlobalDailyTokenLimit(entity.getGlobalDailyTokenLimit());
        response.setGlobalWeeklyTokenLimit(entity.getGlobalWeeklyTokenLimit());
        response.setGlobalMonthlyTokenLimit(entity.getGlobalMonthlyTokenLimit());
        response.setGlobalBoundaryPrompt(TextKit.blankToNull(entity.getGlobalBoundaryPrompt()) == null
                ? DEFAULT_GLOBAL_BOUNDARY_PROMPT.trim() : entity.getGlobalBoundaryPrompt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }

    private int effectiveTaskConcurrency(RuntimeConfigEntity entity) {
        Integer value = entity.getMaxTaskConcurrency();
        return value == null ? DEFAULT_TASK_CONCURRENCY : Math.min(Math.max(value, 1), 20);
    }

    private Integer normalizeTaskConcurrency(Integer value) {
        return value == null ? DEFAULT_TASK_CONCURRENCY : Math.min(Math.max(value, 1), 20);
    }

    private int effectiveTaskExecutionTimeoutMinutes(RuntimeConfigEntity entity) {
        Integer value = entity.getTaskExecutionTimeoutMinutes();
        return value == null ? DEFAULT_TASK_EXECUTION_TIMEOUT_MINUTES : Math.min(Math.max(value, 5), 1440);
    }

    private Integer normalizeTaskExecutionTimeoutMinutes(Integer value) {
        return value == null ? DEFAULT_TASK_EXECUTION_TIMEOUT_MINUTES : Math.min(Math.max(value, 5), 1440);
    }
}
