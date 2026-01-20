// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
}

// ==============================================================================
// Maven Central Portal API 发布任务
// 参考文档: https://central.sonatype.org/publish/publish-portal-api/
// ==============================================================================

tasks.register("publishToMavenCentral") {
    group = "publishing"
    description = "发布库到Maven Central (使用Portal API)"
    dependsOn(":library:publishToMavenLocal")

    doLast {
        println("开始发布到Maven Central...")
        println("请运行以下任务:")
        println("  - createCentralBundle: 创建部署包")
        println("  - uploadToCentral: 上传部署包到Portal")
        println("  - checkCentralStatus: 检查部署状态")
        println("  - publishCentralDeployment: 发布部署")
    }
}

// 从gradle.properties读取配置
val apiBaseUrl: String = project.findProperty("mavenCentral.apiBaseUrl") as String?
    ?: "https://central.sonatype.com/api/v1/publisher"
val userToken: String? = project.findProperty("mavenCentral.userToken") as String?
val username: String? = project.findProperty("mavenCentral.username") as String?
val password: String? = project.findProperty("mavenCentral.password") as String?
val publishingType: String = project.findProperty("mavenCentral.publishingType") as String? ?: "USER_MANAGED"
val deploymentName: String? = project.findProperty("mavenCentral.deploymentName") as String?

// 计算授权头
fun getAuthHeader(): String {
    val token = when {
        !userToken.isNullOrEmpty() -> userToken
        !username.isNullOrEmpty() && !password.isNullOrEmpty() -> {
            val credentials = "$username:$password"
            java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
        }
        else -> throw IllegalStateException(
            "必须在gradle.properties中配置mavenCentral.userToken或mavenCentral.username/password"
        )
    }
    return "Bearer $token"
}

// 任务: 准备Central部署包文件（包括生成校验和）
tasks.register("prepareCentralBundle") {
    group = "publishing"
    description = "准备Maven Central Portal部署包文件（生成校验和）"

    dependsOn(":library:publishToMavenLocal")

    val groupId: String = project.findProperty("mavenCentral.groupId") as String? ?: "io.github.jt-plug"
    val artifactId: String = project.findProperty("mavenCentral.artifactId") as String? ?: "joytalk"
    val version: String = project.findProperty("mavenCentral.version") as String? ?: "1.0.0"

    val mavenLocalRepo = File(System.getProperty("user.home"), ".m2/repository")
    val artifactPath = "${groupId.replace('.', '/')}/$artifactId/$version"
    val artifactDir = File(mavenLocalRepo, artifactPath)
    val tempDir = layout.buildDirectory.dir("central-bundle-temp").get().asFile

    doLast {
        if (!artifactDir.exists()) {
            throw IllegalStateException("本地Maven仓库中未找到构件: $artifactPath\n请先运行: ./gradlew :library:publishToMavenLocal")
        }

        tempDir.mkdirs()

        // 只处理当前版本的文件（文件名必须包含当前版本号）
        val baseFileName = "$artifactId-$version"

        // 先清理临时目录，确保没有旧文件
        tempDir.listFiles()?.forEach { it.delete() }

        artifactDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val fileName = file.name

                // 严格匹配：文件名必须以artifactId-version开头，或者精确匹配module文件
                val isCurrentVersion = fileName.startsWith(baseFileName) ||
                        fileName == "$artifactId-$version.module" ||
                        fileName == "$artifactId-$version.module.asc"

                // 排除旧版本文件（包含其他版本号的文件）
                val isOldVersion = fileName.contains("-$artifactId-") &&
                        !fileName.startsWith(baseFileName) &&
                        fileName != "$artifactId-$version.module" &&
                        fileName != "$artifactId-$version.module.asc"

                if (isCurrentVersion && !isOldVersion) {
                    if (!fileName.endsWith(".md5") && !fileName.endsWith(".sha1") && !fileName.endsWith(".asc")) {
                        val targetFile = File(tempDir, fileName)
                        val fileBytes = file.readBytes()
                        targetFile.writeBytes(fileBytes)

                        // 生成MD5校验和
                        val md5 = java.security.MessageDigest.getInstance("MD5")
                        val md5Hash = md5.digest(fileBytes).joinToString("") { "%02x".format(it) }
                        File(tempDir, "$fileName.md5").writeText(md5Hash)

                        // 生成SHA1校验和
                        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
                        val sha1Hash = sha1.digest(fileBytes).joinToString("") { "%02x".format(it) }
                        File(tempDir, "$fileName.sha1").writeText(sha1Hash)
                    } else if (fileName.endsWith(".asc")) {
                        // 只复制当前版本的签名文件
                        val targetFile = File(tempDir, fileName)
                        file.copyTo(targetFile, overwrite = true)
                    }
                }
            }
        }

        // 验证必需文件是否存在
        val requiredFiles = listOf(
            "$artifactId-$version.pom",
            "$artifactId-$version.aar",
            "$artifactId-$version.module"
        )

        val missingFiles = requiredFiles.filter { !File(tempDir, it).exists() }
        if (missingFiles.isNotEmpty()) {
            throw IllegalStateException("缺少必需文件: ${missingFiles.joinToString(", ")}\n请确保已运行: ./gradlew :library:publishToMavenLocal")
        }

        println("已准备部署包文件，包含校验和（仅当前版本: $version）")
    }
}

