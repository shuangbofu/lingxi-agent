package top.fusb.lingxi.kit;

public class TextKit {

    private TextKit() {
    }

    public static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        String suffix = "\n\n[内容过长，已截断]";
        if (maxLength <= suffix.length()) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - suffix.length()) + suffix;
    }

    public static String blankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
