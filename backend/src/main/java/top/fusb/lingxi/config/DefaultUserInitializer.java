package top.fusb.lingxi.config;

import top.fusb.lingxi.auth.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultUserInitializer {

    private final AuthService authService;

    /**
     * 应用启动后初始化默认用户。
     *
     * @return 无返回值
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        log.info("开始检查默认用户初始化状态");
        authService.initializeDefaultUsers();
        log.info("默认用户初始化状态检查完成");
    }
}
