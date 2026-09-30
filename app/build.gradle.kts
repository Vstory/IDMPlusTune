plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.vstory.hook.idm.plus"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.vstory.hook.idm.plus"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.5"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // libxposed 本地 jar（api102）：api=编译期 + interface/service=运行期
    compileOnly(files("libs/libxposed/api.jar"))
    implementation(files("libs/libxposed/interface.jar"))
    implementation(files("libs/libxposed/service.jar"))
}