// 任务: 创建Central部署包
tasks.register<Zip>("createCentralBundle") {
    group = "publishing"
    description = "创建Maven Central Portal部署包"

    dependsOn("prepareCentralBundle")

    val groupId: String = project.findProperty("mavenCentral.groupId") as String? ?: "io.github.jt-plug"
    val artifactId: String = project.findProperty("mavenCentral.artifactId") as String? ?: "joytalk"
    val version: String = project.findProperty("mavenCentral.version") as String? ?: "1.0.0"
    val artifactPath = "${groupId.replace('.', '/')}/$artifactId/$version"
    val tempDir = layout.buildDirectory.dir("central-bundle-temp").get().asFile

    // 包含所有文件（包括生成的校验和）
    from(tempDir) {
        include("**/*")
        // 保持Maven仓库的目录结构
        into(artifactPath)
    }

    archiveBaseName.set("central-bundle")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("central-bundle"))

    doLast {
        val bundleFile = archiveFile.get().asFile
        println("=".repeat(60))
        println("部署包已创建: ${bundleFile.absolutePath}")
        println("文件大小: ${bundleFile.length() / 1024} KB")
        println("Maven路径: $artifactPath")
        println("=".repeat(60))
    }
}

// 任务: 上传部署包到Central Portal
tasks.register("uploadToCentral") {
    group = "publishing"
    description = "上传部署包到Maven Central Portal"

    dependsOn("createCentralBundle")

    doLast {
        val bundleFile = tasks.named<Zip>("createCentralBundle").get().archiveFile.get().asFile
        if (!bundleFile.exists()) {
            throw IllegalStateException("部署包不存在: ${bundleFile.absolutePath}")
        }

        val uploadUrl = "$apiBaseUrl/upload"
        val authHeader = getAuthHeader()

        println("正在上传部署包到: $uploadUrl")
        println("部署包路径: ${bundleFile.absolutePath}")

        val command = mutableListOf(
            "curl",
            "--request", "POST",
            "--header", "Authorization: $authHeader",
            "--form", "bundle=@${bundleFile.absolutePath}",
            "--form", "publishingType=$publishingType"
        )

        if (deploymentName != null) {
            command.add("--form")
            command.add("name=$deploymentName")
        }

        command.add(uploadUrl)

        val process = ProcessBuilder(command).start()

        val response = process.inputStream.bufferedReader().readText()
        val errorResponse = process.errorStream.bufferedReader().readText()

        process.waitFor()

        if (process.exitValue() != 0) {
            throw IllegalStateException("上传失败: $errorResponse")
        }

        val deploymentId = response.trim()
        println("部署ID: $deploymentId")

        // 保存部署ID到文件，供后续任务使用
        val deploymentIdFile = layout.buildDirectory.file("central-bundle/deployment-id.txt").get().asFile
        deploymentIdFile.parentFile.mkdirs()
        deploymentIdFile.writeText(deploymentId)

        println("部署ID已保存到: ${deploymentIdFile.absolutePath}")
        println("使用以下命令检查状态:")
        println("  ./gradlew checkCentralStatus")
        println("或使用部署ID:")
        println("  ./gradlew checkCentralStatus -PdeploymentId=$deploymentId")
    }
}

