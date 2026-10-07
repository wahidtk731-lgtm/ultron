package com.ultron.assistant;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-Accuracy Natural Language Intent Engine & Phonetic Normalizer for Ultron Android.
 * Direct zero-dependency Java port of speech_normalizer.py and ultron.py.
 */
public class UltronIntentEngine {

    // Phonetic variants regex for "Ultron" ("all thrown", "all drone", etc.)
    private static final Pattern PHONETIC_ULTRON_PATTERN = Pattern.compile(
        "\\b(all\\s+thrown|all\\s+throne|all\\s+throw\\w*|all\\s+thr\\w+|" +
        "all\\s+drone|all\\s+drown\\w*|all\\s+drawn|all\\s+dr\\w+|" +
        "hall\\s+dr\\w+|hall\\s+thr\\w+|hall\\s+thrown|" +
        "all\\s+turn|all\\s+grown|all\\s+crown|all\\s+round|all\\s+train|all\\s+churn|all\\s+tone|" +
        "old\\s+run|out\\s+run|outrun|el\\s+tr\\w+|alter\\s+on|ultra\\s+on|" +
        "whole\\s+turn|hole\\s+turn|full\\s+turn|" +
        "altron|ultra|ultran|oltron)\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final List<String> WAKE_WORDS = Arrays.asList(
        "hey ultron", "ultron", "hey all thrown", "all thrown", "hey all drone",
        "all drone", "hey altron", "altron", "hey ultra", "ultra",
        "hey out run", "out run", "outrun", "hey all turn", "all turn"
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
        cleaned = cleaned.replaceAll("\\b(wi[- ]?fi|why[- ]?fi|wai[- ]?fai|wee[- ]?fee)\\b", "wifi");
        cleaned = cleaned.replaceAll("\\b(blue[- ]?tooth|bluetooths|blue\\s+tooths)\\b", "bluetooth");
        cleaned = cleaned.replaceAll("\\b(insta|ig)\\b", "instagram");
        cleaned = cleaned.replaceAll("\\b(reel\\s+section|reels\\s+section|reels|reel)\\b", "reels");
        cleaned = cleaned.replaceAll("\\b(short\\s+section|shorts\\s+section)\\b", "shorts");

        // 3. Common acoustic fixes
        cleaned = cleaned.replaceAll("\\b(set\\s+things|sat\\s+things|sad\\s+things|setting's)\\b", "settings");
        cleaned = cleaned.replaceAll("\\b(google\\s+crown|google\\s+crome|google\\s+chrom)\\b", "chrome");
        cleaned = cleaned.replaceAll("\\b(you\\s+tube|u\\s+tube|u-tube)\\b", "youtube");
        cleaned = cleaned.replaceAll("\\b(cal\\s+cue\\s+later|calcu\\s+later)\\b", "calculator");
        cleaned = cleaned.replaceAll("\\b(right|ride)\\s+(note|notes|file|text)\\b", "write $2");
        cleaned = cleaned.replaceAll("\\b(claws|clothes)\\s+(chrome|app|youtube)\\b", "close $2");

        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /**
     * Detects if speech contains Ultron wake word.
     */
    public boolean isWakeWord(String text) {
        if (text == null) return false;
        String norm = normalizeSpeech(text);
        if (norm.contains("ultron")) return true;
        for (String w : WAKE_WORDS) {
            if (text.toLowerCase().contains(w)) return true;
        }
        return false;
    }

    /**
     * Strips wake words from text.
     */
    public String stripWakeWords(String text) {
        if (text == null) return "";
        String norm = normalizeSpeech(text);
        String cleaned = norm.replaceAll("\\bhey\\s+ultron\\b", " ")
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

        // 1. Settings command (Theme, Voice, Assistant preferences)
        if (c.matches(".*\\b(settings|ultron settings|open settings|preferences|options|voice settings|theme settings|sound settings|customize)\\b.*")) {
            return new ActionCommand("open_settings", "", c);
        }

        // 2. Hardware Toggles: Wi-Fi (Robust multi-variant regex)
        if (c.matches(".*\\b(turn on wifi|turn wifi on|turn on the wifi|turn the wifi on|switch on wifi|switch wifi on|enable wifi|enable the wifi|start wifi|wifi on|connect wifi|connect to wifi|open wifi)\\b.*")) {
            return new ActionCommand("wifi_on", "", c);
        }
        if (c.matches(".*\\b(turn off wifi|turn wifi off|turn off the wifi|turn the wifi off|switch off wifi|switch wifi off|disable wifi|disable the wifi|stop wifi|wifi off|disconnect wifi|disconnect from wifi)\\b.*")) {
            return new ActionCommand("wifi_off", "", c);
        }

        // 3. Hardware Toggles: Bluetooth (Robust multi-variant regex)
        if (c.matches(".*\\b(turn on bluetooth|turn bluetooth on|turn on the bluetooth|turn the bluetooth on|switch on bluetooth|switch bluetooth on|enable bluetooth|enable the bluetooth|start bluetooth|bluetooth on)\\b.*")) {
            return new ActionCommand("bluetooth_on", "", c);
        }
        if (c.matches(".*\\b(turn off bluetooth|turn bluetooth off|turn off the bluetooth|turn the bluetooth off|switch off bluetooth|switch bluetooth off|disable bluetooth|disable the bluetooth|stop bluetooth|bluetooth off)\\b.*")) {
            return new ActionCommand("bluetooth_off", "", c);
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

        // 14. Search Web
        if (c.matches(".*\\b(search|look up|find on web)\\b.*")) {
            String query = c.replaceAll("\\b(search the web for|search for|look up|search)\\b", " ")
                            .replaceAll("\\s+", " ").trim();
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
