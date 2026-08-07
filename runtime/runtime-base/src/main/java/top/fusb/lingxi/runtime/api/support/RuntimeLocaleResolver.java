package top.fusb.lingxi.runtime.api.support;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class RuntimeLocaleResolver {

    private static final Duration LOCALE_COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static volatile Set<String> cachedAvailableLocales;

    private RuntimeLocaleResolver() {
    }

    /**
     * 根据系统真实安装的 locale 列表解析子进程可用 locale。
     *
     * @param preferredLocale 配置里声明的优先 locale
     * @return 可安全注入到子进程环境的 locale；无法探测时返回空
     */
    public static Optional<String> resolveProcessLocale(String preferredLocale) {
        return resolveProcessLocale(preferredLocale, availableLocales(), System.getenv());
    }

    static Optional<String> resolveProcessLocale(String preferredLocale, Set<String> availableLocales,
                                                 Map<String, String> environment) {
        if (availableLocales == null || availableLocales.isEmpty()) {
            return Optional.empty();
        }
        for (String candidate : localeCandidates(preferredLocale, environment)) {
            Optional<String> matched = matchAvailableLocale(availableLocales, candidate);
            if (matched.isPresent()) {
                return matched;
            }
        }
        return availableLocales.stream()
                .filter(RuntimeLocaleResolver::isUtf8Locale)
                .findFirst()
                .or(() -> matchAvailableLocale(availableLocales, "C"));
    }

    private static List<String> localeCandidates(String preferredLocale, Map<String, String> environment) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, preferredLocale);
        if (environment != null) {
            addCandidate(candidates, environment.get("LC_ALL"));
            addCandidate(candidates, environment.get("LC_CTYPE"));
            addCandidate(candidates, environment.get("LANG"));
        }
        return new ArrayList<>(candidates);
    }

    private static void addCandidate(Set<String> candidates, String value) {
        String candidate = blankToNull(value);
        if (candidate != null) {
            candidates.add(candidate);
        }
    }

    private static Optional<String> matchAvailableLocale(Set<String> availableLocales, String candidate) {
        String normalizedCandidate = normalizeLocale(candidate);
        return availableLocales.stream()
                .filter(locale -> normalizeLocale(locale).equals(normalizedCandidate))
                .findFirst();
    }

    private static boolean isUtf8Locale(String locale) {
        return normalizeLocale(locale).endsWith("utf8");
    }

    private static String normalizeLocale(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> availableLocales() {
        Set<String> locales = cachedAvailableLocales;
        if (locales != null) {
            return locales;
        }
        synchronized (RuntimeLocaleResolver.class) {
            if (cachedAvailableLocales == null) {
                cachedAvailableLocales = readAvailableLocales();
            }
            return cachedAvailableLocales;
        }
    }

    private static Set<String> readAvailableLocales() {
        LinkedHashSet<String> locales = new LinkedHashSet<>();
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("locale", "-a").redirectErrorStream(true);
            builder.environment().put("LANG", "C");
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("LC_CTYPE", "C");
            process = builder.start();
            boolean finished = process.waitFor(LOCALE_COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return locales;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String locale = blankToNull(line);
                    if (locale != null && isLocaleName(locale)) {
                        locales.add(locale);
                    }
                }
            }
        } catch (Exception ignored) {
            return locales;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return locales;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean isLocaleName(String value) {
        return value.matches("[A-Za-z0-9_@.+-]+");
    }
}
