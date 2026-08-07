package top.fusb.lingxi.runtime.api.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeLocaleResolverTest {

    @Test
    void matchesEquivalentUtf8LocaleNameInstalledBySystem() {
        Set<String> available = new LinkedHashSet<>(Set.of("C", "zh_CN.utf8", "en_US.utf8"));

        assertThat(RuntimeLocaleResolver.resolveProcessLocale(
                "zh_CN.UTF-8", available, Map.of("LC_ALL", "C.UTF-8")))
                .contains("zh_CN.utf8");
    }

    @Test
    void ignoresUnavailableInheritedLocaleAndFallsBackToInstalledUtf8Locale() {
        LinkedHashSet<String> available = new LinkedHashSet<>();
        available.add("C");
        available.add("en_US.utf8");

        assertThat(RuntimeLocaleResolver.resolveProcessLocale(
                null, available, Map.of("LANG", "C.UTF-8")))
                .contains("en_US.utf8");
    }
}
