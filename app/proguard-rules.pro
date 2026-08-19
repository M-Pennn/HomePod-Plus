# Keep BouncyCastle JCA provider intact (algorithms are registered by name).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**