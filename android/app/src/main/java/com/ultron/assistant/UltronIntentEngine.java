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

        // 2. Common acoustic fixes
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
     * Splits compound instructions like 'open chrome and tell me the time'.
     */
    public List<String> splitCompoundCommands(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        String body = stripWakeWords(text);
        String splitRegex = "\\s+(?:and\\s+then|then|after\\s+that|and)\\s+(?=(?:open|launch|run|start|close|kill|write|create|search|google|what|tell|how|play)\\b)";
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

        // 1. Greet
        if (c.matches(".*\\b(hello|hi|hey|good morning|good evening|good afternoon)\\b.*")) {
            return new ActionCommand("greet", "", c);
        }

        // 2. Query Time
        if (c.matches(".*\\b(time|date|day|what time is it|clock)\\b.*")) {
            return new ActionCommand("query_time", "", c);
        }

        // 3. Query Capabilities
        if (c.matches(".*\\b(who are you|what can you do|capabilities|help me)\\b.*")) {
            return new ActionCommand("query_capabilities", "", c);
        }

        // 4. Open App
        if (c.matches(".*\\b(open|launch|start|run|play)\\b.*")) {
            String appName = c.replaceAll("\\b(open|launch|start|run|please|can you|could you|app|the|an|a)\\b", " ")
                              .replaceAll("\\s+", " ").trim();
            return new ActionCommand("open_app", appName, c);
        }

        // 5. Close App
        if (c.matches(".*\\b(close|kill|terminate|exit|quit)\\b.*")) {
            String appName = c.replaceAll("\\b(close|kill|terminate|exit|quit|app|the)\\b", " ")
                              .replaceAll("\\s+", " ").trim();
            return new ActionCommand("close_app", appName, c);
        }

        // 6. Search Web
        if (c.matches(".*\\b(search|google|look up|find on web)\\b.*")) {
            String query = c.replaceAll("\\b(search google for|search the web for|search for|google|look up|search)\\b", " ")
                            .replaceAll("\\s+", " ").trim();
            return new ActionCommand("search_web", query, c);
        }

        // 7. Write File / Note
        if (c.matches(".*\\b(write|create note|add note|type)\\b.*")) {
            String content = c.replaceAll("\\b(write|create note|add note|type|note|in file|to file)\\b", " ")
                              .replaceAll("\\s+", " ").trim();
            return new ActionCommand("write_file", content, c);
        }

        // 8. Exit
        if (c.matches(".*\\b(shutdown|bye|goodbye|go offline|sleep)\\b.*")) {
            return new ActionCommand("exit", "", c);
        }

        // Fallback default: try launching as app or searching
        return new ActionCommand("open_app", c, c);
    }
}
