# JSch instancia sus algoritmos (cifrados, kex, firmas) por nombre de clase.
-keep class com.jcraft.jsch.** { *; }
# Dependencias opcionales de JSch que no se incluyen.
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.**
