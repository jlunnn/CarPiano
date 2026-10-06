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
    namespace = "com.carpiano.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.carpiano.app"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
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
            applicationIdSuffix = ".debug"
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
    // 冇第三方依賴：全部用 Android framework（保持細、透明、易審核）
}
