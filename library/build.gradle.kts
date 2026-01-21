plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
    signing
}

android {
    namespace = "com.plug.library"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // OkHttp
    implementation(libs.okhttp)

    // Gson
    implementation(libs.gson)

    // Timber Log
    implementation(libs.timber)

    // 引用 libs 目录下的 jar 包
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}

// 从gradle.properties读取配置
val groupId: String = project.findProperty("mavenCentral.groupId") as String? ?: "io.github.jt-plug"
val artifactId: String = project.findProperty("mavenCentral.artifactId") as String? ?: "joytalk"
val versionName: String = project.findProperty("mavenCentral.version") as String? ?: "1.0.0"
val projectName: String = project.findProperty("mavenCentral.name") as String? ?: "JoyTalk Library"
val projectDescription: String = project.findProperty("mavenCentral.description") as String? ?: "A library for the JoyTalk project"
val projectUrl: String = project.findProperty("mavenCentral.url") as String? ?: ""
val licenseName: String = project.findProperty("mavenCentral.license.name") as String? ?: "Apache-2.0"
val licenseUrl: String = project.findProperty("mavenCentral.license.url") as String? ?: "https://www.apache.org/licenses/LICENSE-2.0.txt"
val scmUrl: String = project.findProperty("mavenCentral.scm.url") as String? ?: projectUrl
val scmConnection: String? = project.findProperty("mavenCentral.scm.connection") as String?
val scmDeveloperConnection: String? = project.findProperty("mavenCentral.scm.developerConnection") as String?
val developerId: String = project.findProperty("mavenCentral.developer.id") as String? ?: ""
val developerName: String = project.findProperty("mavenCentral.developer.name") as String? ?: ""
val developerEmail: String = project.findProperty("mavenCentral.developer.email") as String? ?: ""

// GPG签名配置
val signingKeyId: String? = project.findProperty("mavenCentral.signing.keyId") as String?

// GPG签名配置
// 注意：发布到Maven Central需要GPG签名
// 使用gpg命令进行签名，密码通过gpg-agent缓存
// 在运行构建前，请确保：
//   1. GPG密钥已导入到系统密钥环中（~/.gnupg/）
//   2. 设置环境变量：export GPG_TTY=$(tty)（用于交互式输入密码）
//   3. 或者使用gpg-agent缓存密码（推荐）
val signingEnabled = project.findProperty("mavenCentral.signing.enabled") as String? != "false"
if (signingKeyId != null && signingEnabled) {
    signing {
        // 使用系统GPG命令（推荐方式）
        // GPG会自动使用默认密钥环位置（~/.gnupg/）和处理密码（通过gpg-agent）
        // 如果gpg不在PATH中，需要在运行前设置PATH环境变量，例如：
        // export PATH="/opt/homebrew/bin:$PATH"
        useGpgCmd()
        sign(publishing.publications)
    }
} else if (!signingEnabled) {
    logger.warn("GPG签名已禁用。发布到Maven Central需要签名，请设置mavenCentral.signing.enabled=true")
} else {
    logger.warn("GPG签名未配置。发布到Maven Central需要签名，请在gradle.properties中设置mavenCentral.signing.keyId")
}

// Maven发布配置
mavenPublishing {
    coordinates(
        groupId = groupId,
        artifactId = artifactId,
        version = versionName
    )

    pom {
        name.set(projectName)
        description.set(projectDescription)
        url.set(projectUrl)

        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
                distribution.set("repo")
            }
        }

        // 添加开发者信息（Maven Central验证要求）
        developers {
            developer {
                id.set(if (developerId.isNotEmpty()) developerId else "jt-plug")
                name.set(if (developerName.isNotEmpty()) developerName else "jt-plug")
                email.set(if (developerEmail.isNotEmpty()) developerEmail else "zhengyaoting1211@gmail.com")
            }
        }

        if (scmUrl.isNotEmpty()) {
            scm {
                url.set(scmUrl)
                // 优先使用gradle.properties中定义的连接配置
                connection.set(scmConnection ?: "scm:git:${scmUrl.replace("https://", "git://").replace("http://", "git://")}.git")
                developerConnection.set(scmDeveloperConnection ?: "scm:git:${scmUrl.replace("https://", "ssh://git@").replace("http://", "ssh://git@")}.git")
            }
        }
    }
}