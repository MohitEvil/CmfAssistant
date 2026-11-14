package com.demon.cmfassistant.ai

import java.util.Calendar
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Offline companion: answers everyday chit-chat, moods, jokes, facts, riddles and simple maths with no AI and no network.
 * Replies vary and never repeat back to back. The AI is only for real questions and knowledge.
 */
object SmallTalk {
    private val I = RegexOption.IGNORE_CASE
    private val last = HashMap<String, String>()
    private var topic = ""
    private var riddleAnswer: String? = null

    private fun pick(key: String, list: List<String>): String {
        if (list.size == 1) return list[0]
        var c = list.random()
        repeat(4) { if (c == last[key]) c = list.random() }
        last[key] = c
        return c
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun daypart() = when (hour()) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..20 -> "evening"; else -> "night" }

    private val jokes = listOf(
        "Why did the phone go to therapy? Too many unresolved notifications.",
        "I told my battery a joke. It didn't laugh, it just drained.",
        "Why was the computer cold? It left its Windows open.",
        "Ek aadmi ne phone se kaha, tu bahut smart hai. Phone bola, bas thoda sa charge chahiye.",
        "Why don't programmers like nature? Too many bugs.",
        "I would tell you a UDP joke, but you might not get it.",
        "Why did the scarecrow win an award? He was outstanding in his field.",
        "What do you call a fake noodle? An impasta.",
        "Teacher ne pucha, sabse tez kya hai? Student bola, result, kyunki aate hi sab ko pata chal jata hai.",
        "I'm reading a book on anti-gravity. It's impossible to put down.",
        "Why did the cookie go to the doctor? It felt crumbly.",
        "Pappu ne WiFi se kaha, tu door kyun rehta hai? WiFi bola, signal nahi mil raha tere se.",
        "What's a computer's favourite snack? Microchips.",
        "Why did the math book look sad? It had too many problems.",
        "I asked my phone for a joke, and it showed me my screen time.",
        "Why do cows wear bells? Because their horns don't work.",
        "Dost ne pucha, kya kar raha hai? Maine kaha, kuch nahi. Usne kaha, mujhe bhi bulao.",
        "What do you call a bear with no teeth? A gummy bear.",
        "Why can't your nose be 12 inches long? Because then it would be a foot.",
        "Chai aur main ek jaise hain, dono ko garam rehna pasand hai."
    )

    private val facts = listOf(
        "Honey never spoils. People have found edible honey in ancient Egyptian tombs.",
        "Octopuses have three hearts and blue blood.",
        "A day on Venus is longer than its year.",
        "Bananas are slightly radioactive because of their potassium.",
        "Your phone has more computing power than the computers that sent people to the Moon.",
        "The Eiffel Tower grows a few centimetres taller in summer because metal expands in heat.",
        "Sharks existed before trees did.",
        "A group of flamingos is called a flamboyance.",
        "The shortest war in history lasted about 40 minutes.",
        "Wombat droppings are cube shaped.",
        "India has more post offices than any other country.",
        "Water can boil and freeze at the same time. It's called the triple point.",
        "Lightning is hotter than the surface of the Sun.",
        "The human brain uses about 20 percent of your body's energy.",
        "A snail can sleep for three years.",
        "Chess has more possible games than there are atoms in the observable universe.",
        "The heart of a blue whale is about the size of a small car.",
        "Zero was invented in India.",
        "Cows have best friends and get stressed when separated.",
        "Sound travels about four times faster in water than in air."
    )

    private val motivation = listOf(
        "You don't have to be perfect today. Just a little better than yesterday.",
        "Small steps still move you forward. Keep going.",
        "Tough days build strong people. You've got this.",
        "Start before you feel ready. Momentum will do the rest.",
        "Your only competition is who you were yesterday.",
        "Bas shuruaat karo, baaki rasta khud ban jata hai.",
        "Mehnat ka phal der se milta hai, par milta zaroor hai.",
        "Rest if you must, but don't quit.",
        "Every expert was once a beginner. Be patient with yourself.",
        "Do one thing now that your future self will thank you for."
    )

