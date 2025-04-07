package com.limelight.utils;

import java.security.MessageDigest;

public class MathUtils {

    public static String computeMD5(String text) {
        try {
            // 创建一个 MessageDigest 实例
            MessageDigest digest = MessageDigest.getInstance("MD5");

            // 计算输入的字节的 MD5 值
            byte[] hash = digest.digest(text.getBytes("UTF-8"));

            // 将 MD5 值转换为十六进制的字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
