# R8 keep 規則（車機 app 專用）
# 為什麼要 keep 自己成個 package：車控信號係用反射讀 OEM 類別、ApkProvider 由系統按名載入，
# R8 改名／移除都會靜靜壞掉。libs（androidx/material）就照縮 —— 省落嚟嘅體積主要嚟自佢哋。

-keep class com.carpiano.** { *; }

# 由系統按名載入嘅組件（manifest 有列出，明寫一次穩陣）
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider

# 反射／annotations 用
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# OEM 車控類別唔喺 APK 內（只有反射引用）→ 唔好當錯
-dontwarn **
-dontnote **