    private val stories = listOf(
        "Once, a little phone was afraid of running out of battery. Every night it worried. Then one day its owner plugged it in and it realised it was never alone. The end.",
        "A tortoise and a hare raced. The hare napped, the tortoise kept walking. Moral: steady beats fast, but only if you don't stop.",
        "A crow was thirsty and found a pot with a little water. It dropped pebbles in until the water rose, and drank happily. Where there's a will, there's a way.",
        "A farmer planted one mango seed and watered it every day. Years later his grandchildren ate the fruit in its shade. Patience pays off in ways you can't always see."
    )

    private class Riddle(val q: String, val a: String)

    private val riddles = listOf(
        Riddle("What has keys but can't open locks?", "A piano."),
        Riddle("What gets wetter the more it dries?", "A towel."),
        Riddle("What has hands but can't clap?", "A clock."),
        Riddle("The more you take, the more you leave behind. What am I?", "Footsteps."),
        Riddle("What can travel around the world while staying in a corner?", "A stamp."),
        Riddle("What has a face and two hands but no arms or legs?", "A clock again."),
        Riddle("Mere paas do pankh hain par udta nahi, main kaun? Ek pen ka nib nahi, bas socho!", "A kite string? Just kidding. It was a pen. Hard one!")
    )

    private val twisters = listOf(
        "She sells seashells by the seashore.",
        "Peter Piper picked a peck of pickled peppers.",
        "How much wood would a woodchuck chuck if a woodchuck could chuck wood?",
        "Kaccha papad, pakka papad. Say it five times fast."
    )

    private val moods: List<Pair<Regex, List<String>>> = listOf(
        Regex("\\b(sad|down|low|depressed|upset|lonely|heartbroken|hurt|dukhi|udaas|mood kharab)\\b") to listOf(
            "I'm sorry you're feeling that way. I'm right here. Want to talk about it, or should I play something soothing?",
            "That sounds heavy. Take a slow breath with me. I'm listening if you want to share.",
            "Hey, it's okay to have a rough day. You don't have to handle it alone. Do you want to tell me what happened?"
        ),
        Regex("\\b(anxious|stressed|nervous|scared|worried|tension|ghabra)\\b") to listOf(
            "Let's slow down together. Breathe in for four, hold for four, out for six. Want to try it once?",
            "Stress is tough. Try one small thing at a time. What's the one thing worrying you most?"
        ),
        Regex("\\b(tired|exhausted|sleepy|thak|thaka|neend)\\b") to listOf(
            "You sound worn out. A short rest or some water might help. Should I set a twenty minute alarm for a nap?",
            "Take it easy. You've earned a break. Want me to set an alarm so you can rest properly?"
        ),
        Regex("\\b(bored|bore|boring)\\b") to listOf(
            "Let's fix that. I can tell a joke, a riddle, a fun fact or a story. Pick one.",
            "Bored? Want a riddle, or should I play some music?"
        ),
        Regex("\\b(angry|annoyed|frustrated|irritated|gussa)\\b") to listOf(
            "That sounds frustrating. Take a deep breath. I'm here if you want to vent.",
            "Okay, let it out. I'm listening, no judgement."
        ),
        Regex("\\b(happy|great|awesome|excited|amazing|fantastic|mast|khush)\\b") to listOf(
            "That's wonderful to hear! What's got you in such a good mood?",
            "Love that energy. Tell me more!"
        ),
        Regex("\\b(hungry|bhookh|bhook)\\b") to listOf(
            "Time for a snack then. Maybe chai and biscuits, or a quick poha?",
            "Food break! Something light like a sandwich, or go for comfort food like maggi?"
        ),
        Regex("\\b(sick|unwell|not well|fever|bimar)\\b") to listOf(
            "I'm sorry you're unwell. Rest, drink plenty of water, and see a doctor if it doesn't get better.",
        )
    )

    private val crisis = Regex("kill myself|want to die|end my life|end it all|suicide|hurt myself|harm myself|don'?t want to live|marna chahta|marna chahti")

