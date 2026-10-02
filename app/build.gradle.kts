plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "top.lvbyte.powerfee"
    compileSdk = 34

    defaultConfig {
        applicationId = "top.lvbyte.powerfee"
        minSdk = 24
        targetSdk = 34
        // 对外发版时两处都要改：versionCode 每次 +1，versionName 与 tag 同名（如 v1.1）。
        // Android 靠 versionCode 判断是不是新版本，忘了加会出现"装完还是旧版"的错觉。
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("zh", "en")
    }

    // 签名材料全部来自环境变量（CI 里由 GitHub Secrets 注入），仓库里不留任何密钥
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 没有签名材料时构建出的 release 包无法安装，所以只有拿到密钥才套用签名配置
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 刻意只依赖 WorkManager：其余全部用平台自带 API（HttpURLConnection / org.json / 平台控件），
    // 与托盘程序"无第三方库"的风格保持一致，APK 体积也小。
    implementation("androidx.work:work-runtime:2.9.1")
}
