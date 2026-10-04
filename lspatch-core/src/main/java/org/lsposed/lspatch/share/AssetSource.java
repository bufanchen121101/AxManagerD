package org.lsposed.lspatch.share;

import java.io.InputStream;

/**
 * 【便捷版】可注入的资源来源（改造隔离层）。
 *
 * 背景：完整版把修补所需的资源（metaloader.dex / loader.dex / so / public.xml ...）
 * 直接打包进 APK 的 assets；便捷版为了瘦身不带这些大资源，改由用户在安装
 * 「Xposed 修补核心」模块后，由该模块把资源解压到 App 私有目录提供。
 *
 * 行为约定（对完整版零影响）：
 *  - 未注入 Provider 时：只读本 APK 的 assets，与改造前完全一致。
 *  - 注入 Provider 后：先问 Provider；Provider 返回 null 表示「我这儿没有」，
 *    再回退到本 APK 的 assets。两条路都没有则返回 null，调用方照旧抛
 *    「资源缺失」异常，UI 层据此提示用户安装模块。
 */
public final class AssetSource {

    public interface Provider {
        /**
         * @param assetPath 形如 "assets/lspatch/loader.dex" 的 APK 内路径
         * @return 输入流；返回 null 表示该 Provider 不提供此资源
         */
        InputStream open(String assetPath);
    }

    private static volatile Provider provider;

    private AssetSource() {
    }

    public static void setProvider(Provider p) {
        provider = p;
    }

    public static Provider getProvider() {
        return provider;
    }

    /** 打开资源：Provider 优先，其次本 APK 的 assets。 */
    public static InputStream open(String assetPath) {
        Provider p = provider;
        if (p != null) {
            try {
                InputStream is = p.open(assetPath);
                if (is != null) return is;
            } catch (Throwable ignored) {
                // Provider 异常不应影响回退逻辑
            }
        }
        return AssetSource.class.getClassLoader().getResourceAsStream(assetPath);
    }

    /** 资源是否可用（不消费流）。 */
    public static boolean available(String assetPath) {
        Provider p = provider;
        if (p != null) {
            try {
                InputStream is = p.open(assetPath);
                if (is != null) {
                    try {
                        is.close();
                    } catch (Throwable ignored) {
                    }
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        try (InputStream is = AssetSource.class.getClassLoader().getResourceAsStream(assetPath)) {
            return is != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
