package org.almoha.liseuse.epub;

public class Chapter {
    public final String title;
    public final int charOffset;

    public Chapter(String title, int charOffset) {
        this.title = title;
        this.charOffset = charOffset;
    }
}
