package org.almoha.liseuse.epub;

public class Sentence {
    public final int start;
    public final int end;

    public Sentence(int start, int end) {
        this.start = start;
        this.end = end;
    }

    public String text(CharSequence full) {
        return full.subSequence(start, end).toString();
    }
}
