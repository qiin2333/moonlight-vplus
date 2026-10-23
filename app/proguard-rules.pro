# Don't obfuscate code
-dontobfuscate

# Our code
-keep class com.limelight.binding.input.evdev.* {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Dedicated MAT JNI callback names/signatures; separate from AC3/PCM callbacks.
-keep class com.limelight.binding.audio.MatNativeReceiver { *; }
-keepclassmembers class * implements com.limelight.binding.audio.MatCarrierConsumer {
    public boolean offerMatCycle(byte[], int, long, long, long, long);
    public boolean endMatStream(long);
}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# Shizuku starts this service by class name in another process.
-keep class com.limelight.services.ShellService { *; }
-keep class com.limelight.services.IKeyboardAccessibilityShell { *; }
-keep class com.limelight.services.IKeyboardAccessibilityShell$Stub { *; }
-keep class com.limelight.services.IKeyboardAccessibilityShell$Stub$Proxy { *; }
