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

## Smarter matching, screen awareness, AI Voice (latest)
- Speech clean-up: "wi-fi / why fi / wi fi" all become wifi, "d p i" -> dpi, "developer option(s)/dev options/developer mode" -> Developer options, "four four four" -> 444. The recogniser's other guesses are tried if the first isn't a command.
- Settings and apps are matched by sound and spelling, ignoring plurals and filler words ("bluetooth settings", "developer option"). Near-certain matches open directly, doubtful ones ask "Do you mean...?".
- "Find X" opens an app/setting, or offers a web search if nothing is found. "Where is X / how do I find X" and "what can you do" are answered offline.
- Leaving: "go back", "go home", "exit this screen" (Back), "close the app / exit" (Home; apps can't be killed without root).
- Screen awareness (Accessibility + on-device OCR): "what's on screen", "what can you see", "find the search bar", "tap search", "click Login". If an app exposes no text, a screenshot is read with on-device OCR.
- Gentle declines: no mention of keys or AI unless AI is on and set up.
- Settings > AI settings has two tabs: AI Chat and AI Voice (cloud text-to-speech with OpenAI, Groq, ElevenLabs or custom). Both are OFF by default; the offline voice is the fallback.

## Gestures, chains, stay-open, camera (latest)
- Gestures: long press / press and hold / tap and hold <thing>, double tap <thing>, swipe left/right/up/down, zoom in/out, select all / copy / paste / cut, scroll to top/bottom, press enter. "click it" repeats the last target.
- Chains: "open settings and search developer options", "open telegram and message demon hi", "send hi to demon on whatsapp", "turn off wifi and turn on bluetooth". In-app search and messaging find the search box/message box and Send button on their own. If a step fails, the assistant says which step it got stuck on and waits for your next instruction.
- Stay open between tasks (Settings > Stay open between tasks, on by default): after each task the window comes back and listens for the next one. Say ok, thanks, stop it or stop listening to close. Needs "Display over other apps" so the window can return over other apps.
- Camera: "take a picture / selfie" opens the camera and presses the shutter (selfie flips to the front camera and waits 1.5 s). With the camera already open, "take a picture" or "tap shutter" presses it.
- AI voice: FETCH MODELS and FETCH VOICES, and a Google (Gemini TTS) provider.

## Careful screen control (new)
- Taps only on an exact name; otherwise it searches the whole list, then asks "Do you mean X?" before touching anything.
- Several matches (toggles, same-named chats) -> it lists them and asks which; answer "the second one", "the top one", "the last".
- Ordinals and areas: "tap the second toggle", "long press the top right button", "click the last one".
- Relations: "click the stop button in front of Telegram", "... below Telegram", "what's right after that", "which button is above it".
- Short-term memory (last 5 things, ~5 minutes): "can you see the Demon chat?" then "click on it", "long press it", "delete it", "what's next to it".
- Scrolling: scroll left/right, "long scroll down", "scroll up 5 times", "keep scrolling down", "scroll until you see X".
- Switches: "toggle on", "turn it off", "enable <label>", "switch the second one on".
- Also: hide keyboard, refresh, next/previous page, "do it again", "type hello in the search box".
- AI voice: Gemini/OpenAI/ElevenLabs are streamed (speech starts on the first chunk); Groq/custom download first.

## Notifications, weather, typing (new)
- Needs Notification access. "Show all notifications" reads each one aloud; "read the second notification", "open the last notification", "reply to the first notification saying ok", "reply to Demon saying I'm busy" (uses the notification's own reply box), "clear notifications from Telegram", "did I get anything from YouTube", "what's Demon saying", "who needs my reply".
- "What's the weather (in Delhi / tomorrow)": Open-Meteo, no key; without a place it uses your network's rough location.
- "Type 123 / type hello" opens the search box or input itself when none is focused. "Type X in the search box" also works.
- "Click the third video / photo / item / last notification": ignores toolbar icons, picks real content.
- Gemini 3.x AI voice only: <chuckle> for laughs and IPA for Indian place names and acronyms.

## Search, play, install, notification follow-ups (new)
- "Search X on Instagram / Telegram / any app": opens that app and uses its own search box. "Search X" alone uses the search box of the app in front. The web is used only for "search X on Google / the web" (or when nothing on screen can search).
- "Play X on Namida / Glass Player / Mochi / Spotify": opens the app, searches X, plays the first match. "Play a random song on Namida": opens it and shuffles. "Play X on YouTube": searches YouTube and opens the first video. Naming an app means it is a music/video app.
- Settings > Default music app / Default video app: used when you say "play a song" or "play a video" without an app.
- "Install Instagram": opens the Play Store search, opens the app page and presses Install.
- Notifications: "any new notification" -> "7 notifications: 3 from Telegram, 2 from WhatsApp". Then "what's new from Telegram", "who sent them", "what do they say". If a person messaged you it asks "Do you want to reply to Demon?"; say yes, then what to say, and it sends it.
- "Notification history" opens Android's history page; "read my notification history" reads the assistant's own log (kept from when Notification access was granted).
- "Keep scrolling down", "long scroll", "scroll down 5 times" now stop only at the real end.
