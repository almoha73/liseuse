package org.almoha.liseuse.epub;

import java.util.ArrayList;
import java.util.List;

public class BookText {
    private static final int MAX_SENTENCE = 320;

    public final String text;
    public final List<Chapter> chapters;
    public final List<Sentence> sentences;

    public BookText(String text, List<Chapter> chapters) {
        this.text = text;
        this.chapters = chapters;
        this.sentences = splitSentences(text);
    }

    public int sentenceIndexAt(int charOffset) {
        int lo = 0, hi = sentences.size() - 1, res = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Sentence s = sentences.get(mid);
            if (s.end <= charOffset) {
                lo = mid + 1;
            } else {
                res = mid;
                hi = mid - 1;
            }
        }
        return res;
    }

    public Chapter chapterAt(int charOffset) {
        Chapter current = null;
        for (Chapter c : chapters) {
            if (c.charOffset <= charOffset) current = c;
            else break;
        }
        return current;
    }

    public static List<Sentence> splitSentences(String t) {
        List<Sentence> out = new ArrayList<>();
        int n = t.length();
        int start = -1;
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            if (start < 0) {
                if (!Character.isWhitespace(c)) start = i;
                continue;
            }
            boolean terminal = c == '.' || c == '!' || c == '?' || c == '\u2026';
            if (terminal) {
                int j = i + 1;
                while (j < n && isClosing(t.charAt(j))) j++;
                boolean boundary = j >= n || Character.isWhitespace(t.charAt(j));
                if (boundary && !(c == '.' && isAbbreviation(t, i))) {
                    addSentence(out, t, start, j);
                    start = -1;
                    i = j - 1;
                    continue;
                }
            }
            if (c == '\n' && i + 1 < n && t.charAt(i + 1) == '\n') {
                addSentence(out, t, start, i);
                start = -1;
            }
        }
        if (start >= 0) addSentence(out, t, start, n);
        return out;
    }

    private static void addSentence(List<Sentence> out, String t, int start, int end) {
        int s = start, e = end;
        while (s < e && Character.isWhitespace(t.charAt(s))) s++;
        while (e > s && Character.isWhitespace(t.charAt(e - 1))) e--;
        if (s >= e) return;
        if (e - s <= MAX_SENTENCE) {
            out.add(new Sentence(s, e));
            return;
        }
        int cut = s;
        for (int i = s; i < e; i++) {
            char ch = t.charAt(i);
            if ((ch == ',' || ch == ';' || ch == ':' || ch == ')') && i - s > MAX_SENTENCE / 2) {
                cut = i + 1;
            }
            if (i - s >= MAX_SENTENCE - 40) break;
        }
        if (cut <= s) {
            for (int i = Math.min(e - 1, s + MAX_SENTENCE); i > s; i--) {
                if (Character.isWhitespace(t.charAt(i))) {
                    cut = i;
                    break;
                }
            }
        }
        if (cut <= s || cut >= e) {
            out.add(new Sentence(s, e));
            return;
        }
        addSentence(out, t, s, cut);
        addSentence(out, t, cut, e);
    }

    private static boolean isClosing(char c) {
        return c == '"' || c == '\'' || c == '\u00BB' || c == '\u201D' || c == ')'
                || c == ']' || c == '\u2026' || c == '!' || c == '?' || c == '.';
    }

    private static boolean isAbbreviation(String t, int dot) {
        int i = dot - 1;
        while (i >= 0 && (Character.isLetter(t.charAt(i)) || t.charAt(i) == '.')) i--;
        String token = t.substring(i + 1, dot).replace(".", "");
        if (token.isEmpty() || token.length() > 4) return false;
        char first = token.charAt(0);
        if (Character.isUpperCase(first) && token.length() == 1) return true;
        String low = token.toLowerCase();
        switch (low) {
            case "m":
            case "mm":
            case "me":
            case "mme":
            case "mlles":
            case "mlle":
            case "dr":
            case "st":
            case "ste":
            case "mr":
            case "mrs":
            case "no":
            case "cf":
            case "p":
            case "pp":
            case "vol":
            case "chap":
            case "fig":
            case "av":
            case "ap":
            case "env":
                return true;
            default:
                return token.length() >= 2 && token.equals(token.toUpperCase());
        }
    }
}
