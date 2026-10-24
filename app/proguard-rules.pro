-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Eclipse Paho MQTT: loads its network modules via ServiceLoader and its logger/resource bundles by class name.
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-keepnames class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**

# WebRTC (2.0 calls): JNI calls back into these classes by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
