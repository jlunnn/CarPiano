import java.util.Properties

plugins {
    id("com.android.application")
}

// 發佈簽名：如果 repo 根目錄有 keystore.properties（唔會入 git），就用佢；
// 冇嘅話 fallback 用 debug 簽名（只適合自己試裝，唔可以上架）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.carpiano"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carpiano"
        minSdk = 28
        targetSdk = 28
        versionCode = 23
        versionName = "2.1"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false          // 保持簡單：反射讀車控信號，唔想 R8 有機會改壞
            isShrinkResources = false
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // applicationIdSuffix = ".debug"   // ⛔ 唔要：package 要正好 com.carpiano（App Lab 認 package 名）
            isMinifyEnabled = true          // R8：縮 dex（12.4MB → 目標 ~4MB）
            isShrinkResources = true        // 移除未用資源（Material 全量資源係主要肥源）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // A＋B 級 UI 升級：Material 3（Views 版）＋ AppCompat —— 保留車機深色＋藍 accent 色板
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
