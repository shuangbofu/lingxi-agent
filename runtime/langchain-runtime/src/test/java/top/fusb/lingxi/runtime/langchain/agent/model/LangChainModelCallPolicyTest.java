package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainModelCallPolicyTest {

    private final LangChainModelCallPolicy policy = new LangChainModelCallPolicy(
            3, Duration.ofMillis(100), Duration.ofMillis(150));

    @Test
    void retriesOnlyExplicitlyRecoverableErrorsWithBoundedBackoff() {
        assertDecision(new RateLimitException("limited"), 1,
                LangChainModelCallPolicy.ErrorCategory.RATE_LIMIT, 100L);
        assertDecision(new TimeoutException("timeout"), 2,
                LangChainModelCallPolicy.ErrorCategory.TIMEOUT, 150L);
        assertDecision(new HttpException(503, "unavailable"), 1,
                LangChainModelCallPolicy.ErrorCategory.SERVER, 100L);
        assertDecision(new IllegalStateException(new ConnectException("refused")), 1,
                LangChainModelCallPolicy.ErrorCategory.TRANSPORT, 100L);
    }

    @Test
    void rejectsNonRetriableUnknownPartialAndExhaustedCalls() {
        assertThat(policy.evaluate(new AuthenticationException("bad key"), 1, false))
                .satisfies(decision -> {
                    assertThat(decision.shouldRetry()).isFalse();
                    assertThat(decision.stopReason())
                            .isEqualTo(LangChainModelCallPolicy.StopReason.NON_RETRIABLE);
                });
        assertThat(policy.evaluate(new IllegalArgumentException("bad request"), 1, false).shouldRetry())
                .isFalse();
        assertThat(policy.evaluate(new RateLimitException("limited"), 1, true).stopReason())
                .isEqualTo(LangChainModelCallPolicy.StopReason.PARTIAL_OUTPUT);
        assertThat(policy.evaluate(new RateLimitException("limited"), 3, false).stopReason())
                .isEqualTo(LangChainModelCallPolicy.StopReason.ATTEMPTS_EXHAUSTED);
    }

    private void assertDecision(Throwable error,
                                int attempt,
                                LangChainModelCallPolicy.ErrorCategory category,
                                long delayMillis) {
        LangChainModelCallPolicy.Decision decision = policy.evaluate(error, attempt, false);
        assertThat(decision.shouldRetry()).isTrue();
        assertThat(decision.category()).isEqualTo(category);
        assertThat(decision.backoff()).isEqualTo(Duration.ofMillis(delayMillis));
    }
}
