package top.fusb.lingxi.auth;

import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.AuthSessionResponse;
import top.fusb.lingxi.dto.InitialAdminSetupRequest;
import top.fusb.lingxi.dto.LoginRequest;
import top.fusb.lingxi.dto.MenuItemResponse;
import top.fusb.lingxi.dto.PasswordChangeRequest;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.enums.UserRole;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.PasswordKit;
import top.fusb.lingxi.kit.TextKit;
import top.fusb.lingxi.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String SESSION_USER_ID = "WORKBENCH_USER_ID";

    private static final List<MenuItemResponse> MENUS = List.of(
            new MenuItemResponse("ANALYSIS", "分析入口", "分析", "/", "analysis", "sky", "ANALYSIS_USE"),
            new MenuItemResponse("DASHBOARD", "仪表盘", "仪表", "/admin/dashboard", "dashboard", "blue", "DASHBOARD_ADMIN"),
            new MenuItemResponse("SCENARIOS", "场景入口", "场景", "/admin/scenarios", "definition", "violet", "DEFINITION_ADMIN"),
            new MenuItemResponse("CAPABILITY_CONFIGS", "能力管理", "能力", "/admin/capabilities", "capabilityConfig", "teal", "DEFINITION_ADMIN"),
            new MenuItemResponse("ANALYSIS_PREMISES", "分析情境", "情境", "/admin/premises", "premise", "amber", "ANALYSIS_PREMISE_ADMIN"),
            new MenuItemResponse("TASK_RECORDS", "分析记录", "记录", "/admin/tasks/records", "task", "orange", "TASK_ADMIN"),
            new MenuItemResponse("USERS", "用户管理", "用户", "/admin/users", "user", "rose", "USER_ADMIN"),
            new MenuItemResponse("MODELS", "模型管理", "模型", "/admin/models", "model", "cyan", "SYSTEM_ADMIN"),
            new MenuItemResponse("SETTINGS", "系统设置", "设置", "/admin/settings", "settings", "slate", "SYSTEM_ADMIN")
    );

    private final UserRepository userRepository;
    private final LingxiProperties lingxiProperties;

    @Transactional
    public void initializeDefaultUsers() {
        if (userRepository.existsByUsername("admin")) {
            return;
        }
        String defaultPassword = lingxiProperties.getUser().getDefaultPassword();
        if (TextKit.blankToNull(defaultPassword) == null) {
            log.info("未配置初始管理员密码，等待通过首次初始化页面创建管理员");
            return;
        }
        createDefaultUser("admin", "管理员", defaultPassword, UserRole.ADMIN);
    }

    /**
     * 判断当前部署是否需要创建首个管理员。
     *
     * @return 用户表为空时返回 true
     */
    @Transactional(readOnly = true)
    public boolean isInitializationRequired() {
        return userRepository.count() == 0;
    }

    /**
     * 在全新部署中创建首个管理员并建立登录会话。
     *
     * @param request 首个管理员密码
     * @param session 当前 HTTP 会话
     * @return 已登录的管理员会话信息
     * @throws BizException 用户表已有数据时抛出
     */
    @Transactional
    public synchronized AuthSessionResponse setupInitialAdmin(InitialAdminSetupRequest request, HttpSession session) {
        if (userRepository.count() != 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "系统已完成初始化");
        }
        UserEntity user = new UserEntity();
        user.setUsername("admin");
        user.setDisplayName("管理员");
        user.setPasswordHash(PasswordKit.hash(request.getPassword()));
        user.setRole(UserRole.ADMIN);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        UserEntity saved = userRepository.saveAndFlush(user);
        session.setAttribute(SESSION_USER_ID, saved.getId());
        log.info("通过首次初始化页面创建管理员 username={}", saved.getUsername());
        return toSession(saved);
    }

    @Transactional
    public AuthSessionResponse login(LoginRequest request, HttpSession session) {
        String username = request.getUsername().trim();
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.BAD_CREDENTIALS, "账号或密码错误"));
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.USER_DISABLED, "账号已停用");
        }
        if (!PasswordKit.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.BAD_CREDENTIALS, "账号或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(user.getLastLoginAt());
        userRepository.save(user);
        session.setAttribute(SESSION_USER_ID, user.getId());
        log.info("用户登录 username={} role={}", user.getUsername(), user.getRole());
        return toSession(user);
    }

    public void logout(HttpSession session) {
        session.invalidate();
    }

    @Transactional
    public void changePassword(PasswordChangeRequest request, HttpSession session) {
        UserEntity user = requireUser(session);
        String oldPassword = TextKit.blankToNull(request.getOldPassword());
        String newPassword = TextKit.blankToNull(request.getNewPassword());
        if (oldPassword == null || newPassword == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "请输入原密码和新密码");
        }
        if (!PasswordKit.matches(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.BAD_CREDENTIALS, "原密码错误");
        }
        user.setPasswordHash(PasswordKit.hash(newPassword));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("用户修改密码 username={}", user.getUsername());
    }

    public AuthSessionResponse current(HttpSession session) {
        Object userId = session.getAttribute(SESSION_USER_ID);
        if (!(userId instanceof Long id)) {
            return anonymousResponse();
        }
        return userRepository.findById(id)
                .filter(UserEntity::isEnabled)
                .map(this::toSession)
                .orElseGet(this::anonymousResponse);
    }

    public UserEntity requireUser(HttpSession session) {
        if (session == null) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.UNAUTHORIZED, "请先登录");
        }
        Object userId = session.getAttribute(SESSION_USER_ID);
        if (!(userId instanceof Long id)) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.UNAUTHORIZED, "请先登录");
        }
        UserEntity user = userRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.UNAUTHORIZED, "请先登录"));
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.USER_DISABLED, "账号已停用");
        }
        return user;
    }

    public boolean hasPermission(UserEntity user, String permission) {
        String value = TextKit.blankToNull(permission);
        return value == null || user.getRole().getPermissions().contains(value);
    }

    private void createDefaultUser(String username, String displayName, String password, UserRole role) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        String effectivePassword = TextKit.blankToNull(password);
        if (effectivePassword == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, ErrorSubCode.VALIDATION_FAILED, "默认账号密码未配置");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setPasswordHash(PasswordKit.hash(effectivePassword));
        user.setRole(role);
        user.setEnabled(true);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(user.getCreatedAt());
        userRepository.save(user);
        log.info("初始化默认用户 username={} role={}", username, role);
    }

    private AuthSessionResponse toSession(UserEntity user) {
        AuthSessionResponse response = new AuthSessionResponse();
        response.setAuthenticated(true);
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setDisplayName(user.getDisplayName());
        response.setAvatarUrl(user.getAvatarUrl());
        response.setRole(user.getRole().name());
        response.setRoleName(user.getRole().getDescription());
        response.setPermissions(user.getRole().getPermissions());
        response.setMenus(visibleMenus(user.getRole().getPermissions()));
        return response;
    }

    private List<MenuItemResponse> visibleMenus(Set<String> permissions) {
        return MENUS.stream()
                .filter(menu -> permissions.contains(menu.getPermission()))
                .toList();
    }

    private AuthSessionResponse anonymousResponse() {
        AuthSessionResponse response = new AuthSessionResponse();
        response.setAuthenticated(false);
        response.setPermissions(Set.of());
        response.setMenus(List.of());
        return response;
    }
}