// 任务: 检查部署状态
tasks.register("checkCentralStatus") {
    group = "publishing"
    description = "检查Maven Central Portal部署状态"

    doLast {
        val deploymentIdFile = layout.buildDirectory.file("central-bundle/deployment-id.txt").get().asFile
        val deploymentId = if (project.hasProperty("deploymentId")) {
            project.property("deploymentId") as String
        } else if (deploymentIdFile.exists()) {
            deploymentIdFile.readText().trim()
        } else {
            throw IllegalStateException(
                "未找到部署ID。请先运行uploadToCentral任务，或使用-PdeploymentId=<id>参数"
            )
        }

        val statusUrl = "$apiBaseUrl/status?id=$deploymentId"
        val authHeader = getAuthHeader()

        println("正在检查部署状态: $deploymentId")

        val process = ProcessBuilder(
            "curl",
            "--request", "POST",
            "--header", "Authorization: $authHeader",
            "--header", "Accept: application/json",
            statusUrl
        ).start()

        val response = process.inputStream.bufferedReader().readText()
        val errorResponse = process.errorStream.bufferedReader().readText()

        process.waitFor()

        if (process.exitValue() != 0) {
            throw IllegalStateException("检查状态失败: $errorResponse")
        }

        println("部署状态:")
        println(response)

        // 尝试解析JSON (如果jq可用)
        try {
            val jsonProcess = ProcessBuilder("echo", response).start()
            val jqProcess = ProcessBuilder("jq", ".").start()
            jsonProcess.inputStream.transferTo(jqProcess.outputStream)
            jsonProcess.waitFor()
            jqProcess.waitFor()
            val formatted = jqProcess.inputStream.bufferedReader().readText()
            if (formatted.isNotEmpty()) {
                println("\n格式化后的状态:")
                println(formatted)
            }
        } catch (e: Exception) {
            // jq不可用，忽略
        }
    }
}

// 任务: 发布部署
tasks.register("publishCentralDeployment") {
    group = "publishing"
    description = "发布已验证的部署到Maven Central"

    doLast {
        val deploymentIdFile = layout.buildDirectory.file("central-bundle/deployment-id.txt").get().asFile
        val deploymentId = if (project.hasProperty("deploymentId")) {
            project.property("deploymentId") as String
        } else if (deploymentIdFile.exists()) {
            deploymentIdFile.readText().trim()
        } else {
            throw IllegalStateException(
                "未找到部署ID。请先运行uploadToCentral任务，或使用-PdeploymentId=<id>参数"
            )
        }

        val publishUrl = "$apiBaseUrl/deployment/$deploymentId"
        val authHeader = getAuthHeader()

        println("正在发布部署: $deploymentId")

        val process = ProcessBuilder(
            "curl",
            "--request", "POST",
            "--header", "Authorization: $authHeader",
            "--verbose",
            publishUrl
        ).start()

        val response = process.inputStream.bufferedReader().readText()
        val errorResponse = process.errorStream.bufferedReader().readText()

        process.waitFor()

        if (process.exitValue() != 0) {
            throw IllegalStateException("发布失败: $errorResponse")
        }

        if (response.isNotEmpty()) {
            println("响应: $response")
        }
        println("发布成功!")
        println("部署正在发布到Maven Central，请稍后使用checkCentralStatus检查状态")
    }
}

// 任务: 删除部署
tasks.register("dropCentralDeployment") {
    group = "publishing"
    description = "删除Maven Central Portal中的部署"

    doLast {
        val deploymentIdFile = layout.buildDirectory.file("central-bundle/deployment-id.txt").get().asFile
        val deploymentId = if (project.hasProperty("deploymentId")) {
            project.property("deploymentId") as String
        } else if (deploymentIdFile.exists()) {
            deploymentIdFile.readText().trim()
        } else {
            throw IllegalStateException(
                "未找到部署ID。请先运行uploadToCentral任务，或使用-PdeploymentId=<id>参数"
            )
        }

        val deleteUrl = "$apiBaseUrl/deployment/$deploymentId"
        val authHeader = getAuthHeader()

        println("正在删除部署: $deploymentId")

        val process = ProcessBuilder(
            "curl",
            "--request", "DELETE",
            "--header", "Authorization: $authHeader",
            "--verbose",
            deleteUrl
        ).start()

        val response = process.inputStream.bufferedReader().readText()
        val errorResponse = process.errorStream.bufferedReader().readText()

        process.waitFor()

        if (process.exitValue() != 0) {
            throw IllegalStateException("删除失败: $errorResponse")
        }

        if (response.isNotEmpty()) {
            println("响应: $response")
        }
        println("删除成功!")
    }
}