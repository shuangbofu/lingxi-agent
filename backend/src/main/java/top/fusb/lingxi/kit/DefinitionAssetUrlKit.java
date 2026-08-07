package top.fusb.lingxi.kit;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

public final class DefinitionAssetUrlKit {

    private DefinitionAssetUrlKit() {
    }

    /**
     * 生成带定义更新时间版本的图标地址，使同名资源更新后不再命中旧浏览器缓存。
     *
     * @param kind 定义类型目录
     * @param code 定义编码
     * @param icon 图标文件名
     * @param updatedAt 定义更新时间
     * @return 未配置图标时返回 null，否则返回带版本参数的图标地址
     */
    public static String iconUrl(String kind, String code, String icon, LocalDateTime updatedAt) {
        if (TextKit.blankToNull(icon) == null) {
            return null;
        }
        long version = updatedAt == null ? 0 : updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        return "/api/definition-assets/" + kind + "/" + code + "/icon?v=" + version;
    }
}
