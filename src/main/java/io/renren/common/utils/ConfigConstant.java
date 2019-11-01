package io.renren.common.utils;

import java.util.Locale;

/**
 * 系统参数相关key
 */
public class ConfigConstant {
    /**
     * 云存储配置key
     */
    public final static String CLOUD_STORAGE_CONFIG_KEY = "CLOUD_STORAGE_CONFIG_KEY";

    /**
     * 获取操作系统信息
     */
    public final static String OS_NAME_LC = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);

}
