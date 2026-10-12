package com.ultron.assistant;

import java.util.*;
import java.util.regex.Pattern;

/**
 * High-Accuracy Natural Language Intent Engine & Phonetic Normalizer for Ultron Android.
 * Direct zero-dependency Java port of speech_normalizer.py and ultron.py.
 */
public class UltronIntentEngine {

    // Phonetic variants regex for "Ultron" ("hey ultron", "all thrown", "all drone", etc.)
    private static final Pattern PHONETIC_ULTRON_PATTERN = Pattern.compile(
        "\\b(hey\\s+ultron|ok\\s+ultron|hi\\s+ultron|hello\\s+ultron|" +
        "all\\s+thrown|all\\s+throne|all\\s+drone|all\\s+drawn|" +
        "hall\\s+thrown|hall\\s+drone|" +
        "ultra\\s+on|alter\\s+on|" +
        "altron|oltron|ultran|eltron)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final List<String> WAKE_WORDS = Arrays.asList(
        "hey ultron", "ultron", "ok ultron", "hi ultron", "hello ultron",
        "hey all thrown", "all thrown", "hey all drone", "all drone",
        "hey altron", "altron", "ultra on", "hey ultra on"
    );

    public static class ActionCommand {
        public String intent;
        public String entity;
        public String raw;

        public ActionCommand(String intent, String entity, String raw) {
            this.intent = intent;
            this.entity = entity;
            this.raw = raw;
        }
    }

    /**
     * Normalizes acoustic misrecognitions from speech-to-text.
     */
    public String normalizeSpeech(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }
        String cleaned = text.toLowerCase().trim();

        // 1. Normalize Ultron variants
        cleaned = PHONETIC_ULTRON_PATTERN.matcher(cleaned).replaceAll("ultron");

        // 2. Hardware toggles phonetic fixes
        cleaned = cleaned.replaceAll("\\b(wi[- ]?fi|why[- ]?fi|wai[- ]?fai|wee[- ]?fee|waifai|why\\s+fee|why\\s+fire|wife|y\\s+fi)\\b", "wifi");
        cleaned = cleaned.replaceAll("\\b(blue[- ]?tooth|bluetooths|blue\\s+tooths|blootooth|blue\\s+truth|blow\\s+tooth|plutooth)\\b", "bluetooth");
        cleaned = cleaned.replaceAll("\\b(flash[- ]?light|flesh[- ]?light|flash\\s+night|fresh\\s+light|tour[- ]?ch|torch|torch\\s+light)\\b", "flashlight");
        cleaned = cleaned.replaceAll("\\b(valume|vol[- ]?ume|valyoom|sound\\s+level|wall\\s+volume)\\b", "volume");
        cleaned = cleaned.replaceAll("\\b(screen[- ]?shot|screan[- ]?shot|screen\\s+short|snap\\s+screen|screen\\s+shoot|screen\\s+shit)\\b", "screenshot");

        // 3. System & UI Controls phonetic fixes
        // Settings: covers all Vosk misrecognitions ("said things", "say things", "sed things", "set in", "set thing", etc.)
        cleaned = cleaned.replaceAll("\\b(said\\s+things|say\\s+things|saying\\s+things|sed\\s+things|sit\\s+things|set\\s+things|sat\\s+things|sad\\s+things|sub\\s+things|send\\s+things|sent\\s+things|set\\s+thing|set\\s+ins?|set\\s+in|sayings|setting\'?s?)\\b", "settings");
        cleaned = cleaned.replaceAll("\\b(kwick\\s+settings|quick\\s+setting|quick\\s+said\\s+things|quick\\s+set\\s+things|quick\\s+sad\\s+things)\\b", "quick settings");
        cleaned = cleaned.replaceAll("\\b(stats\\s+bar|statas\\s+bar|notification\\s+bar|notif\\s+bar)\\b", "status bar");
        cleaned = cleaned.replaceAll("\\b(resent\\s+apps?|resunt\\s+apps?|last\\s+apps?|switch\\s+apps?|recent\\s+tab|recent\\s+tabs)\\b", "recent apps");
        cleaned = cleaned.replaceAll("\\b(flowting|floting)\\b", "floating");

