package org.almoha.liseuse;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.almoha.liseuse.epub.BookText;
import org.almoha.liseuse.epub.EpubBook;
import org.almoha.liseuse.epub.EpubParser;
import org.almoha.liseuse.epub.Fb2Parser;
import org.almoha.liseuse.model.Book;
import org.almoha.liseuse.model.Library;
import org.almoha.liseuse.ui.BookAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LibraryActivity extends AppCompatActivity implements BookAdapter.Callback {

    // Les FB2 n'ont pas de type MIME fiable : on laisse tout choisir et on détecte le format au contenu.
    private static final String[] MIMES = {"*/*"};

    private Library library;
    private BookAdapter adapter;
    private RecyclerView list;
    private View empty;
    private View progress;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris == null || uris.isEmpty()) return;
                importBooks(new ArrayList<>(uris));
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(R.string.app_name);

        library = new Library(this);
        list = findViewById(R.id.books);
        empty = findViewById(R.id.empty);
        progress = findViewById(R.id.import_progress);

        adapter = new BookAdapter(this);
        list.setLayoutManager(new GridLayoutManager(this, 2));
        list.setAdapter(adapter);

        requestNotificationPermission();
        io.execute(this::repairMisdetectedFb2);
    }

    /** Les anciennes versions importaient les FB2 comme du texte brut : on les convertit. */
    private void repairMisdetectedFb2() {
        boolean changed = false;
        for (Book book : library.books()) {
            if (book.filePath == null || !book.filePath.endsWith(".txt")) continue;
            File txt = new File(book.filePath);
            try {
                if (!".fb2".equals(detectFormat(txt, ""))) continue;
                File fb2 = new File(txt.getParentFile(), book.id + ".fb2");
                if (!txt.renameTo(fb2)) continue;
                book.filePath = fb2.getAbsolutePath();
                try {
                    applyParsed(book, Fb2Parser.parse(fb2));
                } catch (Exception e) {
                    fb2.renameTo(txt);
                    continue;
                }
                book.progressChar = 0;
                book.progressSentence = 0;
                library.add(book);
                changed = true;
            } catch (Exception ignored) {
            }
        }
        if (changed) main.post(this::refresh);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<Book> books = library.books();
        adapter.setItems(books);
        empty.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(books.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_library, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_import) {
            picker.launch(MIMES);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void importBooks(List<Uri> uris) {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            int ok = 0;
            Exception last = null;
            for (Uri uri : uris) {
                try {
                    importOne(uri);
                    ok++;
                } catch (Exception e) {
                    last = e;
                }
            }
            int finalOk = ok;
            Exception finalLast = last;
            main.post(() -> {
                progress.setVisibility(View.GONE);
                refresh();
                if (finalOk > 0) {
                    Toast.makeText(this, getString(R.string.import_done) + " (" + finalOk + ")",
                            Toast.LENGTH_SHORT).show();
                } else {
                    String msg = getString(R.string.import_failed);
                    if (finalLast != null) msg += " : " + finalLast.getMessage();
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void importOne(Uri uri) throws Exception {
        String name = queryName(uri);
        String lower = name.toLowerCase(Locale.ROOT);

        File dir = new File(getFilesDir(), "books");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("Stockage indisponible");
        String id = UUID.randomUUID().toString();
        File dest = new File(dir, id + ".tmp");

        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new Exception("Fichier illisible");
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }

        String ext = detectFormat(dest, lower);
        if (ext == null) {
            dest.delete();
            throw new Exception("Format non pris en charge (EPUB, FB2 ou TXT)");
        }
        File renamed = new File(dir, id + ext);
        if (!dest.renameTo(renamed)) throw new Exception("Stockage indisponible");
        dest = renamed;

        Book book = new Book();
        book.id = id;
        book.filePath = dest.getAbsolutePath();
        book.addedAt = System.currentTimeMillis();
        book.title = stripExtension(name);

        if (!ext.equals(".txt")) {
            try {
                applyParsed(book, ext.equals(".epub") ? EpubParser.parse(dest) : Fb2Parser.parse(dest));
            } catch (Exception e) {
                dest.delete();
                throw new Exception(ext.equals(".epub") ? "EPUB invalide" : "FB2 invalide");
            }
        } else {
            String text = decodeText(readAll(dest));
            book.totalChars = text.length();
            writeTextCache(id, text);
        }

        library.add(book);
    }

    private void applyParsed(Book book, EpubBook parsed) throws Exception {
        if (parsed.title != null && !parsed.title.trim().isEmpty()) book.title = parsed.title;
        book.author = parsed.author;
        if (parsed.cover != null) {
            File covers = new File(getFilesDir(), "covers");
            if (covers.exists() || covers.mkdirs()) {
                File cover = new File(covers, book.id + ".img");
                try (OutputStream co = new FileOutputStream(cover)) {
                    co.write(parsed.cover);
                }
                book.coverPath = cover.getAbsolutePath();
            }
        }
        book.totalChars = parsed.content.text.length();
        writeTextCache(book.id, parsed.content.text);
    }

    /** Renvoie l'extension interne (.epub, .fb2, .txt) d'après le contenu, ou null si inconnu. */
    private static String detectFormat(File f, String lowerName) throws Exception {
        byte[] head = new byte[512];
        int n;
        try (InputStream in = new java.io.FileInputStream(f)) {
            n = Math.max(0, in.read(head));
        }
        String start = new String(head, 0, n, StandardCharsets.ISO_8859_1);
        if (start.startsWith("PK")) {
            if (lowerName.endsWith(".fb2.zip") || lowerName.endsWith(".fbz")) return ".fb2";
            return ".epub";
        }
        if (start.contains("<FictionBook")) return ".fb2";
        if (lowerName.endsWith(".fb2")) return ".fb2";
        if (lowerName.endsWith(".txt") || looksLikeText(head, n)) return ".txt";
        return null;
    }

    private static boolean looksLikeText(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) return false;
        }
        return n > 0;
    }

    /** UTF-8 si valide, sinon Windows-1252 (fréquent pour les vieux .txt). */
    static String decodeText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            text = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void writeTextCache(String id, String text) throws Exception {
        File texts = new File(getFilesDir(), "texts");
        if (!texts.exists() && !texts.mkdirs()) throw new Exception("Stockage indisponible");
        File f = new File(texts, id + ".txt");
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private byte[] readAll(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    private String queryName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "livre.epub";
        return name;
    }

    private static String stripExtension(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    @Override
    public void onOpen(Book book) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        startActivity(i);
    }

    @Override
    public void onLongPress(Book book) {
        new AlertDialog.Builder(this)
                .setTitle(book.title)
                .setMessage(R.string.delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    deleteFiles(book);
                    library.remove(book.id);
                    refresh();
                })
                .show();
    }

    private void deleteFiles(Book book) {
        try {
            if (book.filePath != null) new File(book.filePath).delete();
            if (book.coverPath != null) new File(book.coverPath).delete();
            new File(new File(getFilesDir(), "texts"), book.id + ".txt").delete();
        } catch (Exception ignored) {
        }
    }
}
