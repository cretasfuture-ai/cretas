---
name: build-android-apk
description: 自动化构建 React Native Android APK。包括依赖检查、Debug/Release 构建、设备安装。使用此 Skill 来构建 APK、测试安装、或排查构建问题。
allowed-tools:
  - Bash
  - Read
---

# 构建 Android APK

## 环境要求

| 项目 | 路径 |
|------|------|
| JAVA_HOME | `C:/Program Files/Java/jdk-17` |
| ANDROID_HOME | `C:\Users\Steve\AppData\Local\Android\Sdk` |
| 项目目录 | `frontend/CretasFoodTrace/android` |
| 输出 APK | `android/app/build/outputs/apk/release/app-release.apk` (~98MB) |

---

## 快速构建 (Release)

```bash
# 1. 加 CMake 路径修复（Windows 路径 250 字符限制，见下文）。先确认 build.gradle 没有未提交改动，
#    否则第 5 步的还原会把那些改动一起抹掉
git status --short frontend/CretasFoodTrace/android/build.gradle   # 应为空
cat .claude/skills/build-android-apk/references/cmake-workaround.gradle >> frontend/CretasFoodTrace/android/build.gradle

# 2. 清理旧 CMake 缓存
rm -rf node_modules/react-native-reanimated/android/.cxx

# 3. 构建
ANDROID_HOME="C:\\Users\\Steve\\AppData\\Local\\Android\\Sdk" \
JAVA_HOME="C:/Program Files/Java/jdk-17" \
cmd //c "cd frontend\CretasFoodTrace\android && gradlew.bat assembleRelease"

# 4. 复制到项目根目录
cp frontend/CretasFoodTrace/android/app/build/outputs/apk/release/app-release.apk ./CretasFoodTrace.apk

# 5. 清理 CMake 临时目录，还原 build.gradle（第 1 步确认过它原本是干净的；git diff 应只剩这段补丁）
rm -rf C:/b
git diff --stat frontend/CretasFoodTrace/android/build.gradle
git restore frontend/CretasFoodTrace/android/build.gradle
```

## Debug 构建

```bash
ANDROID_HOME="C:\\Users\\Steve\\AppData\\Local\\Android\\Sdk" \
JAVA_HOME="C:/Program Files/Java/jdk-17" \
cmd //c "cd frontend\CretasFoodTrace\android && gradlew.bat assembleDebug"
```

---

## 关键注意事项

### CMake 路径修复

Windows 下 `react-native-reanimated` 的 CMake 对象路径超过 250 字符限制，要用 `buildStagingDirectory` 重定向到短路径 (`C:/b/`)。

- 补丁内容在 `references/cmake-workaround.gradle`，构建前追加、构建后还原，不提交。
- 还原前确认 `build.gradle` 原本没有未提交改动：还原会连同那些改动一起丢掉。
- **如果构建失败**: 先清理 `rm -rf node_modules/react-native-reanimated/android/.cxx`

### 其他注意事项

- Junction (`mklink /J`) 不可用 — Metro bundler 会解析回原始路径
- 构建耗时约 5-7 分钟
- 包名: `com.cretas.foodtrace`
- Hermes 引擎: 禁止使用 `toLocaleString`/`toLocaleDateString`/`toLocaleTimeString`，使用 `src/utils/formatters.ts`

---

## 参考文档

- `references/build-troubleshooting.md` - 构建问题排查
- `references/signing-guide.md` - 签名配置
