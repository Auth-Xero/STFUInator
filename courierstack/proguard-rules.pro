# CourierStack ProGuard Rules
-keepclassmembers class * implements com.courierstack.hci.HciHalCallback {
    public void onPacket(com.courierstack.hci.HciPacketType, byte[]);
}

-keep class com.courierstack.** { *; }
