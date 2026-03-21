# Сохраняем точки входа из manifest.
-keep class ru.foric27.cluster.MainActivity { *; }
-keep class ru.foric27.cluster.DeveloperActivity { *; }
-keep class ru.foric27.cluster.ClusterLaunchProxyActivity { *; }
-keep class ru.foric27.cluster.UdpStreamService { *; }
-keep class ru.foric27.cluster.BootReceiver { *; }
-keep class ru.foric27.cluster.ClusterFocusRequestReceiver { *; }
-keep class ru.foric27.cluster.AppRecoveryReceiver { *; }

# Не зашумляем лог предупреждениями от Android view callback-ов.
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}

# Apache FtpServer / SLF4J могут ссылаться на optional binder-классы.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.impl.StaticMDCBinder

# Apache MINA использует reflection-sensitive путь для NIO processor при старте FTP listener.
-keep class org.apache.mina.transport.socket.nio.NioProcessor { *; }
