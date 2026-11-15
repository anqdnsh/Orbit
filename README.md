# OrbitSearch

A Raycast / Alfred-style launcher for Galaxy Tab (Tab S9 Ultra and newer, Android 13+), built for the Book Cover Keyboard. It's frosted glass in light or charcoal dark with a red accent, it has no libraries, and it opens instantly. A welcome guide walks you through setup on first launch.

## What Phase 1 does

- **Hotkey from any app:** Alt + Space (or Ctrl + Alt + Space / Meta + Space) shows the panel as an overlay. This needs the accessibility switch, see Setup.
- **Search:** apps (including a work profile), 24 system settings pages, a calculator answer as a result row (`128*1.06`, `1,250 x 3`, `2^10`; Enter copies it, or type "calculator" for the Calculator app), and a web search fallback.
- **Enter opens the first result.** The top hit is always highlighted. The arrow keys move the highlight.
- **More keys:** Ctrl + Enter opens a pop-up window, Shift + Enter opens side by side, Ctrl + 1–8 opens that result directly, Ctrl + Tab switches filters, Ctrl + C copies a calculator answer, Esc closes.
- **It learns:** things you open often or recently rank higher. That data stays on the tablet.
- **Light / Dark / Match system**, set in OrbitSearch settings (search "orbit" or press Ctrl + ,).
- **Liquid glass:** uses Android's real cross-window blur. When One UI turns blur off (for example in battery saver), it switches to a frosted tint.
- **Other ways in:** the app icon, a Quick Settings tile, and Android's own per-app keyboard shortcut if your One UI version offers it.

## Laptop tools

- **Area screenshot:** press **Alt + Shift + S** anywhere (or type "snip"). The screen freezes, you drag a box, and the crop is saved to Pictures/Screenshots/OrbitSearch and copied. Enter takes the whole screen. Apps that block screenshots (e.g. banking) come out black.
- **File search (Spotlight-style):** type a file name, or use the **Files** filter. Press **↓ then Space** (or **Shift + Space**) to preview images, videos, PDFs (first page) and text, and ↓ ↑ to flip through files. It needs Android's "All files access" and searches the system's own file index, so nothing scans in the background.
- **Focus timer:** `focus 25`, `focus 50`, `timer 10`, `timer 1h`. A glass card with a smooth 120 Hz ring and rolling digits. Space pauses, + adds 5 min, and Esc (or tapping outside) shrinks it to a small ring bubble in the bottom-right corner; tap the bubble to open it again. It leaves Do Not Disturb alone. It chimes when done, even if the tablet was asleep.
- **Keep awake:** `awake 30`, `awake 1h`, "keep awake until I turn it off", "keep awake off". It uses an invisible keep-screen-on window, with no wake locks.
- **Currency:** `100 usd to myr`, `rm50 in sgd`, `250 eur`. Rates come from Frankfurter (central-bank reference rates, no key), with ExchangeRate-API as a backup. They're fetched only when you convert, then cached for 12 hours.
- **Ctrl + /** shows every shortcut inside OrbitSearch.

## Speed and resources

- The hotkey service keeps a panel built and reuses it, so opening is just showing a ready window. It's prepared while the tablet is idle.
- Search scores everything first and only builds the rows that will show. File search is debounced and runs off the main thread.
- No background work: no polling, no timers, no wake locks. The 120 Hz request only applies while OrbitSearch is on screen.
- When Android asks for memory back, the spare panel and icon cache are dropped.
- **Reduce effects** (settings) turns off the blur. **Performance** (settings) shows real open time, search time and memory.

## Quick AI

Type anything and press **Tab**. The answer streams into the panel. Enter or Tab asks a follow-up, Ctrl + C copies the answer, Ctrl + N starts a new question, and Esc goes back to search. Typing `ask …` and pressing Enter does the same thing.

Set it up in OrbitSearch settings › Quick AI. You choose Claude, ChatGPT, Gemini, OpenRouter or a custom OpenAI-compatible server (for example Ollama on your PC), then paste your API key. The key is encrypted with the Android Keystore and only sent to that provider. Model names are editable because providers rename them often.

## Device tools

- **Device health** ("health", "ram", "storage", "battery"): RAM used, free storage, battery level and temperature, charging, and time since the last restart. It suggests a restart after 3 days. Enter opens Device care.
- **Clean RAM / Optimise now:** open Samsung's Battery and device care (Memory › Clean now stops background apps). Since Android 14, apps can't close other apps themselves.
- **Free up storage:** Android's free-up-space screen.
- **Quit all apps** ("close all", "clear recents"): opens Recent apps, then you tap Close all.
- **DeX:** shows Enter DeX or Exit DeX and opens Quick Settings with the DeX tile ready. No app can switch DeX directly. In DeX the panel is a little narrower.

## System controls

Type a command or press Tab to the **System** filter. Each result says what Enter will do.

| Runs now | After a one-time permission | Opens Settings (Android doesn't let apps do it) |
|---|---|---|
| Restart / Shut down / Power menu (opens Samsung's power menu, where you tap the final option) | Brightness (`brightness 70`), auto brightness | Wi-Fi on/off (pop-up panel), NFC (panel) |
| Lock screen, Screenshot | Auto-rotate | Bluetooth, Dark mode, Battery saver |
| Notifications, Quick Settings, Recent apps, Split screen, Home | Do Not Disturb | Hotspot, Location, Airplane mode |
| Play/pause, Next, Previous, Volume up/down, `volume 40`, Mute, Torch | | |

The "Runs now" power and screen actions use the hotkey (accessibility) service. Volume, media and torch work without it.

## Build the APK

No Android Studio needed:

1. Push this folder to a GitHub repo.
2. Go to **Actions › Build APK**. It runs on every push. Download `orbit-apk` from the run.
3. (Optional) To make each new APK install over the old one, add a signing key as repo secrets: `OrbitSearch_KEYSTORE_B64`, `OrbitSearch_KEYSTORE_PASSWORD`, `OrbitSearch_KEY_ALIAS`, `OrbitSearch_KEY_PASSWORD`. Without them, each run uses a different debug key, so you have to uninstall before updating.

Locally: `gradle assembleRelease` (Gradle 8.14+, JDK 17, Android SDK 36).

## Setup on the tablet

1. Install the APK.
2. Open OrbitSearch settings and tap **Open Accessibility settings**. Switch on **OrbitSearch keyboard hotkey**.
3. If the switch is greyed out (Android blocks this for sideloaded apps): **Settings › Apps › OrbitSearch › ⋮ › Allow restricted settings**, then repeat step 2.

The accessibility service only checks key presses for the hotkey (`canRetrieveWindowContent=false`). It never reads the screen.

## Known limits

- Pop-up and side-by-side are requests. One UI decides whether the current mode honours them. In DeX they work as freeform windows. In normal tablet mode, side by side needs split screen to be possible for that app.
- App shortcuts (like "New note") need OrbitSearch to be the default home app on Android, so they're left for later.
- Android doesn't allow clipboard history in the background, so OrbitSearch won't offer it.

## Code map

```
OrbitAccessibilityService  hotkey + overlay window (the fast path)
SearchActivity             same panel in a floating window (icon / tile / shortcut)
ui/OrbitView               panel, keyboard handling (Enter → first result)
ui/GlassPanel, Palette     glass look, light & dark colours
search/*                   app index, fuzzy matching, calculator, settings list, ranking
Launcher                   opening results (normal / pop-up / side by side)
```
