plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.docreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.docreader"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    
    // 16KB Page Size Compatibility Configuration
    packagingOptions {
        jniLibs {
            useLegacyPackaging = false
        }
        // Exclude problematic libraries that don't support 16KB alignment
        resources.excludes.addAll(listOf(
            "META-INF/services/javax.xml.stream.XMLEventFactory",
            "META-INF/DEPENDENCIES",
            "META-INF/NOTICE",
            "META-INF/LICENSE",
            "**/libc++_shared.so",
            "**/libjniPdfium.so",
            "**/libmodft2.so",
            "**/libmodpdfium.so",
            "**/libmodpng.so"
        ))
    }

    // Split APKs by architecture to drastically reduce download and install size
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // TalbotGooday PDF Viewer - supports onDraw/onLongPress for text selection
    implementation("com.github.TalbotGooday:AndroidPdfViewer:3.1.0-beta.3")

    // PdfBox-Android for native text extraction (no OCR needed)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Apache POI for legacy Office formats and rich DOCX support
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")
    implementation("org.apache.poi:poi-scratchpad:5.2.5")
    implementation("javax.xml.stream:stax-api:1.0-2")
    implementation("com.fasterxml.woodstox:woodstox-core:5.0.3")

    // High-Performance Database (Room)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}