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
