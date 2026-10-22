# InkEdit · 墨编

Android 原生、离线 EPUB 编辑器（早期开发版）。

## 当前功能
- 通过 Android 系统文件选择器打开 EPUB
- 编辑书名、作者、出版社、语言、简介
- 选择 JPEG / PNG 作为新封面
- 修改正文行距、首行缩进和段后间距
- 另存为 EPUB，不覆盖原文件

## 云端构建
打开 **Actions → Build Android APK → Run workflow**。成功后，在对应运行的 **Artifacts** 中下载 `InkEdit-debug-APK`。目前该构建仍需实际验证。仅供测试，尚不建议作为正式出版工具。

## 局限
- 当前仅支持未加密 EPUB
- 封面编辑是图片替换，不含裁切 UI
- CSS 注入采用基础模式，复杂样式/目录层级可能无法覆盖
- 尚无 EPUBCheck 校验和设备实测
- 每次保存请检查输出文件，并保留原始文件

## 技术
Kotlin、Jetpack Compose、Android Storage Access Framework、Java ZIP/XML API。
