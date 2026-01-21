#!/bin/bash
# Maven Central 上传脚本 - 上传到Portal

set -e

# 设置环境变量（确保能找到gpg命令）
export PATH="/opt/homebrew/bin:$PATH"
export GPG_TTY=$(tty)

echo "🚀 开始上传到Maven Central Portal..."
echo ""

# 0. 停止Gradle Daemon（重要：确保使用最新的JVM参数和配置）
# Gradle Daemon会缓存JVM参数和环境变量，修改gradle.properties后必须停止Daemon
echo "🛑 停止Gradle Daemon以确保使用最新配置..."
if ./gradlew --stop > /dev/null 2>&1; then
    echo "✅ Gradle Daemon已停止"
else
    echo "ℹ️  没有运行中的Gradle Daemon"
fi
echo ""

# 1. 确保GPG密码已缓存
echo "🔐 配置GPG签名..."
GPG_KEY_ID="F60455A7"
GPG_PASSWORD="T@feeling1211"

# 确保gpg-agent正在运行
if ! pgrep -x "gpg-agent" > /dev/null; then
    echo "启动gpg-agent..."
    gpg-agent --daemon > /dev/null 2>&1 || true
    sleep 1
fi

# 缓存GPG密码到gpg-agent（通过执行一次签名操作）
echo "正在缓存GPG密码..."
TEST_FILE="/tmp/gpg_test_$$.txt"
echo "test" > "$TEST_FILE"
echo "$GPG_PASSWORD" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 --default-key "$GPG_KEY_ID" --sign "$TEST_FILE" > /dev/null 2>&1

if [ $? -eq 0 ]; then
    echo "✅ GPG密码已缓存"
    rm -f "$TEST_FILE" "${TEST_FILE}.gpg" 2>/dev/null
else
    echo "⚠️  GPG密码缓存失败"
    echo "   请检查GPG密钥ID和密码是否正确"
    rm -f "$TEST_FILE" "${TEST_FILE}.gpg" 2>/dev/null
    exit 1
fi

echo ""

# 2. 创建并上传部署包
# 注意：createCentralBundle会自动执行prepareCentralBundle和publishToMavenLocal（依赖关系）
echo "📦 创建部署包并上传..."
if ./gradlew createCentralBundle uploadToCentral; then
    echo "✅ 部署包创建和上传成功"
else
    echo "❌ 部署包创建或上传失败"
    exit 1
fi

# 3. 获取部署ID
DEPLOYMENT_ID=$(cat build/central-bundle/deployment-id.txt 2>/dev/null || echo "")
if [ -z "$DEPLOYMENT_ID" ]; then
    echo "❌ 未找到部署ID，请检查上传是否成功"
    exit 1
fi

echo ""
echo "✅ 部署包已成功上传到Portal！"
echo ""
echo "📋 部署ID: $DEPLOYMENT_ID"
echo "🌐 Portal链接: https://central.sonatype.com/publishing/deployments"
echo ""
echo "📝 下一步："
echo "   1. 访问Portal查看部署状态和验证结果"
PUBLISHING_TYPE=$(grep "^mavenCentral.publishingType" gradle.properties 2>/dev/null | cut -d'=' -f2 | tr -d ' ' || echo "AUTOMATIC")
if [ "$PUBLISHING_TYPE" = "AUTOMATIC" ]; then
    echo "   2. 验证通过后将自动发布（AUTOMATIC模式）"
    echo "   3. 可以使用以下命令检查状态："
    echo "      ./gradlew checkCentralStatus"
else
    echo "   2. 验证通过后，在Portal UI中手动发布部署"
fi
echo ""

