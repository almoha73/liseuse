package org.almoha.liseuse.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Library {
    private static final String PREFS = "library";
    private static final String KEY = "books";
    // Le service de lecture vocale écrit la progression depuis un autre thread.
    private static final Object LOCK = new Object();

    private final SharedPreferences prefs;

    public Library(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Book> books() {
        List<Book> out = new ArrayList<>();
        String raw = prefs.getString(KEY, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Book.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> Long.compare(b.addedAt, a.addedAt));
        return out;
    }

    public void save(List<Book> books) {
        JSONArray arr = new JSONArray();
        for (Book b : books) {
            try {
                arr.put(b.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs.edit().putString(KEY, arr.toString()).apply();
    }

    public void add(Book book) {
        synchronized (LOCK) {
            addLocked(book);
        }
    }

    private void addLocked(Book book) {
        List<Book> all = books();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(book.id)) {
                all.set(i, book);
                save(all);
                return;
            }
        }
        all.add(book);
        save(all);
    }

    public void updateProgress(String id, int charOffset, int sentence) {
        synchronized (LOCK) {
            List<Book> all = books();
            for (Book b : all) {
                if (b.id.equals(id)) {
                    if (b.progressChar == charOffset && b.progressSentence == sentence) return;
                    b.progressChar = charOffset;
                    b.progressSentence = sentence;
                    break;
                }
            }
            save(all);
        }
    }

    public void updateTotalChars(String id, int totalChars) {
        synchronized (LOCK) {
            List<Book> all = books();
            for (Book b : all) {
                if (b.id.equals(id)) {
                    if (b.totalChars == totalChars) return;
                    b.totalChars = totalChars;
                    break;
                }
            }
            save(all);
        }
    }

    public void updateBookmarks(String id, List<Book.Bookmark> bookmarks) {
        synchronized (LOCK) {
            List<Book> all = books();
            for (Book b : all) {
                if (b.id.equals(id)) {
                    b.bookmarks = new java.util.ArrayList<>(bookmarks);
                    break;
                }
            }
            save(all);
        }
    }

    public void remove(String id) {
        synchronized (LOCK) {
            List<Book> all = books();
            all.removeIf(b -> b.id.equals(id));
            save(all);
        }
    }

    public Book find(String id) {
        for (Book b : books()) {
            if (b.id.equals(id)) return b;
        }
        return null;
    }
}
