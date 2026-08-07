package top.fusb.lingxi.runtime.langchain.agent.model;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 对单次模型 API 调用进行错误分类、次数限制和退避计算。
 */
public final class LangChainModelCallPolicy {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    /**
     * 创建显式模型调用重试策略。
     *
     * @param maxAttempts 包含首次调用在内的最大请求次数
     * @param initialBackoff 第一次重试前的等待时间
     * @param maximumBackoff 指数退避允许达到的最大等待时间
     * @throws IllegalArgumentException 次数小于 1、等待时间为负或最大等待小于初始等待时抛出
     */
    public LangChainModelCallPolicy(int maxAttempts, Duration initialBackoff, Duration maximumBackoff) {
        if (maxAttempts < 1 || initialBackoff == null || initialBackoff.isNegative()
                || maximumBackoff == null || maximumBackoff.isNegative()
                || maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("LangChain 模型重试参数无效");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
    }

    /**
     * 根据错误、当前次数和流式输出状态决定是否重试同一个模型请求。
     *
     * @param error 当前模型请求错误
     * @param attempt 当前请求次数，从 1 开始
     * @param partialOutputStarted 是否已经向上游发送任何响应、思考或工具调用增量
     * @return 包含错误分类、是否重试和退避时间的决定
     */
    public Decision evaluate(Throwable error, int attempt, boolean partialOutputStarted) {
        ErrorCategory category = classify(error);
        if (partialOutputStarted) {
            return new Decision(false, category, Duration.ZERO, StopReason.PARTIAL_OUTPUT);
        }
        if (!category.retriable()) {
            return new Decision(false, category, Duration.ZERO, StopReason.NON_RETRIABLE);
        }
        if (attempt >= maxAttempts) {
            return new Decision(false, category, Duration.ZERO, StopReason.ATTEMPTS_EXHAUSTED);
        }
        return new Decision(true, category, backoff(attempt), null);
    }

    private ErrorCategory classify(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof NonRetriableException || current instanceof InterruptedException) {
                return ErrorCategory.NON_RETRIABLE;
            }
            if (current instanceof RateLimitException) {
                return ErrorCategory.RATE_LIMIT;
            }
            if (current instanceof TimeoutException || current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return ErrorCategory.TIMEOUT;
            }
            if (current instanceof RetriableException) {
                return ErrorCategory.SERVER;
            }
            if (current instanceof HttpException httpException) {
                int status = httpException.statusCode();
                if (status == 429) {
                    return ErrorCategory.RATE_LIMIT;
                }
                if (status == 408 || status == 409 || status == 425 || status >= 500) {
                    return status == 408 ? ErrorCategory.TIMEOUT : ErrorCategory.SERVER;
                }
                return ErrorCategory.NON_RETRIABLE;
            }
            if (current instanceof ConnectException || current instanceof SocketException
                    || current instanceof IOException) {
                return ErrorCategory.TRANSPORT;
            }
        }
        return ErrorCategory.UNKNOWN;
    }

    private Duration backoff(int attempt) {
        long initialMillis = initialBackoff.toMillis();
        long maximumMillis = maximumBackoff.toMillis();
        int shift = Math.min(30, Math.max(0, attempt - 1));
        long multiplier = 1L << shift;
        long delayMillis = initialMillis > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : initialMillis * multiplier;
        return Duration.ofMillis(Math.min(maximumMillis, delayMillis));
    }

    public enum ErrorCategory {
        RATE_LIMIT(true),
        TIMEOUT(true),
        SERVER(true),
        TRANSPORT(true),
        NON_RETRIABLE(false),
        UNKNOWN(false);

        private final boolean retriable;

        ErrorCategory(boolean retriable) {
            this.retriable = retriable;
        }

        public boolean retriable() {
            return retriable;
        }
    }

    public enum StopReason {
        NON_RETRIABLE,
        PARTIAL_OUTPUT,
        ATTEMPTS_EXHAUSTED
    }

    public record Decision(boolean shouldRetry,
                           ErrorCategory category,
                           Duration backoff,
                           StopReason stopReason) {
    }
}
