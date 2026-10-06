# Car Piano (車機鋼琴)

一部**車機（Android 車載系統）**上用嘅音樂／提示音小工具：鋼琴鍵盤、錄音，同埋把聲音播去**車外喇叭**或者**車內喇叭**。

> ⚠️ **非官方 App，同任何車廠／車機供應商完全無關（unofficial, unaffiliated）。**
> 車外播放廣播／提示音，喺公共道路上可能受當地法規限制（例如模擬響號、倒車提示音）；
> 請只喺合法、安全嘅場合使用（私人地方、展示、測試）。詳見「安全與法規」。

---

## 功能

| Tab | 做啲咩 |
|---|---|
| 🎹 鋼琴 | 兩個八度琴鍵（可加減八度）、音量控制；琴音即時合成（6 諧波 × 指數衰減包絡），可播去車外喇叭 |
| 🎙 錄音 ＋ 咪位置測試 | 用車機麥克風錄音（WAV）、即時聲量錶、可揀輸入裝置（測「邊個咪＝邊個位置」）；錄音可播去 **🔔 車內通知通道** 或 **🚗 車外喇叭** |
| 🚗 開車自動 | 讀車機點火值：一著車自動開 app＋彈懸浮掣、熄車自動收埋（可停用） |
| 📌 懸浮掣 | 兩個圓掣（📯 HORN 嗶嗶 ／ 📯 倒車提示）浮喺其他 app 上；可自由拖放、記住位置 |

全部介面用**繁體中文（香港）**。

## 系統需求

- 需要嘅權限：
  - `RECORD_AUDIO` — 錄音（唔錄音可以唔授權）
  - `SYSTEM_ALERT_WINDOW` — 懸浮掣（唔用懸浮掣可以唔授權）
  - `FOREGROUND_SERVICE` / `RECEIVE_BOOT_COMPLETED` — 「開車自動」前台服務

## 更新
- 開 app 會自動查一次新版；有新版本會喺畫面頂顯示提示 → 撳「下載並更新」即刻下載＋交系統安裝器。
- 亦可以喺「設定」tab 撳「檢查更新」。
- 下載會核對 SHA-256 雜湊，對唔上就中止（唔會裝錯檔）。
- ⚠️ Android 唔容許第三方 app 靜默安裝 APK —— 系統一定會彈確認。車機（App Lab 容器）彈出安裝器之後
  **tap「返回」，唔好 tap「打開」**（tap 打開會即刻開 app，睇落好似冇裝到）。

## 私隱

**完全離線。** 冇網絡請求、冇帳號、冇分析 SDK、冇任何上傳。
麥克風錄音同診斷記錄只存在 app 私人目錄，用戶可以自己刪。詳見 [PRIVACY.md](PRIVACY.md)。

### 音效／圖示素材

- `tools/make_horn.py` — 用純 Python 生成「嗶嗶」喇叭聲（400 + 505 Hz 雙音、兩短響）
- `tools/make_icon.py` — 用 Pillow 生成 🎹 app icon（5 個密度 + 圓形版）
- `app/src/main/assets/reverse.wav` — 倒車提示語音（「倒車，倒車，請小心」）
  ⚠️ **商用前請換走**：呢個檔案係用第三方 TTS 引擎生成，條款未必容許商業再分发。
  建議自己錄一段（或者請配音員），用 `ffmpeg -ar 44100 -ac 1 -c:a pcm_s16le` 轉做 WAV 放入 `assets/reverse.wav`。

## 點解要用「指定裝置」播聲

車機嘅每條音頻 BUS 背後係唔同 DSP／功放路徑：

- **唔指定裝置**（靠系統預設）→ 喺部分車機容器環境**完全冇聲**
- 要出聲就要 `AudioTrack.setPreferredDevice(device)`（或者 `MediaPlayer`）指定一條 BUS
- 而且要**配對應嘅 usage／音量流**：通知通道 → `USAGE_NOTIFICATION` ＋ 音量跟「通知音量」；媒體 → `USAGE_MEDIA` ＋ 媒體音量

## 已知限制

- 唔同車型／車機版本嘅 BUS 名稱同車控 function ID 可能唔同；`VehicleRead.java` 係 best-effort（讀唔到會回報「讀唔到」，唔會亂估）
- 部分車機容器**唔派** `BOOT_COMPLETED`，所以「開車自動」要靠前台服務常駐；車機完全重啟後要開一次 app
- 懸浮掣需要系統「懸浮窗」權限；部分車機設定頁冇呢一項

## 專案結構

```
app/src/main/java/com/carpiano/app/
  MainActivity.java    三個 tab 嘅主介面＋崩潰記錄
  PianoTab.java        🎹 鋼琴鍵盤
  RecordTab.java       🎙 錄音＋咪測試＋播放路線
  FloatService.java    📌 懸浮掣（overlay window）
  AutoService.java     🚗 開車自動（前台服務）
  BootReceiver.java    開機廣播（有啲車機唔派）
  AudioOut.java        音頻裝置／音量／琴音合成
  Pcm.java             WAV(PCM) 播放（AudioTrack + 指定裝置）
  VehicleRead.java     只讀車控信號（反射）
  Diag.java            本機診斷記錄（唔聯網）
```

## 安全與法規（請務必睇）

1. **唔好用車外喇叭模擬真正嘅響號／警報**去要求其他道路使用者讓路 —— 可能違反交通法規。
2. **倒車提示音**唔應該喺唔倒車嘅時候播（會誤導其他人）。
3. 喺公共道路使用前，請先了解當地對「車輛外部發聲裝置」嘅規定。
4. 車外播聲時請注意音量，唔好影響其他人。
5. 呢個 app 唔會、亦唔應該干擾車輛嘅安全系統。

## 授權

本專案以 [MIT License](LICENSE) 發佈（可自行改為其他授權）。
呢個 app 係獨立開發，**冇**使用或包含任何車廠嘅專有程式碼；
`AudioOut.java` / `VehicleRead.java` 入面嘅裝置名稱同信號 ID 係由公開可觀察嘅系統行為得出。

`assets/reverse.wav` 嘅語音內容授權見上（商用前請換走）。
