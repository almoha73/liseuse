package org.almoha.liseuse.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Book {
    public String id;
    public String title;
    public String author;
    public String filePath;
    public String coverPath;
    public int progressChar;
    public int progressSentence;
    public long addedAt;
    public int totalChars;
    public List<Bookmark> bookmarks = new ArrayList<>();

    public static class Bookmark {
        public int charOffset;
        public String label;
        public long createdAt;
    }

    public Book() {
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("author", author);
        o.put("filePath", filePath);
        o.put("coverPath", coverPath == null ? JSONObject.NULL : coverPath);
        o.put("progressChar", progressChar);
        o.put("progressSentence", progressSentence);
        o.put("addedAt", addedAt);
        o.put("totalChars", totalChars);
        JSONArray marks = new JSONArray();
        for (Bookmark m : bookmarks) {
            JSONObject j = new JSONObject();
            j.put("charOffset", m.charOffset);
            j.put("label", m.label);
            j.put("createdAt", m.createdAt);
            marks.put(j);
        }
        o.put("bookmarks", marks);
        return o;
    }

    public static Book fromJson(JSONObject o) {
        Book b = new Book();
        b.id = o.optString("id");
        b.title = o.optString("title", "Sans titre");
        b.author = o.optString("author", "");
        b.filePath = o.optString("filePath");
        b.coverPath = o.isNull("coverPath") ? null : o.optString("coverPath", null);
        b.progressChar = o.optInt("progressChar", 0);
        b.progressSentence = o.optInt("progressSentence", 0);
        b.addedAt = o.optLong("addedAt", 0);
        b.totalChars = o.optInt("totalChars", 0);
        JSONArray marks = o.optJSONArray("bookmarks");
        if (marks != null) {
            for (int i = 0; i < marks.length(); i++) {
                JSONObject j = marks.optJSONObject(i);
                if (j == null) continue;
                Bookmark m = new Bookmark();
                m.charOffset = j.optInt("charOffset", 0);
                m.label = j.optString("label", "");
                m.createdAt = j.optLong("createdAt", 0);
                b.bookmarks.add(m);
            }
        }
        return b;
    }
}
