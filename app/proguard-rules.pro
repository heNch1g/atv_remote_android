# Pairing keys and session traffic go through BouncyCastle's lightweight API,
# which is referenced by name rather than through the JCA provider registry.
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-keep class org.bouncycastle.util.** { *; }
-dontwarn org.bouncycastle.**

# Keep line numbers so crashes can still be matched to sources.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
