package top.fusb.lingxi.kit;

public class SecretValueKit {

    private SecretValueKit() {
    }

    /**
     * 生成可用于辨识凭证但无法还原完整值的脱敏摘要。
     *
     * @param value 凭证明文，可为空
     * @return 长凭证保留前 8 位和后 6 位，短凭证保留前后各 3 位；空值返回 null
     */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() <= 8) {
            return "****";
        }
        if (normalized.length() <= 18) {
            return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 3);
        }
        return normalized.substring(0, 8) + "****" + normalized.substring(normalized.length() - 6);
    }
}