        // 4. Popular apps phonetic fixes
        cleaned = cleaned.replaceAll("\\b(what\'?s?\\s*app|wat[- ]?zap|watsapp|wa|what\\s+sap|watts\\s+app)\\b", "whatsapp");
        cleaned = cleaned.replaceAll("\\b(insta|ig|in\\s+sta|insta\\s+gram)\\b", "instagram");
        cleaned = cleaned.replaceAll("\\b(spot\\s*ify|spotty\\s*fy|spoti\\s*pie|spot\\s*fight|sportify)\\b", "spotify");
        cleaned = cleaned.replaceAll("\\b(tele\\s*gram|tellegram|tell\\s+gram)\\b", "telegram");
        cleaned = cleaned.replaceAll("\\b(cam\\s*ra|kamra|kamera|cam|came\\s+ra)\\b", "camera");
        cleaned = cleaned.replaceAll("\\b(gel\\s*ry|gallary|galery|photoes|photos|gelry)\\b", "gallery");
        cleaned = cleaned.replaceAll("\\b(reel\\s+section|reels\\s+section|reels|reel|real\\s+section)\\b", "reels");
        cleaned = cleaned.replaceAll("\\b(short\\s+section|shorts\\s+section)\\b", "shorts");
        cleaned = cleaned.replaceAll("\\b(cal\\s*cue?\\s*later|calcu\\s*later|kalkulator|calc)\\b", "calculator");
        cleaned = cleaned.replaceAll("\\b(you\\s*tube|u\\s*tube|u-tube|yt)\\b", "youtube");
        cleaned = cleaned.replaceAll("\\b(google\\s+crown|google\\s+crome|google\\s+chrom|browser|crome)\\b", "chrome");
        cleaned = cleaned.replaceAll("\\b(my\\s+files?|file\\s+manager|explorer|my\\s+file)\\b", "files");
        cleaned = cleaned.replaceAll("\\b(sms|text\\s+message|messages?|massage|massages)\\b", "messages");
        cleaned = cleaned.replaceAll("\\b(dialer|dial|call|phone\\s+call)\\b", "phone");

