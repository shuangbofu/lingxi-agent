package top.fusb.lingxi.auth;

import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.PageResult;
import top.fusb.lingxi.dto.UserResponse;
import top.fusb.lingxi.dto.UserSaveRequest;
import top.fusb.lingxi.dto.UserUsageSettingsRequest;
import top.fusb.lingxi.dto.UserUsageQuotaResponse;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.UserRole;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.PasswordKit;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final LingxiProperties lingxiProperties;
    private final UserUsageQuotaService userUsageQuotaService;

    /**
     * 查询用户列表。
     *
     * @return 用户列表
     */
    public List<UserResponse> list() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(UserEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toResponse)
                .toList();
    }

    /**
     * 分页查询用户。
     *
     * @param page 页码，从 1 开始
     * @param size 每页数量
     * @return 用户分页
     */
    public PageResult<UserResponse> page(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        Page<UserEntity> result = userRepository.findAllByOrderByCreatedAtAsc(PageRequest.of(safePage - 1, safeSize));
        log.info("分页查询用户 page={} size={} total={}", safePage, safeSize, result.getTotalElements());
        return new PageResult<>(result.getContent().stream().map(this::toResponse).toList(), result.getTotalElements(), safePage, safeSize);
    }

    /**
     * 创建用户。
     *
     * @param request 用户保存参数
     * @return 创建后的用户
     * @throws BizException 用户名重复、默认密码未配置或角色不支持时抛出
     */
    @Transactional
    public UserResponse create(UserSaveRequest request) {
        String username = request.getUsername().trim();
        if (userRepository.existsByUsername(username)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "账号已存在");
        }
        String password = TextKit.blankToNull(request.getPassword());
        String effectivePassword = password == null ? TextKit.blankToNull(lingxiProperties.getUser().getDefaultPassword()) : password;
        if (effectivePassword == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "默认密码未配置");
        }
        UserEntity entity = new UserEntity();
        entity.setUsername(username);
        applyInput(entity, request);
        entity.setPasswordHash(PasswordKit.hash(effectivePassword));
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getCreatedAt());
        UserEntity saved = userRepository.save(entity);
        log.info("创建用户 id={} username={} role={}", saved.getId(), saved.getUsername(), saved.getRole());
        return toResponse(saved);
    }

    /**
     * 更新用户。
     *
     * @param id 用户 ID
     * @param request 用户保存参数
     * @return 更新后的用户
     * @throws BizException 用户不存在、用户名重复或角色不支持时抛出
     */
    @Transactional
    public UserResponse update(Long id, UserSaveRequest request) {
        UserEntity entity = requireEntity(id);
        String username = request.getUsername().trim();
        if (!entity.getUsername().equals(username) && userRepository.existsByUsername(username)) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "账号已存在");
        }
        entity.setUsername(username);
        applyInput(entity, request);
        String password = TextKit.blankToNull(request.getPassword());
        if (password != null) {
            entity.setPasswordHash(PasswordKit.hash(password));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        UserEntity saved = userRepository.save(entity);
        log.info("更新用户 id={} username={} role={} enabled={}", saved.getId(), saved.getUsername(), saved.getRole(), saved.isEnabled());
        return toResponse(saved);
    }

    /**
     * 管理员更新指定用户的日、周、月 Token 配额。
     *
     * @param id 用户 ID
     * @param request 用量限制设置；配额为空时继承全局设置
     * @return 更新后的用户信息
     * @throws BizException 用户不存在或配额无效时抛出
     */
    @Transactional
    public UserResponse updateUsageSettings(Long id, UserUsageSettingsRequest request) {
        UserEntity entity = requireEntity(id);
        entity.setDailyTokenLimit(request.getDailyTokenLimit());
        entity.setWeeklyTokenLimit(request.getWeeklyTokenLimit());
        entity.setMonthlyTokenLimit(request.getMonthlyTokenLimit());
        entity.setUpdatedAt(LocalDateTime.now());
        UserEntity saved = userRepository.save(entity);
        log.info("管理员更新用户用量设置 userId={} username={} dailyTokenLimit={} weeklyTokenLimit={} monthlyTokenLimit={}",
                saved.getId(), saved.getUsername(), saved.getDailyTokenLimit(), saved.getWeeklyTokenLimit(), saved.getMonthlyTokenLimit());
        return toResponse(saved);
    }

    /**
     * 查询指定用户当前自然月的 Token 配额进度。
     *
     * @param id 用户 ID
     * @return 当前自然月配额状态
     * @throws BizException 用户不存在时抛出
     */
    public UserUsageQuotaResponse usageQuota(Long id) {
        return userUsageQuotaService.current(requireEntity(id));
    }

    private UserEntity requireEntity(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, ErrorSubCode.VALIDATION_FAILED, "用户不存在"));
    }

    private void applyInput(UserEntity entity, UserSaveRequest request) {
        entity.setDisplayName(request.getDisplayName().trim());
        entity.setAvatarUrl(TextKit.blankToNull(request.getAvatarUrl()));
        entity.setRole(UserRole.parse(request.getRole()));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private UserResponse toResponse(UserEntity entity) {
        UserResponse response = new UserResponse();
        response.setId(entity.getId());
        response.setUsername(entity.getUsername());
        response.setDisplayName(entity.getDisplayName());
        response.setAvatarUrl(entity.getAvatarUrl());
        response.setRole(entity.getRole().name());
        response.setRoleName(entity.getRole().getDescription());
        response.setDailyTokenLimit(entity.getDailyTokenLimit());
        response.setWeeklyTokenLimit(entity.getWeeklyTokenLimit());
        response.setMonthlyTokenLimit(entity.getMonthlyTokenLimit());
        response.setEnabled(entity.isEnabled());
        response.setLastLoginAt(entity.getLastLoginAt());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        return response;
    }
}
