# EZMJ 台灣十六張麻將

以 Java Swing 製作的台灣十六張麻將。需要 JDK 17 以上才能建置。

## 建置與打包

```
powershell -ExecutionPolicy Bypass -File build.ps1
```

腳本會編譯、執行全部測試（失敗就中止），再把成品放到 `dist\`：

| 檔案 | 說明 |
|---|---|
| `ezmj-<版號>.jar` | 可執行 jar，需要 Java 17 以上：`java -jar ezmj-<版號>.jar` |
| `EZMJ-<版號>-windows-portable.zip` | 免安裝版，解壓後執行 `EZMJ.exe`，不需要另外裝 Java |
| `EZMJ-<版號>-setup.exe` | 安裝程式，需要 WiX 3 才會產生 |

版號只改 `src/ezmj/version.properties` 一處。

要產生安裝程式時，把 [WiX v3.14.1](https://github.com/wixtoolset/wix3/releases/tag/wix3141rtm) 的 `wix314-binaries.zip` 解壓到 `tools\wix314\`（不進版控），或把 WiX 3 加到 PATH。

## 測試

測試不依賴框架，直接執行 `test/ezmj/AllTests.java` 的 `main` 即可。

## 程式結構

| 套件 | 職責 |
|---|---|
| `ezmj.model` | 牌、牌牆、面子、玩家、設定 |
| `ezmj.rules` | 胡牌判定、計台 |
| `ezmj.engine` | 單局流程、整場牌局與結算（不依賴 Swing） |
| `ezmj.bot` | 電腦玩家介面（目前只有無策略的佔位實作） |
| `ezmj.ui` | Swing 畫面與流程控制 |
