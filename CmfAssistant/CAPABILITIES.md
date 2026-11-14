# CMF Assistant: what it can control

Everything here works without root. "Needs" says what you must grant (Settings → Phone control access).
When something is missing, the assistant says so and opens the exact Android page.

## Works out of the box
| Say | Does |
|---|---|
| open / launch <app> | Opens it. Close-sounding names ask "Do you mean …?" first |
| flashlight on/off, torch | Torch |
| timer 5 minutes, alarm 7 am, show alarms | Clock app |
| call / dial <name or number> | Dialer (fuzzy contact match asks first) |
| text <name> saying … | Messages draft, always confirmed |
| volume up/down/50%, mute | Media volume |
| play / pause / next / previous | Media keys |
| battery, time, date | Info |
| search …, navigate to … | Web / Maps |
| open <site>.com | Browser |
| email … saying … | Email draft |
| add event <title> | Calendar editor prefilled |
| change wallpaper | Wallpaper picker |
| app info <app>, uninstall <app> | App details / system uninstall prompt |
| copy <text>, read clipboard | Clipboard |
| how much storage / RAM, am I online, is Wi-Fi/Bluetooth/airplane/DND on | Status read-outs |
| open <name> settings (about 40 pages: display, sound, battery, location, security, privacy, apps, default apps, accessibility, date, language, keyboard, NFC, hotspot, VPN, cast, developer options, about phone, storage, notifications, accounts, sync, printing …) | Opens that page; near-miss names ask first |
| Wi-Fi / Bluetooth / internet | Opens the Android panel (apps cannot flip them) |

## Needs one-time access
| Say | Needs |
|---|---|
| brightness up/down/60%/auto, auto-rotate on/off, screen timeout 2 minutes | Modify system settings |
| do not disturb on/off, silent / vibrate / normal mode, ring/alarm/notification/call volume | Do Not Disturb access |
| lock the phone, turn off the screen | Device admin (lock only) |
| read my notifications, clear notifications | Notification access |
| what's on my calendar | Calendar permission |
| call/text by name | Contacts permission |

## Fuzzy matching
Apps, contacts, settings pages and "app info/uninstall" targets use spelling similarity.
If the match is not exact, the assistant asks "Do you mean …?" and only acts after yes.

## Deliberately not included
- Factory reset, wipe, password reset (device-admin policies that can destroy data).
- Direct Wi-Fi / Bluetooth / mobile-data / airplane toggles (blocked for apps since Android 10 without root).
- Silent screenshots, other apps' data, USB debugging toggles.

## Ideas for later
Call-log queries (READ_CALL_LOG), location sharing, usage-access "screen time" report, shortcuts to routines
("good night" = DND + brightness low + alarm), accessibility-service gestures (back/home/recents/screenshot).

## v-next: wider commands, background screen control, offline companion
- Natural phrasing: "can you open wifi", "turn on developer options", "switch off bluetooth", Hinglish ("wifi chalu karo"). Toggles, settings pages and apps are resolved with fuzzy matching; AI is never used for these.
- Toggles (Wi-Fi, Bluetooth, mobile data, hotspot, NFC, location, airplane, battery saver, auto-rotate, etc.) are driven through the Settings page by the Accessibility service using node actions (not gestures), so your touches don't cancel them.
- Developer options: unlock (Build number x7) and toggle; "set smallest width to 444" jumps straight to the field.
- Global actions: back, home, recents, notifications, quick settings, power menu, split screen, lock, screenshot. Plus tap/type/scroll/read screen, force stop, clear cache, play music, reminders.
- Offline companion: greetings, moods, jokes, facts, riddles, stories, maths, coin/dice, "my name is X". AI is used only for real questions or knowledge.
Limits: Accessibility must be enabled manually (Access screen); switch labels vary by OEM; Wi-Fi/Bluetooth cannot be toggled directly on Android 10+ without root.
