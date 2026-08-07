package top.fusb.lingxi.runtime.codex.maintenance;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodexVersion {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?<!\\d)(\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?)(?!\\d)");

    private CodexVersion() {
    }

    static String extract(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = VERSION_PATTERN.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    static int compare(String left, String right) {
        String[] leftParts = requireVersion(left).split("-", 2);
        String[] rightParts = requireVersion(right).split("-", 2);
        String[] leftNumbers = leftParts[0].split("\\.");
        String[] rightNumbers = rightParts[0].split("\\.");
        for (int i = 0; i < 3; i++) {
            int result = Integer.compare(Integer.parseInt(leftNumbers[i]), Integer.parseInt(rightNumbers[i]));
            if (result != 0) {
                return result;
            }
        }
        if ((leftParts.length > 1) != (rightParts.length > 1)) {
            return leftParts.length > 1 ? -1 : 1;
        }
        return leftParts.length == 1 ? 0 : leftParts[1].compareTo(rightParts[1]);
    }

    private static String requireVersion(String value) {
        String version = extract(value);
        if (version == null) {
            throw new IllegalArgumentException("版本号格式不正确：" + value);
        }
        return version;
    }
}