        // 5. Action verb phonetic fixes
        cleaned = cleaned.replaceAll("\\b(right|ride|rite)\\s+(note|notes|file|text)\\b", "write $2");
        cleaned = cleaned.replaceAll("\\b(claws|clothes)\\s+(chrome|app|youtube|settings|whatsapp|instagram)\\b", "close $2");
        cleaned = cleaned.replaceAll("\\b(sirch|soorch|goggle|googel)\\b", "search");
        cleaned = cleaned.replaceAll("\\b(hoppen|opun|lunch|lanch|open\\s+up)\\b", "open");
        cleaned = cleaned.replaceAll("\\b(torn\\s+on|tern\\s+on)\\b", "turn on");
        cleaned = cleaned.replaceAll("\\b(torn\\s+off|tern\\s+off)\\b", "turn off");

        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /**
     * Detects if speech contains Ultron wake word.
     */
    public boolean isWakeWord(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase().trim();
        for (String w : WAKE_WORDS) {
            if (lower.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        String norm = normalizeSpeech(text);
        return norm.matches(".*\\bultron\\b.*");
    }

    /**
     * Strips wake words from text.
     */
    public String stripWakeWords(String text) {
        if (text == null) return "";
        String norm = normalizeSpeech(text);
        String cleaned = norm.replaceAll("\\b(?:hey|ok|hi|hello)\\s+ultron\\b", " ")
                             .replaceAll("\\bultron\\b", " ");
        for (String w : WAKE_WORDS) {
            cleaned = cleaned.replaceAll("\\b" + Pattern.quote(w) + "\\b", " ");
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /**
     * Splits compound instructions like 'open instagram and go to reel section' or 'turn on wifi and open chrome'.
     */
    public List<String> splitCompoundCommands(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        String body = stripWakeWords(text);
        // Split on:
        // "and then", "then", "after that"
        // OR "and" followed by an action verb (open, launch, start, run, go, navigate, switch, show, close, kill, exit, turn, enable, disable, write, search, play, clear, take, etc.)
        String splitRegex = "\\s+(?:and\\s+then|then|after\\s+that|and\\s+(?=(?:open|launch|run|start|go|navigate|switch|show|close|kill|exit|write|create|search|google|what|tell|how|play|turn|enable|disable|clear|take)\\b))\\s*";
        String[] parts = body.split(splitRegex);

        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        if (result.isEmpty() && !body.isEmpty()) {
            result.add(body);
        }
        return result;
    }

    /**
     * Classifies atomic command into intent and extracts target entity.
     */
    public ActionCommand classifyCommand(String cmd) {
        String c = cmd.toLowerCase().trim();

        // 1. Phone System / Device Settings
        if (c.matches(".*\\b(phone settings|android settings|device settings|system settings|open phone settings|open android settings|open device settings|open system settings)\\b.*")) {
            return new ActionCommand("open_system_settings", "", c);
        }

        // 1b. Ultron Settings command (Theme, Voice, Assistant preferences)
        if (c.matches(".*\\b(settings|ultron settings|open settings|preferences|options|voice settings|theme settings|sound settings|customize|" +
                       "said things|open said things|say things|open say things|set things|open set things|sed things|open sed things|" +
                       "setting|open setting|settings app)\\b.*")) {
            return new ActionCommand("open_settings", "", c);
        }

        // 2. Hardware Toggles: Wi-Fi (Robust multi-variant regex)
        if (c.matches(".*\\b(turn on (?:wifi|why fi|wife|why fee)|turn (?:wifi|why fi|wife|why fee) on|switch on (?:wifi|why fi|wife)|enable (?:wifi|why fi|wife)|(?:wifi|why fi|wife) on|open (?:wifi|why fi|wife)|connect (?:wifi|why fi|wife))\\b.*")) {
            return new ActionCommand("wifi_on", "", c);
        }
        if (c.matches(".*\\b(turn off (?:wifi|why fi|wife|why fee)|turn (?:wifi|why fi|wife|why fee) off|switch off (?:wifi|why fi|wife)|disable (?:wifi|why fi|wife)|(?:wifi|why fi|wife) off|disconnect (?:wifi|why fi|wife))\\b.*")) {
            return new ActionCommand("wifi_off", "", c);
        }

        // 3. Hardware Toggles: Bluetooth (Robust multi-variant regex)
        if (c.matches(".*\\b(turn on (?:bluetooth|blue tooth)|turn (?:bluetooth|blue tooth) on|switch on (?:bluetooth|blue tooth)|enable (?:bluetooth|blue tooth)|(?:bluetooth|blue tooth) on)\\b.*")) {
            return new ActionCommand("bluetooth_on", "", c);
        }
        if (c.matches(".*\\b(turn off (?:bluetooth|blue tooth)|turn (?:bluetooth|blue tooth) off|switch off (?:bluetooth|blue tooth)|disable (?:bluetooth|blue tooth)|(?:bluetooth|blue tooth) off)\\b.*")) {
            return new ActionCommand("bluetooth_off", "", c);
        }

        // 3b. Hardware Toggles: Flashlight / Torch
        if (c.matches(".*\\b(turn on (?:flashlight|flash light|flesh light|torch)|turn (?:flashlight|flash light|flesh light|torch) on|switch on (?:flashlight|torch)|enable (?:flashlight|torch)|(?:flashlight|torch) on)\\b.*")) {
            return new ActionCommand("flashlight_on", "", c);
        }
        if (c.matches(".*\\b(turn off (?:flashlight|flash light|flesh light|torch)|turn (?:flashlight|flash light|flesh light|torch) off|switch off (?:flashlight|torch)|disable (?:flashlight|torch)|(?:flashlight|torch) off)\\b.*")) {
            return new ActionCommand("flashlight_off", "", c);
        }

        // 3c. Volume controls
        if (c.matches(".*\\b(volume up|increase volume|louder|turn up volume|raise volume|higher volume|volume high|valume up|sound up)\\b.*")) {
            return new ActionCommand("volume_up", "", c);
        }
        if (c.matches(".*\\b(volume down|decrease volume|lower volume|turn down volume|quieter|soft volume|volume low|valume down|sound down)\\b.*")) {
            return new ActionCommand("volume_down", "", c);
        }
        if (c.matches(".*\\b(mute volume|mute sound|mute audio|silence volume|turn volume off|volume mute|mute|silent)\\b.*")) {
            return new ActionCommand("volume_mute", "", c);
        }

        // 3d. Screenshot
        if (c.matches(".*\\b(take screenshot|capture screen|screen shot|take a screenshot|screenshot|take screen shot|screen short|capture display)\\b.*")) {
            return new ActionCommand("screenshot", "", c);
        }

        // 3e. Media controls
        if (c.matches(".*\\b(play music|start music|resume music|resume song|play song)\\b.*")) {
            return new ActionCommand("media_play", "", c);
        }
        if (c.matches(".*\\b(pause music|pause song|stop music|pause audio|stop audio)\\b.*")) {
            return new ActionCommand("media_pause", "", c);
        }
        if (c.matches(".*\\b(next song|next track|skip song|skip track)\\b.*")) {
            return new ActionCommand("media_next", "", c);
        }
        if (c.matches(".*\\b(previous song|previous track|prev song|prev track)\\b.*")) {
            return new ActionCommand("media_prev", "", c);
        }

        // 4. Status Bar & Notifications
        if (c.matches(".*\\b(clear notifications?|clean notifications?|dismiss notifications?|wipe notifications?|remove notifications?)\\b.*")) {
            return new ActionCommand("clear_notifications", "", c);
        }
        if (c.matches(".*\\b(quick settings|open quick settings|show quick settings)\\b.*")) {
            return new ActionCommand("quick_settings", "", c);
        }
        if (c.matches(".*\\b(status bar|open status bar|show status bar|expand status bar|show notifications|open notifications)\\b.*")) {
            return new ActionCommand("status_bar", "", c);
        }

        // 5. Recent Apps (replacing static ajio/chrome)
        if (c.matches(".*\\b(recent apps?|recently opened apps?|open recent apps?|show recent apps?|switch to recent|previous app|last app|recents|overview)\\b.*")) {
            return new ActionCommand("recent_apps", "", c);
        }

        // App Sections & Deep Navigation (e.g. "go to reel section", "open reels", "shorts", "direct")
        if (c.matches(".*\\b(go to|open|show|navigate to)\\s+(reels?|reel section|reels section|clips)\\b.*") || 
            c.matches(".*\\b(reels?|reel section|reels section)\\b.*")) {
            return new ActionCommand("app_section", "instagram:reels", c);
        }
        if (c.matches(".*\\b(instagram|insta)\\b.*\\b(story|camera)\\b.*")) {
            return new ActionCommand("app_section", "instagram:camera", c);
        }
        if (c.matches(".*\\b(instagram|insta)\\b.*\\b(direct|dm|messages?)\\b.*")) {
            return new ActionCommand("app_section", "instagram:direct", c);
        }
        if (c.matches(".*\\b(youtube|yt)?\\s*(shorts?|short section)\\b.*")) {
            return new ActionCommand("app_section", "youtube:shorts", c);
        }

        // Floating Bubble Mode
        if (c.matches(".*\\b(floating mode|floating bubble|bubble mode|floating overlay|open floating|start floating|enable floating|turn on floating)\\b.*")) {
            return new ActionCommand("floating_mode", "", c);
        }

        // 6. Greet
        if (c.matches(".*\\b(hello|hi|hey|good morning|good evening|good afternoon)\\b.*")) {
            return new ActionCommand("greet", "", c);
        }

        // 7. Query Time
        if (c.matches(".*\\b(time|date|day|what time is it|clock)\\b.*")) {
            return new ActionCommand("query_time", "", c);
        }

        // 8. Query Capabilities
        if (c.matches(".*\\b(who are you|what can you do|capabilities|help me)\\b.*")) {
            return new ActionCommand("query_capabilities", "", c);
        }

        // 9. Close App (guaranteed close)
        if (c.matches(".*\\b(close|kill|terminate|exit|quit|shut down)\\b.*")) {
            String appName = c.replaceAll("\\b(close|kill|terminate|exit|quit|shut down|app|the|this|current)\\b", " ")
                              .replaceAll("\\s+", " ").trim();
            return new ActionCommand("close_app", appName, c);
        }

        // 10. File Operations: Create File
        if (c.matches(".*\\b(create file|make file|new file|create a file)\\b.*")) {
            String filename = c.replaceAll("\\b(create file|make file|new file|create a file|called|named|file)\\b", " ")
                               .replaceAll("\\s+", " ").trim();
            return new ActionCommand("create_file", filename, c);
        }

        // 11. File Operations: Edit File / Open File
        if (c.matches(".*\\b(edit file|modify file|open file)\\b.*")) {
            String filename = c.replaceAll("\\b(edit file|modify file|open file|called|named|file)\\b", " ")
                               .replaceAll("\\s+", " ").trim();
            return new ActionCommand("edit_file", filename, c);
        }

        // 12. File Operations: Write File / Note
        if (c.matches(".*\\b(write|type|insert|add to file|write in file|write to file)\\b.*")) {
            String content = c.replaceAll("\\b(write in file|write to file|write into file|write file|write|create note|add note|type|note|in file|to file)\\b", " ")
                              .replaceAll("\\s+", " ").trim();
            return new ActionCommand("write_file", content, c);
        }

        // 13. Open App
        if (c.matches(".*\\b(open|launch|start|run|play)\\b.*")) {
            String appName = c.replaceAll("\\b(open|launch|start|run|please|can you|could you|app|the|an|a)\\b", " ")
                              .replaceAll("\\s+", " ").trim();
            return new ActionCommand("open_app", appName, c);
        }

        // 14. Search Web & Open-Domain Questions
        if (c.matches(".*\\b(search|look up|find on web|google)\\b.*") ||
            c.matches("^(what|who|where|when|why|how|which|tell me|explain|define|meaning|calculate|translate|weather|capital|population|news)\\b.*") ||
            c.endsWith("?")) {
            String query = c.replaceAll("^(search the web for|search for|look up|search google for|google|search|find info on|what is|who is|where is|how to|why does|why is|tell me about|tell me|explain|define|meaning of)\\b", " ")
                            .replaceAll("\\s+", " ").trim();
            if (query.isEmpty()) query = c;
            return new ActionCommand("search_web", query, c);
        }

        // 15. Exit
        if (c.matches(".*\\b(shutdown|bye|goodbye|go offline|sleep)\\b.*")) {
            return new ActionCommand("exit", "", c);
        }

        // Fallback default: try launching as app or searching
        return new ActionCommand("open_app", c, c);
    }
}
