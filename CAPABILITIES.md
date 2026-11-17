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

## Navigation & maps
- "Navigate to X", "take me to X with Waze / Google Maps / any nav app", "walking directions to X"
- "Find hotels / parks / schools / markets / pharmacies / petrol pumps near me" (or "near Connaught Place") – speaks the closest ones with distances and opens the map
- "How far is X from Y", "how long to reach X (walking / by bike)", "best route to X", "shortcut to X"
- Allow Location in the Access screen for exact results; otherwise the network's rough area is used.

## Messages by dictation
- "Send a message to Demon on Telegram" → asks "What should I say?" → sends what you say ("cancel" stops it).

## Scheduled tasks (Tasker-style)
- "Open YouTube every day at 10 AM", "news briefing every morning", "every morning except Sunday", "only on Sunday at 8 pm", "on weekdays / weekends", "tomorrow at 7 say good morning", "in 30 minutes turn off Wi-Fi", "on 25 December at 9 AM …"
- "Remind me to drink water every day at 11" is spoken aloud at that time.
- Manage: "what scheduled tasks do I have", "delete the news automation", "delete all automations", "pause / resume automations".
- "Keep the screen awake for the next 6 hours" / "stop keeping the screen awake" (notification with a Stop button).

## News & web answers
- "Any new news?", "latest news about cricket", "news briefing" → ~10 different headlines.
- With AI chat off, other questions are answered from the web (DuckDuckGo / Wikipedia).

## Tool calling (Needle 3) and cross-app jobs
- Sentences the fixed grammar doesn't know go to tool calling: Needle 3 on the phone (when the GitHub build downloaded it), else your AI provider. Several tool calls become one chain across apps.
- "Order biryani from Swiggy", "book a cab to the airport", "book a table at Barbeque Nation": opens the app, searches, picks the first match and stops before paying.
- Open-ended jobs ("do this in the apps: …") use your AI provider to read the screen and tap step by step. It always asks before anything that pays or confirms.

## Advanced access (optional)
- Settings → Advanced access: Auto / Off / Shizuku / Root. Used only when granted; otherwise everything falls back.
- With it: instant Wi-Fi / data / Bluetooth / airplane / NFC / location / dark mode switches, force stop and clear caches without opening Settings, "clear data of X" (asks first), "revoke location from X", "turn off animations", "close background apps", "restart the phone", "run command …" (asks first).
- Without it: "audit location permissions", "how much storage is left", "do not disturb until 7 am / for 2 hours" still work.

## Assistant trigger
- The app is registered as a digital assistant, so the power-button / corner-swipe gesture opens the small panel and listens. Choose CMF Assistant under Settings → Apps → Default apps → Digital assistant app.
- Starting a song or video stops listening and closes the panel.

## Hybrid brain (Nov 18)
Order for every spoken request: Needle 3 (on the phone) -> your cloud AI (only if switched on; it also sees the recent chat and what is on screen) -> fixed grammar -> plain answer.
- Needle/cloud can plan several steps: "open Play Store, wait 10 seconds, search Telegram and install it", "turn on the flashlight, wait 10 seconds and turn it off", "take a picture and send it to X on WhatsApp".
- The grammar handles the same chains offline (wait N seconds, "turn it off", "install it"), and wins over an unsure Needle guess that understood less.
- Chat history is saved on the phone (45 min of context for follow-ups). Say "clear chat history" to wipe it.
- Calls: direct with Phone permission, else through Shizuku/root, else the dialer + auto-press Call.
- Web answers: follow-up aware ("and its value?"), ranks Wikipedia, DuckDuckGo and Bing snippets by your key words and numbers.
- On-screen matching: closest match is used directly (no "did you mean?" dead end); "change it to X" picks X on the open list.
