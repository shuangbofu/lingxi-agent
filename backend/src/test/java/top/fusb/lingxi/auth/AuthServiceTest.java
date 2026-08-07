package top.fusb.lingxi.auth;

import top.fusb.lingxi.config.LingxiProperties;
import top.fusb.lingxi.dto.AuthSessionResponse;
import top.fusb.lingxi.dto.InitialAdminSetupRequest;
import top.fusb.lingxi.entity.UserEntity;
import top.fusb.lingxi.exception.BizException;
import top.fusb.lingxi.kit.PasswordKit;
import top.fusb.lingxi.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void waitsForWebSetupWhenDefaultPasswordIsNotConfigured() {
        UserRepository repository = mock(UserRepository.class);
        when(repository.existsByUsername("admin")).thenReturn(false);
        AuthService service = new AuthService(repository, new LingxiProperties());

        service.initializeDefaultUsers();

        verify(repository, never()).save(any());
    }

    @Test
    void reportsInitializationRequiredOnlyForAnEmptyUserTable() {
        UserRepository repository = mock(UserRepository.class);
        AuthService service = new AuthService(repository, new LingxiProperties());
        when(repository.count()).thenReturn(0L, 1L);

        assertThat(service.isInitializationRequired()).isTrue();
        assertThat(service.isInitializationRequired()).isFalse();
    }

    @Test
    void createsInitialAdminAndStartsAuthenticatedSession() {
        UserRepository repository = mock(UserRepository.class);
        HttpSession session = mock(HttpSession.class);
        AuthService service = new AuthService(repository, new LingxiProperties());
        when(repository.count()).thenReturn(0L);
        when(repository.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity user = invocation.getArgument(0);
            user.setId(12L);
            return user;
        });
        InitialAdminSetupRequest request = new InitialAdminSetupRequest();
        request.setPassword("strong-password");

        AuthSessionResponse response = service.setupInitialAdmin(request, session);

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("admin");
        assertThat(PasswordKit.matches("strong-password", captor.getValue().getPasswordHash())).isTrue();
        verify(session).setAttribute(AuthService.SESSION_USER_ID, 12L);
        assertThat(response.isAuthenticated()).isTrue();
        assertThat(response.getUsername()).isEqualTo("admin");
    }

    @Test
    void rejectsSetupAfterAnyUserAlreadyExists() {
        UserRepository repository = mock(UserRepository.class);
        AuthService service = new AuthService(repository, new LingxiProperties());
        when(repository.count()).thenReturn(1L);
        InitialAdminSetupRequest request = new InitialAdminSetupRequest();
        request.setPassword("strong-password");

        assertThatThrownBy(() -> service.setupInitialAdmin(request, mock(HttpSession.class)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("系统已完成初始化");
        verify(repository, never()).saveAndFlush(any());
    }
}
