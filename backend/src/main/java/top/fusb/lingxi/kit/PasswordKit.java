package top.fusb.lingxi.kit;

import top.fusb.lingxi.enums.ErrorCode;
import top.fusb.lingxi.enums.ErrorSubCode;
import top.fusb.lingxi.exception.BizException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public class PasswordKit {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private PasswordKit() {
    }

    /**
     * 生成密码哈希。
     *
     * @param password 明文密码
     * @return 带算法参数和盐值的密码哈希
     * @throws BizException 密码哈希生成失败时抛出
     */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        byte[] digest = digest(password, salt, ITERATIONS, KEY_LENGTH);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(digest);
    }

    /**
     * 校验密码是否匹配。
     *
     * @param password 明文密码
     * @param storedHash 存储的密码哈希
     * @return 匹配返回 true，否则返回 false
     * @throws BizException 密码哈希格式错误时抛出
     */
    public static boolean matches(String password, String storedHash) {
        String[] parts = storedHash == null ? new String[0] : storedHash.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.BAD_CREDENTIALS, "账号或密码错误");
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
        } catch (Exception e) {
            throw new BizException(ErrorCode.AUTH_ERROR, ErrorSubCode.BAD_CREDENTIALS, "账号或密码错误");
        }
        byte[] actual = digest(password, salt, iterations, expected.length * 8);
        return constantTimeEquals(expected, actual);
    }

    private static byte[] digest(String password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLength);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ErrorSubCode.UNKNOWN_ERROR, "密码处理失败");
        }
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < left.length; i++) {
            result |= left[i] ^ right[i];
        }
        return result == 0;
    }
}
