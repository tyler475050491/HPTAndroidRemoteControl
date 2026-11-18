package com.hpt.adbtool;

/** 设备基础信息 */
public final class DevInfo {
    public String brand = "";
    public String model = "";
    public String androidVersion = "";
    public String sdk = "";
    public String resolution = "";
    public String address = "";
    public String name = "";

    public String line1() {
        return address;
    }

    public String line2() {
        StringBuilder sb = new StringBuilder();
        if (!androidVersion.isEmpty()) {
            sb.append("Android ").append(androidVersion);
            if (!sdk.isEmpty()) {
                sb.append("(API ").append(sdk).append(")");
            }
        }
        if (!resolution.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(resolution);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    public int width() {
        try {
            return Integer.parseInt(resolution.split("x")[0]);
        } catch (Exception e) {
            return 0;
        }
    }

    public int height() {
        try {
            return Integer.parseInt(resolution.split("x")[1]);
        } catch (Exception e) {
            return 0;
        }
    }
}
