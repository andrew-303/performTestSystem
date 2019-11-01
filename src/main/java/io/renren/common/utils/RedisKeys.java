package io.renren.common.utils;

/**
 * Redis所有keys
 */
public class RedisKeys {

    public static String getSysConfigKey(String key) {
        return "sys:config:" + key;
    }
}
