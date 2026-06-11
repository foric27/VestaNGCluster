# ===== App-specific rules =====
-keep class ru.foric27.cluster.** { *; }
-keepclassmembers class ru.foric27.cluster.** { *; }

# ===== OkHttp =====
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ===== Apache FTPServer + Mina =====
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**
-dontwarn org.slf4j.**

# ===== BouncyCastle =====
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ===== Netty =====
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-dontwarn io.netty.handler.ssl.**
-dontwarn io.netty.handler.codec.**
-dontwarn io.netty.channel.kqueue.**
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.channel.unix.**

# ===== jose4j =====
-keep class org.jose4j.** { *; }
-dontwarn org.jose4j.**

# ===== JDOM =====
-keep class org.jdom2.** { *; }
-dontwarn org.jdom2.**

# ===== Apache HttpClient5 =====
-keep class org.apache.hc.** { *; }
-dontwarn org.apache.hc.**
-dontwarn org.apache.http.**

# ===== kotlinx.serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ===== Timber =====
-dontwarn org.jetbrains.annotations.**

# ===== General =====
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