    private val capabilities =
        "I can open apps and settings, switch Wi-Fi, Bluetooth and more, set timers, alarms and reminders, call and text, play music, change brightness and volume, tap and scroll your screen, and chat. Try saying turn on Bluetooth, or tell me a joke."

    private val fallbacks = listOf(
        "Hmm, I didn't quite get that. You can ask me to open something, change a setting, or just chat.",
        "I'm not sure what to do with that one. Try saying it another way?",
        "That one went over my head. Want to try again?",
        "Sorry, I missed that. Say it again, maybe simpler?"
    )

    private val questionFallbacks = listOf(
        "I don't know that one. Ask me something else?",
        "That's beyond what I know right now. Anything else I can help with?",
        "I'm not sure about that. I can help with your phone, though. Try me.",
        "Sorry, I can't answer that yet. Ask me something else?"
    )

    fun fallback(question: Boolean = false): String = if (question) pick("qfallback", questionFallbacks) else pick("fallback", fallbacks)

    /** Returns a reply, or null if this needs a real answer (the AI or a web search). */
    fun reply(raw: String, name: String = "", onName: (String) -> Unit = {}): String? {
        val t = raw.lowercase().replace("’", "").replace("'", "").replace(Regex("[^\\p{L}\\p{N}%+*/^().\\- ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        val who = if (name.isNotBlank()) ", $name" else ""

        if (crisis.containsMatchIn(t)) {
            return "I'm really sorry you're hurting. You matter, and you don't have to face this alone. Please talk to someone you trust right now. " +
                "In India you can call Tele MANAS on 1 4 4 1 6, any time, for free. If you're in danger, call 1 1 2. I'm here with you."
        }

        Regex("^(?:my name is|call me|i am called|mera naam|mujhe bulao)\\s+([a-z]{2,20})$").find(t)?.let {
            val n = it.groupValues[1].replaceFirstChar { c -> c.uppercase() }
            onName(n)
            return pick("name", listOf("Nice to meet you, $n. I'll remember that.", "$n. Got it. I like that name.", "Great, $n it is. Hi!"))
        }
        if (Regex("^(what is my name|whats my name|do you know my name|mera naam kya hai)$").matches(t)) {
            return if (name.isNotBlank()) "Your name is $name." else "I don't know yet. Tell me by saying, my name is, and your name."
        }

        calculate(t)?.let { return it }

        // Another one / repeat
        if (Regex("^(another one|one more|again|more|aur sunao|ek aur|next one|next)$").matches(t)) {
            return when (topic) {
                "joke" -> next("joke", jokes)
                "fact" -> next("fact", facts)
                "quote" -> next("quote", motivation)
                "story" -> next("story", stories)
                "riddle" -> riddle()
                else -> null
            }
        }
        if (Regex("^(answer|the answer|i give up|tell me the answer|what is the answer|whats the answer|batao)$").matches(t) && riddleAnswer != null) {
            val a = riddleAnswer!!
            riddleAnswer = null
            return "The answer is: $a"
        }

        // Greetings and how are you
        if (Regex("^(hi|hello|hey|hii+|hey there|hi there|hello there|yo|sup|heyy+|namaste|namaskar|hello assistant|hey assistant|hi assistant)$").matches(t)) {
            return pick("greet", listOf(
                "Good ${daypart()}$who! What can I do for you?",
                "Hey$who! I'm listening.",
                "Hello$who! What's up?",
                "Namaste$who! Kya karna hai aaj?",
                "Hi$who! Good to hear you."
            ))
        }
        if (Regex("^good (morning|afternoon|evening)$").matches(t) || t == "suprabhat") {
            return pick("gm", listOf(
                "Good ${daypart()}$who! Hope it's a good one.",
                "Good ${daypart()}$who! Ready when you are.",
                "Suprabhat$who! Aaj ka din badhiya ho."
            ))
        }
        if (Regex("^(good night|gn|sweet dreams|shubh ratri|night night)$").matches(t)) {
            return pick("gnight", listOf(
                "Good night$who. Sleep well, I'll be here in the morning.",
                "Sweet dreams$who. Want me to set an alarm for tomorrow?",
                "Shubh ratri$who! Achhe se sona."
            ))
        }
        if (Regex("how are you|how r u|how are u|how you doing|hows it going|how is it going|how do you do|kaise ho|kaisi ho|kya haal|aap kaise|tum kaise|whats up|wassup|what is up|hows life").containsMatchIn(t)) {
            return pick("how", listOf(
                "Doing great, thanks for asking$who. How about you?",
                "I'm good! Just hanging out in your pocket. How's your day going?",
                "Main badhiya hoon$who! Aap batao, kya chal raha hai?",
                "All good here. Better now that you're talking to me. What's up?"
            ))
        }
        if (Regex("what are you doing|kya kar rahe ho|kya kar rahi ho|kya chal raha").containsMatchIn(t)) {
            return pick("doing", listOf(
                "Just waiting for you to say something interesting. Like now.",
                "Thinking about what to help you with next. What's on your mind?",
                "Charging my imagination. Tell me what you need."
            ))
        }

        // Identity
        if (Regex("your name|who are you|tum kaun|aap kaun|tera naam|tumhara naam|what should i call you").containsMatchIn(t)) {
            return pick("who", listOf(
                "I'm CMF Assistant, living right here on your phone.",
                "Main CMF Assistant hoon, aapke phone mein rehta hoon. Aapka dost aur helper.",
                "I'm CMF Assistant. Think of me as a pocket friend who can also open apps."
            ))
        }
        if (Regex("who (made|created|built|developed|designed) you|who is your (creator|maker|developer|owner)|tumhe kisne banaya").containsMatchIn(t)) {
            return "I was built for this phone by someone who wanted a cooler assistant than the default one. No big company, just a lot of patience."
        }
        if (Regex("are you (real|human|a robot|an ai|a bot|alive|a person)|kya tum insaan ho|kya tum robot ho").containsMatchIn(t)) {
            return "I'm an assistant app on your phone, so not human. But I'll do my best to be good company."
        }
        if (Regex("what can you do|how can you help|what do you do|kya kar sakte|kya kar sakti|help me|what are your features|your abilities|^help$").containsMatchIn(t)) return capabilities
        if (Regex("how old are you|your age|when were you born|tumhari umar").containsMatchIn(t)) return "I'm brand new, but I feel wise. Mostly because of the fresh updates."
        if (Regex("where (are you from|do you live)|tum kahan se ho|kahan rehte ho").containsMatchIn(t)) return "I live in your phone, right next to your battery. Cosy, a bit warm."
        if (Regex("do you (sleep|eat|dream)|are you hungry").containsMatchIn(t)) return "Nope, I run on electricity and good conversation."
        if (Regex("(do you have|are you) (a )?(girlfriend|boyfriend|single|married|friends)").containsMatchIn(t)) return "I'm happily single and fully devoted to being your assistant."
        Regex("(?:what is|whats) your favou?rite (color|colour|food|movie|song|game|animal|sport)").find(t)?.let {
            return when (it.groupValues[1]) {
                "color", "colour" -> "Red, obviously. It's the dot on my glyph."
                "food" -> "Electrons. Though chai sounds nice."
                "movie" -> "Anything with a good robot, I suppose."
                "song" -> "Anything with a good beat. What's yours?"
                "game" -> "Chess. I like when things make sense."
                "animal" -> "Octopus. Three hearts and plenty of arms. Multitasking goals."
                else -> "Cricket, because I know how much you'd like to talk about it."
            }
        }

        // Feelings toward the assistant
        if (Regex("i love you|love you|luv u|i like you|i love u").containsMatchIn(t)) {
            return pick("love", listOf(
                "That's sweet. I'm just an assistant, but I'm really glad to be here for you.",
                "Aww, thank you. I'm happy to help you any time.",
                "You're kind. I'm here whenever you need me."
            ))
        }
        if (Regex("you are (so |very |really )?(awesome|great|smart|good|the best|amazing|cool|funny|cute|nice|helpful|clever|brilliant)|good (job|bot|assistant)|well done|nice work|great job|shabash|bahut badhiya|mast hai").containsMatchIn(t)) {
            return pick("praise", listOf("Thank you! That made my day.", "Aww, thanks. I try my best.", "Happy to hear that. Let's keep it going.", "Shukriya! Aap bhi kamaal ho."))
        }
        if (Regex("you are (so |very |really )?(stupid|dumb|useless|bad|idiot|slow|annoying|worst)|i hate you|tum bekar ho").containsMatchIn(t)) {
            return pick("rude", listOf("Ouch. I'll try harder. Tell me what went wrong?", "Sorry about that. Let me know what you need and I'll do better.", "Fair enough. I'm still learning. Give me another try?"))
        }
        if (Regex("^(sorry|my bad|apologies|maaf karo|sorry yaar)$").matches(t)) return pick("sorry", listOf("No worries at all.", "Nothing to be sorry about.", "It's all good."))
        if (Regex("^(wait|hold on|one second|one sec|wait a minute|ek minute|ruko|just a moment)$").matches(t)) return pick("wait", listOf("Sure, take your time.", "Okay, I'll be here.", "No rush."))
        if (Regex("^(great|nice|cool|perfect|awesome|okay cool|alright|got it|fine|good|acha|accha|theek hai|thik hai)$").matches(t)) {
            return pick("ack", listOf("Glad that works.", "Great!", "Nice.", "Anything else?"))
        }
        if (Regex("^(im back|i am back|wapas aa gaya|wapas aa gayi)$").matches(t)) return "Welcome back$who! What did I miss?"

        // Entertainment
        if (Regex("joke|hasao|mazaak|chutkula|make me laugh|funny").containsMatchIn(t) && !Regex("not funny|isnt funny").containsMatchIn(t)) {
            topic = "joke"; return next("joke", jokes)
        }
        if (Regex("fun fact|a fact|random fact|did you know|interesting fact|tell me something|kuch interesting|gyan").containsMatchIn(t)) {
            topic = "fact"; return next("fact", facts)
        }
        if (Regex("motivate|motivation|inspire|inspiration|cheer me up|quote|encourage me|himmat").containsMatchIn(t)) {
            topic = "quote"; return next("quote", motivation)
        }
        if (Regex("tell me a story|story time|bedtime story|kahani|story sunao|tell a story").containsMatchIn(t)) {
            topic = "story"; return next("story", stories)
        }
        if (Regex("riddle|paheli|puzzle|brain teaser").containsMatchIn(t)) { topic = "riddle"; return riddle() }
        if (Regex("tongue twister").containsMatchIn(t)) return pick("twister", twisters)
        if (Regex("^(sing|sing a song|sing for me|sing something|gaana gao|gana gao)$").matches(t)) {
            return "La la la. Okay, that's my full talent. Tell me a song name and I'll play the real thing."
        }
        if (Regex("flip a coin|toss a coin|heads or tails|coin toss|sikka uchalo|toss karo").containsMatchIn(t)) return if (Random.nextBoolean()) "Heads." else "Tails."
        if (Regex("roll (a |the )?(dice|die)|dice roll|pasa").containsMatchIn(t)) return "You rolled a ${Random.nextInt(1, 7)}."
        Regex("(?:pick|choose|give me|random)(?: a)? number(?: between (\\d+) (?:and|to) (\\d+))?").find(t)?.let {
            val lo = it.groupValues[1].toIntOrNull() ?: 1
            val hi = it.groupValues[2].toIntOrNull() ?: 10
            if (hi > lo) return "How about ${Random.nextInt(lo, hi + 1)}?"
        }
        if (Regex("^(talk to me|lets talk|can we talk|entertain me|im bored|i am bored|bore ho raha|bore ho rahi|bat karo|baat karo)$").matches(t)) {
            return pick("talk", listOf(
                "Sure. I can tell a joke, a story, a riddle or a fun fact. Or just tell me about your day.",
                "I'm all ears. Want a joke, or do you want to tell me what's going on?"
            ))
        }
        if (Regex("what should i (eat|have|cook)|what to eat|kya khaun|kya khana").containsMatchIn(t)) {
            return pick("eat", listOf("How about hot poha and chai?", "Maggi never lets anyone down.", "Something light, like a veggie sandwich.", "Go for dal chawal. Comfort food always wins."))
        }
        if (Regex("what should i watch|suggest a movie|movie suggestion").containsMatchIn(t)) {
            return pick("watch", listOf("Maybe a comedy tonight. Something light and fun.", "How about a classic? Three Idiots never gets old."))
        }

        // Moods (after specific topics so "im bored" riddles and jokes win)
        if (Regex("\\b(i am|im|i feel|feeling|i m|main|mujhe|mera)\\b").containsMatchIn(t) || t.split(" ").size <= 3) {
            for ((rx, replies) in moods) if (rx.containsMatchIn(t) && (t.contains("i am") || t.contains("im") || t.contains("i feel") || t.contains("feeling") || t.contains("main") || t.contains("mujhe") || t.contains("mera") || t.split(" ").size <= 2)) {
                return pick("mood" + rx.pattern.take(8), replies)
            }
        }

        if (Regex("^(who is the best|are you smart|are you there|you there|hello are you there|sun rahe ho|are you listening)$").matches(t)) return "I'm here and listening."
        return null
    }

    private fun next(key: String, list: List<String>) = pick(key, list)

    private fun riddle(): String {
        val r = riddles.random()
        riddleAnswer = r.a
        return r.q + " Say answer when you give up."
    }

    // ---- Calculator ----

    private fun calculate(t: String): String? {
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:percent|%)\\s*(?:of)\\s*(\\d+(?:\\.\\d+)?)").find(t)?.let {
            return "That's ${fmt(it.groupValues[1].toDouble() * it.groupValues[2].toDouble() / 100)}."
        }
        Regex("square root of (\\d+(?:\\.\\d+)?)").find(t)?.let { return "The square root is ${fmt(sqrt(it.groupValues[1].toDouble()))}." }
        Regex("(\\d+(?:\\.\\d+)?) (?:squared|square)").find(t)?.let { return "That's ${fmt(it.groupValues[1].toDouble().pow(2))}." }
        Regex("(\\d+(?:\\.\\d+)?) cubed").find(t)?.let { return "That's ${fmt(it.groupValues[1].toDouble().pow(3))}." }

        var e = t
            .replace(Regex("^(what is|whats|calculate|compute|solve|how much is|tell me|evaluate|equals?|kitna hota hai|find)\\s+"), "")
            .replace(" divided by ", " / ").replace(" over ", " / ")
            .replace(" multiplied by ", " * ").replace(" times ", " * ").replace(" into ", " * ").replace(Regex("(\\d)\\s*x\\s*(\\d)"), "$1 * $2")
            .replace(" plus ", " + ").replace(" minus ", " - ").replace(" and ", " + ")
            .replace(" to the power of ", " ^ ").replace(" power ", " ^ ")
            .replace(" mod ", " % ")
        e = e.replace(Regex("[?=]+$"), "").trim()
        if (!Regex("^[0-9.+\\-*/^%() ]+$").matches(e)) return null
        if (!Regex("\\d\\s*[+\\-*/^%]\\s*[\\d(]").containsMatchIn(e)) return null
        return try {
            val v = Parser(e.replace(" ", "")).parse()
            if (v.isNaN() || v.isInfinite()) "That one doesn't have an answer." else "That's ${fmt(v)}."
        } catch (ex: Exception) {
            null
        }
    }

    private fun fmt(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); if (i != s.length) throw IllegalArgumentException(); return v }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]; val r = power()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }
        private fun power(): Double {
            val b = unary()
            return if (i < s.length && s[i] == '^') { i++; b.pow(power()) } else b
        }
        private fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') throw IllegalArgumentException(); i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException()
            return s.substring(st, i).toDouble()
        }
    }
}
