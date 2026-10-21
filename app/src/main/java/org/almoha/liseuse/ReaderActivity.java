package org.almoha.liseuse;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Layout;
import android.util.Log;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import org.almoha.liseuse.epub.BookText;
import org.almoha.liseuse.epub.EpubBook;
import org.almoha.liseuse.epub.EpubParser;
import org.almoha.liseuse.epub.Fb2Parser;
import org.almoha.liseuse.epub.Sentence;
import org.almoha.liseuse.model.Book;
import org.almoha.liseuse.model.Library;
import org.almoha.liseuse.tts.TtsService;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReaderActivity extends AppCompatActivity implements TtsService.Listener {

    public static final String EXTRA_BOOK_ID = "book_id";

    private static final float[] SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private static final int[] BG = {0xFFF6F1E7, 0xFFE8DCC0, 0xFF101010};
    private static final int[] FG = {0xFF2B2B2B, 0xFF3A2F22, 0xFFC9C9C9};
    private static final int[] THEME_NAMES = {R.string.theme_light, R.string.theme_sepia, R.string.theme_dark};
    private static final int[] HL_NAMES = {R.string.hl_yellow, R.string.hl_green, R.string.hl_blue,
            R.string.hl_pink, R.string.hl_orange, R.string.hl_grey};
    // Une teinte par thème (ligne) et par couleur de surlignage (colonne), pour garder un bon contraste.
    private static final int[][] HL = {
            {0xFFF2D98D, 0xFFC5E3B5, 0xFFBFD8F0, 0xFFF2C4D6, 0xFFF5CB9E, 0xFFDAD5CB},
            {0xFFDCC27A, 0xFFB5CF9B, 0xFFB0C6DB, 0xFFE0B0BE, 0xFFE8B783, 0xFFCBBFA5},
            {0xFF5C4F1E, 0xFF2F4F2A, 0xFF2E3B52, 0xFF56303F, 0xFF5E3D1E, 0xFF3A3A3A},
    };
    // Au-delà, un TextView unique devient trop lent à dessiner (ANR sur les gros chapitres).
    private static final int PAGE_CHARS = 12000;

    private Book book;
    private Library library;
    private BookText bt;
    private String textPath;

    private final List<Integer> pages = new ArrayList<>();
    private int page;
    private int currentSentence = -1;
    private Spannable content;
    private String chapterText = "";
    private BackgroundColorSpan highlight;

    private TextView textView;
    private ScrollView scroll;
    private ProgressBar progress;
    private ImageButton btnPlay;
    private ProgressBar playLoading;
    private Button btnPagePrev;
    private TextView timelineChapter;
    private TextView timelineInfo;
    private SeekBar timelineBar;
    private boolean draggingTimeline;
    private Button btnPageNext;
    private Button btnSpeed;

    private TtsService tts;
    private boolean bound;

    private SharedPreferences prefs;
    private float fontSize;
    private int theme;
    private int highlightColor;
    private float speed;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            tts = ((TtsService.LocalBinder) service).service();
            tts.setListener(ReaderActivity.this);
            bound = true;
            syncWithService();
            updateSpeedLabel();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            tts = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        toolbar.setTitle("");

        textView = findViewById(R.id.text);
        scroll = findViewById(R.id.scroll);
        progress = findViewById(R.id.progress);
        btnPlay = findViewById(R.id.btn_play);
        playLoading = findViewById(R.id.play_loading);
        btnSpeed = findViewById(R.id.btn_speed);
        btnPagePrev = findViewById(R.id.btn_page_prev);
        btnPageNext = findViewById(R.id.btn_page_next);
        timelineChapter = findViewById(R.id.timeline_chapter);
        timelineInfo = findViewById(R.id.timeline_info);
        timelineBar = findViewById(R.id.timeline_bar);

        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        fontSize = prefs.getFloat("fontSize", 19f);
        theme = prefs.getInt("theme", 0);
        highlightColor = prefs.getInt("highlightColor", 0);
        speed = prefs.getFloat("speed", 1f);
        textView.setTextSize(fontSize);
        applyTheme();

        library = new Library(this);
        String id = getIntent().getStringExtra(EXTRA_BOOK_ID);
        book = id == null ? null : library.find(id);
        if (book == null) {
            finish();
            return;
        }
        textPath = new File(new File(getFilesDir(), "texts"), book.id + ".txt").getAbsolutePath();

        btnPlay.setOnClickListener(v -> togglePlay());
        findViewById(R.id.btn_prev).setOnClickListener(v -> jumpSeconds(-10));
        findViewById(R.id.btn_next).setOnClickListener(v -> jumpSeconds(10));
        findViewById(R.id.btn_prev).setOnLongClickListener(v -> {
            stepSentence(-1);
            return true;
        });
        findViewById(R.id.btn_next).setOnLongClickListener(v -> {
            stepSentence(1);
            return true;
        });
        btnSpeed.setOnClickListener(v -> cycleSpeed());
        btnPagePrev.setOnClickListener(v -> goToPage(page - 1));
        btnPageNext.setOnClickListener(v -> goToPage(page + 1));
        setupTapToSelect();
        setupTimeline();

        startService(new Intent(this, TtsService.class));
        loadBook();
    }

    private void loadBook() {
        progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                BookText result;
                File file = new File(book.filePath);
                String lower = file.getName().toLowerCase();
                if (lower.endsWith(".epub") || lower.endsWith(".fb2")) {
                    EpubBook parsed = lower.endsWith(".epub") ? EpubParser.parse(file) : Fb2Parser.parse(file);
                    result = parsed.content;
                } else {
                    // Même texte que celui lu par la synthèse vocale, pour que les phrases correspondent.
                    String text = readFile(new File(textPath));
                    List<org.almoha.liseuse.epub.Chapter> chapters = new ArrayList<>();
                    chapters.add(new org.almoha.liseuse.epub.Chapter("Texte", 0));
                    result = new BookText(text, chapters);
                }
                BookText finalResult = result;
                main.post(() -> onBookReady(finalResult));
            } catch (Exception e) {
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Lecture impossible : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void onBookReady(BookText result) {
        bt = result;
        if (book.totalChars != bt.text.length()) {
            book.totalChars = bt.text.length();
            library.updateTotalChars(book.id, book.totalChars);
        }
        buildPages();
        page = pageForOffset(book.progressChar);
        // La phrase où l'on s'était arrêté est surlignée : ▶ reprend exactement là.
        currentSentence = bt.sentences.isEmpty() ? -1 : bt.sentenceIndexAt(book.progressChar);
        renderChapter();
        progress.setVisibility(View.GONE);
        scroll.post(() -> restoreScroll(book.progressChar));
        syncWithService();
    }

    /** Si la synthèse vocale lit (ou a lu) ce livre, on se cale sur sa position. */
    private void syncWithService() {
        if (tts == null || bt == null || !book.id.equals(tts.bookId())) return;
        int idx = tts.currentIndex();
        if (idx >= 0 && idx < bt.sentences.size()) highlight(idx, true);
        updatePlayButton(tts.isPlaying());
        showLoading(tts.isLoading());
    }

    /** Barre d'avancement : glisser pour aller n'importe où dans le livre. */
    private void setupTimeline() {
        timelineBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && bt != null) showTimeline(offsetForBar(value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                draggingTimeline = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                draggingTimeline = false;
                if (bt != null) goToOffset(offsetForBar(bar.getProgress()));
            }
        });
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (!isPlayingThis()) updateTimeline();
        });
    }

    private int offsetForBar(int value) {
        return (int) Math.min(bt.text.length() - 1, (long) bt.text.length() * value / timelineBar.getMax());
    }

    /** Met à jour la barre d'après la position courante (phrase lue, ou écran). */
    private void updateTimeline() {
        if (bt == null || draggingTimeline || bt.text.isEmpty()) return;
        int pos = isPlayingThis() && currentSentence >= 0 && currentSentence < bt.sentences.size()
                ? bt.sentences.get(currentSentence).start : currentPosition();
        timelineBar.setProgress((int) ((long) pos * timelineBar.getMax() / bt.text.length()));
        showTimeline(pos);
    }

    private void showTimeline(int pos) {
        org.almoha.liseuse.epub.Chapter ch = bt.chapterAt(pos);
        timelineChapter.setText(ch != null ? ch.title : "");
        int pct = (int) (100L * pos / bt.text.length());
        int cps = tts != null ? tts.charsFor(1) : Math.round(14 * speed);
        String left = formatDuration((bt.text.length() - pos) / Math.max(1, cps));
        timelineInfo.setText(pct + " % \u00B7 " + getString(R.string.time_left, left));
    }

    private static String formatDuration(long seconds) {
        long minutes = Math.max(1, Math.round(seconds / 60.0));
        if (minutes < 60) return minutes + " min";
        return (minutes / 60) + " h " + String.format(java.util.Locale.FRANCE, "%02d", minutes % 60);
    }

    /** Toucher une phrase la sélectionne ; pendant la lecture, la voix y saute directement. */
    private void setupTapToSelect() {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                Layout l = textView.getLayout();
                if (bt == null || l == null) return false;
                int y = (int) e.getY() - textView.getTotalPaddingTop();
                float x = e.getX() - textView.getTotalPaddingLeft();
                int offset = l.getOffsetForHorizontal(l.getLineForVertical(Math.max(0, y)), x);
                int sentence = bt.sentenceIndexAt(chapterStart() + offset);
                applyHighlight(sentence, false);
                if (tts != null && tts.isPlaying() && book.id.equals(tts.bookId())) {
                    tts.seekToSentence(sentence);
                }
                return true;
            }
        });
        textView.setOnTouchListener((v, e) -> {
            detector.onTouchEvent(e);
            return true;
        });
    }

    /** Position à retenir : la phrase sélectionnée si elle est à l'écran, sinon le haut de l'écran. */
    private int currentPosition() {
        if (currentSentence >= 0 && currentSentence < bt.sentences.size() && isOnScreen(currentSentence)) {
            return bt.sentences.get(currentSentence).start;
        }
        return chapterStart() + visibleOffset();
    }

    private boolean isOnScreen(int sentenceIndex) {
        Sentence s = bt.sentences.get(sentenceIndex);
        if (s.start < chapterStart() || s.start >= chapterEnd()) return false;
        Layout l = textView.getLayout();
        if (l == null) return true;
        int top = textTop() + l.getLineTop(l.getLineForOffset(s.start - chapterStart()));
        int y = scroll.getScrollY();
        return top >= y && top < y + scroll.getHeight();
    }

    /** Phrase précédente/suivante, que la voix soit en cours ou non. */
    private void stepSentence(int delta) {
        if (bt == null || bt.sentences.isEmpty()) return;
        int from = isPlayingThis() ? tts.currentIndex() : bt.sentenceIndexAt(currentPosition());
        moveToSentence(from + delta);
    }

    /**
     * Saut d'environ {@code seconds} secondes. La synthèse vocale n'a pas de minutage :
     * on convertit la durée en caractères d'après la vitesse de lecture mesurée.
     */
    private void jumpSeconds(int seconds) {
        if (bt == null || bt.sentences.isEmpty()) return;
        int from = isPlayingThis() ? tts.currentIndex() : bt.sentenceIndexAt(currentPosition());
        int chars = tts != null ? tts.charsFor(Math.abs(seconds)) : Math.round(14 * speed * Math.abs(seconds));
        int startOffset = bt.sentences.get(from).start;
        int targetOffset = seconds > 0 ? startOffset + chars : startOffset - chars;
        targetOffset = Math.max(0, Math.min(bt.text.length() - 1, targetOffset));
        // Début de phrase le plus proche du point visé.
        int target = bt.sentenceIndexAt(targetOffset);
        if (target + 1 < bt.sentences.size()
                && bt.sentences.get(target + 1).start - targetOffset < targetOffset - bt.sentences.get(target).start) {
            target++;
        }
        if (target == from) target += seconds > 0 ? 1 : -1;
        moveToSentence(target);
    }

    private boolean isPlayingThis() {
        return tts != null && tts.isPlaying() && book.id.equals(tts.bookId());
    }

    private void moveToSentence(int index) {
        int target = Math.max(0, Math.min(bt.sentences.size() - 1, index));
        if (isPlayingThis()) {
            tts.seekToSentence(target);
        } else {
            highlight(target, true);
        }
    }

    private void goToOffset(int abs) {
        page = pageForOffset(abs);
        currentSentence = bt.sentenceIndexAt(abs);
        renderChapter();
        scrollToOffset(bt.sentences.get(currentSentence).start - chapterStart());
        if (tts != null && tts.isPlaying() && book.id.equals(tts.bookId())) tts.seekToSentence(currentSentence);
    }

    /** Découpe les chapitres trop longs en pages, de préférence entre deux paragraphes. */
    private void buildPages() {
        pages.clear();
        int count = bt.chapters.size();
        if (count == 0) pages.add(0);
        for (int i = 0; i < count; i++) {
            int s = bt.chapters.get(i).charOffset;
            int e = i + 1 < count ? bt.chapters.get(i + 1).charOffset : bt.text.length();
            pages.add(s);
            while (e - s > PAGE_CHARS * 3 / 2) {
                int cut = bt.text.lastIndexOf("\n\n", s + PAGE_CHARS);
                if (cut > s + PAGE_CHARS / 2) {
                    cut += 2;
                } else {
                    cut = bt.sentences.get(bt.sentenceIndexAt(s + PAGE_CHARS)).start;
                    if (cut <= s || cut >= e) cut = s + PAGE_CHARS;
                }
                pages.add(cut);
                s = cut;
            }
        }
    }

    private int pageForOffset(int offset) {
        int idx = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i) <= offset) idx = i;
            else break;
        }
        return idx;
    }

    private int chapterStart() {
        return pages.get(page);
    }

    private int chapterEnd() {
        return page + 1 < pages.size() ? pages.get(page + 1) : bt.text.length();
    }

    private void goToPage(int p) {
        if (bt == null || p < 0 || p >= pages.size()) return;
        page = p;
        currentSentence = bt.sentenceIndexAt(chapterStart());
        renderChapter();
        if (tts != null && tts.isPlaying()) tts.seekToSentence(currentSentence);
    }

    private void renderChapter() {
        int start = chapterStart();
        int end = chapterEnd();
        chapterText = bt.text.substring(start, end);
        // Le surlignage initial est posé avant setText : posé après, il n'est pas toujours dessiné.
        android.text.SpannableString initial = new android.text.SpannableString(chapterText);
        highlight = null;
        if (currentSentence >= 0 && currentSentence < bt.sentences.size()) {
            Sentence s = bt.sentences.get(currentSentence);
            int a = Math.max(0, s.start - start);
            int b = Math.min(chapterText.length(), s.end - start);
            if (a < b) {
                highlight = new BackgroundColorSpan(HL[theme][highlightColor]);
                initial.setSpan(highlight, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        textView.setText(initial, TextView.BufferType.SPANNABLE);
        content = (Spannable) textView.getText();
        btnPagePrev.setVisibility(page > 0 ? View.VISIBLE : View.GONE);
        btnPageNext.setVisibility(page + 1 < pages.size() ? View.VISIBLE : View.GONE);
        scroll.scrollTo(0, 0);
        scroll.post(this::updateTimeline);
    }

    private void restoreScroll(int absOffset) {
        Layout l = textView.getLayout();
        if (l == null) return;
        int rel = absOffset - chapterStart();
        rel = Math.max(0, Math.min(rel, content.length()));
        int line = l.getLineForOffset(rel);
        scroll.scrollTo(0, Math.max(0, textTop() + l.getLineTop(line) - scroll.getHeight() / 3));
    }

    private void highlight(int sentenceIndex, boolean scrollTo) {
        if (bt == null || sentenceIndex < 0 || sentenceIndex >= bt.sentences.size()) return;
        Sentence s = bt.sentences.get(sentenceIndex);
        if (s.start < chapterStart() || s.start >= chapterEnd()) {
            currentSentence = sentenceIndex;
            page = pageForOffset(s.start);
            renderChapter();
        }
        applyHighlight(sentenceIndex, scrollTo);
    }

    private void applyHighlight(int sentenceIndex, boolean scrollTo) {
        if (content == null) return;
        Sentence s = bt.sentences.get(sentenceIndex);
        int a = Math.max(0, s.start - chapterStart());
        int b = Math.min(content.length(), s.end - chapterStart());
        if (a >= b) return;
        // On déplace le surlignage sans reconstruire le texte : pas de nouvelle mise en page.
        if (highlight != null) content.removeSpan(highlight);
        highlight = new BackgroundColorSpan(HL[theme][highlightColor]);
        content.setSpan(highlight, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (scrollTo) scrollToOffset(a);
        currentSentence = sentenceIndex;
        updateTimeline();
    }

    private void scrollToOffset(int rel) {
        scroll.post(() -> {
            Layout l = textView.getLayout();
            if (l == null) return;
            int line = l.getLineForOffset(Math.max(0, Math.min(rel, content.length())));
            int target = Math.max(0, textTop() + l.getLineTop(line) - scroll.getHeight() / 3);
            scroll.smoothScrollTo(0, target);
        });
    }

    /** Position du début du texte dans la zone défilante (sous le bouton « Page précédente »). */
    private int textTop() {
        return textView.getTop() + textView.getTotalPaddingTop();
    }

    private int visibleOffset() {
        Layout l = textView.getLayout();
        if (l == null) return 0;
        int y = scroll.getScrollY() - textTop();
        int line = l.getLineForVertical(Math.max(0, y));
        return Math.max(0, l.getLineStart(line));
    }

    private void togglePlay() {
        if (bt == null) return;
        if (tts == null) {
            Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (tts.isPlaying() && book.id.equals(tts.bookId())) {
            tts.pause();
        } else {
            tts.playFrom(book.id, textPath, Math.max(0, bt.sentenceIndexAt(currentPosition())));
            updatePlayButton(true);
        }
    }

    private void cycleSpeed() {
        int idx = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - speed) < 0.01f) {
                idx = i;
                break;
            }
        }
        speed = SPEEDS[(idx + 1) % SPEEDS.length];
        prefs.edit().putFloat("speed", speed).apply();
        if (tts != null) tts.setSpeed(speed);
        updateSpeedLabel();
        updateTimeline();
    }

    private void updateSpeedLabel() {
        String v = String.format(java.util.Locale.FRANCE, "%.2f", speed);
        v = v.replaceAll("0+$", "").replaceAll(",$", "");
        if (btnSpeed != null) btnSpeed.setText(v + "×");
    }

    /** Petit cercle à la place de ▶/⏸ tant que la voix ne s'entend pas ; le bouton reste actif (annule). */
    private void showLoading(boolean loading) {
        if (playLoading == null || btnPlay == null) return;
        playLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPlay.setImageAlpha(loading ? 0 : 255);
        btnPlay.setContentDescription(getString(loading ? R.string.loading_voice : R.string.pause));
    }

    private void updatePlayButton(boolean playing) {
        if (btnPlay == null) return;
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        btnPlay.setContentDescription(getString(playing ? R.string.pause : R.string.play));
    }

    private void applyTheme() {
        getWindow().setBackgroundDrawable(new ColorDrawable(BG[theme]));
        View root = findViewById(android.R.id.content);
        if (root != null) root.setBackgroundColor(BG[theme]);
        scroll.setBackgroundColor(BG[theme]);
        textView.setTextColor(FG[theme]);
        stylePageButton(btnPagePrev);
        stylePageButton(btnPageNext);
        recolorHighlight();
    }

    /** Bouton « page » discret mais lisible sur chaque fond : texte et contour de la couleur du texte. */
    private void stylePageButton(Button b) {
        float dp = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setCornerRadius(24 * dp);
        pill.setColor((FG[theme] & 0x00FFFFFF) | 0x14000000);
        pill.setStroke(Math.round(dp), (FG[theme] & 0x00FFFFFF) | 0x66000000);
        b.setBackground(pill);
        b.setTextColor(FG[theme]);
    }

    /** Change la couleur du surlignage sans reconstruire la page (on garde la position). */
    private void recolorHighlight() {
        if (content == null || highlight == null) return;
        int a = content.getSpanStart(highlight);
        int b = content.getSpanEnd(highlight);
        content.removeSpan(highlight);
        if (a < 0 || b <= a) {
            highlight = null;
            return;
        }
        highlight = new BackgroundColorSpan(HL[theme][highlightColor]);
        content.setSpan(highlight, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void saveProgress() {
        if (bt == null) return;
        int abs;
        int sentence;
        if (tts != null && tts.isPlaying() && book.id.equals(tts.bookId())
                && currentSentence >= 0 && currentSentence < bt.sentences.size()) {
            sentence = currentSentence;
            abs = bt.sentences.get(sentence).start;
        } else {
            abs = currentPosition();
            sentence = bt.sentenceIndexAt(abs);
        }
        library.updateProgress(book.id, abs, sentence);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, TtsService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyTheme();
    }

    @Override
    protected void onPause() {
        saveProgress();
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (bound && tts != null) {
            tts.clearListener(this);
            unbindService(connection);
            bound = false;
            tts = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_reader, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_chapters) {
            showChapters();
            return true;
        }
        if (id == R.id.action_add_bookmark) {
            addBookmark();
            return true;
        }
        if (id == R.id.action_bookmarks) {
            showBookmarks();
            return true;
        }
        if (id == R.id.action_theme) {
            showThemes();
            return true;
        }
        if (id == R.id.action_highlight) {
            showHighlightColors();
            return true;
        }
        if (id == R.id.action_font_up) {
            changeFont(1);
            return true;
        }
        if (id == R.id.action_font_down) {
            changeFont(-1);
            return true;
        }
        if (id == R.id.action_voice) {
            showVoices();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Pastille « Aa » qui montre le rendu d'un choix (fond + texte). */
    private CharSequence swatch(int bg, int fg, String label) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("  Aa  ");
        sb.setSpan(new BackgroundColorSpan(bg), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(fg), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("   ").append(label);
        return sb;
    }

    private void showThemes() {
        CharSequence[] items = new CharSequence[BG.length];
        for (int i = 0; i < items.length; i++) items[i] = swatch(BG[i], FG[i], getString(THEME_NAMES[i]));
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme)
                .setSingleChoiceItems(items, theme, (d, which) -> {
                    theme = which;
                    prefs.edit().putInt("theme", theme).apply();
                    applyTheme();
                    d.dismiss();
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showHighlightColors() {
        CharSequence[] items = new CharSequence[HL_NAMES.length];
        for (int i = 0; i < items.length; i++) items[i] = swatch(HL[theme][i], FG[theme], getString(HL_NAMES[i]));
        new AlertDialog.Builder(this)
                .setTitle(R.string.highlight_color)
                .setSingleChoiceItems(items, highlightColor, (d, which) -> {
                    highlightColor = which;
                    prefs.edit().putInt("highlightColor", highlightColor).apply();
                    recolorHighlight();
                    d.dismiss();
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void changeFont(int delta) {
        fontSize = Math.max(13f, Math.min(30f, fontSize + delta));
        prefs.edit().putFloat("fontSize", fontSize).apply();
        textView.setTextSize(fontSize);
    }

    private void showChapters() {
        if (bt == null) return;
        String[] titles = new String[bt.chapters.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = bt.chapters.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle(R.string.chapters)
                .setItems(titles, (d, which) -> {
                    int offset = bt.chapters.get(which).charOffset;
                    page = pageForOffset(offset);
                    currentSentence = bt.sentenceIndexAt(offset);
                    renderChapter();
                    if (tts != null && book.id.equals(tts.bookId())) tts.seekToSentence(currentSentence);
                })
                .show();
    }

    private void addBookmark() {
        if (bt == null) return;
        int abs = currentPosition();
        for (Book.Bookmark existing : book.bookmarks) {
            if (existing.charOffset == abs) {
                Toast.makeText(this, R.string.bookmark_exists, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Book.Bookmark m = new Book.Bookmark();
        m.charOffset = abs;
        m.createdAt = System.currentTimeMillis();
        String snippet = bt.sentences.isEmpty() ? "" : bt.sentences.get(bt.sentenceIndexAt(abs)).text(bt.text);
        if (snippet.length() > 70) snippet = snippet.substring(0, 70).trim() + "\u2026";
        org.almoha.liseuse.epub.Chapter ch = bt.chapterAt(abs);
        m.label = (ch != null ? ch.title + " \u2014 " : "") + "\u00AB " + snippet + " \u00BB";
        book.bookmarks.add(m);
        java.util.Collections.sort(book.bookmarks, (a, b) -> Integer.compare(a.charOffset, b.charOffset));
        library.updateBookmarks(book.id, book.bookmarks);
        Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show();
    }

    private void showBookmarks() {
        if (bt == null) return;
        if (book.bookmarks.isEmpty()) {
            Toast.makeText(this, R.string.no_bookmarks, Toast.LENGTH_LONG).show();
            return;
        }
        final AlertDialog[] dialog = new AlertDialog[1];
        android.widget.ArrayAdapter<Book.Bookmark> adapter = new android.widget.ArrayAdapter<Book.Bookmark>(
                this, R.layout.item_bookmark, book.bookmarks) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull android.view.ViewGroup parent) {
                View v = convertView != null ? convertView
                        : getLayoutInflater().inflate(R.layout.item_bookmark, parent, false);
                Book.Bookmark m = getItem(position);
                ((TextView) v.findViewById(R.id.bookmark_title)).setText(bookmarkTitle(m));
                ((TextView) v.findViewById(R.id.bookmark_snippet)).setText(bookmarkSnippet(m));
                v.findViewById(R.id.bookmark_delete).setOnClickListener(b -> confirmDeleteBookmark(m, this, dialog[0]));
                return v;
            }
        };
        dialog[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.bookmarks)
                .setAdapter(adapter, (d, which) -> goToOffset(book.bookmarks.get(which).charOffset))
                .setNegativeButton(R.string.close, null)
                .create();
        dialog[0].getListView().setDivider(new ColorDrawable(0x33808080));
        dialog[0].getListView().setDividerHeight(1);
        dialog[0].show();
    }

    private String bookmarkTitle(Book.Bookmark m) {
        org.almoha.liseuse.epub.Chapter ch = bt.chapterAt(m.charOffset);
        int pct = bt.text.isEmpty() ? 0 : (int) (100L * m.charOffset / bt.text.length());
        String title = ch != null ? ch.title : getString(R.string.bookmark);
        return pct + " % \u00B7 " + title;
    }

    private String bookmarkSnippet(Book.Bookmark m) {
        if (bt.sentences.isEmpty()) return "";
        int i = bt.sentenceIndexAt(m.charOffset);
        String text = bt.sentences.get(i).text(bt.text);
        if (i + 1 < bt.sentences.size() && text.length() < 120) text += " " + bt.sentences.get(i + 1).text(bt.text);
        return text.replace('\n', ' ');
    }

    private void confirmDeleteBookmark(Book.Bookmark m, android.widget.ArrayAdapter<Book.Bookmark> adapter,
                                       AlertDialog list) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.bookmark_delete_confirm)
                .setMessage(bookmarkSnippet(m))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    book.bookmarks.remove(m);
                    library.updateBookmarks(book.id, book.bookmarks);
                    adapter.notifyDataSetChanged();
                    if (book.bookmarks.isEmpty() && list != null) list.dismiss();
                })
                .show();
    }

    private boolean pendingVoiceDialog;

    private String engineLabel(String pkg) {
        if (tts != null) {
            for (TtsService.EngineInfo e : tts.engines()) {
                if (e.pkg.equals(pkg)) return e.label;
            }
        }
        return pkg;
    }

    private void showVoices() {
        if (tts == null) {
            Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!tts.isReady()) {
            pendingVoiceDialog = true;
            return;
        }
        String engine = tts.currentEngine();
        String title = getString(R.string.voice) + " \u2014 " + engineLabel(engine);
        List<TtsService.VoiceInfo> voices = tts.frenchVoices();
        if (voices.isEmpty() && getPackageName().equals(engine)) {
            offerSupertonicDownload();
            return;
        }
        if (voices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(getString(R.string.no_french_voice_in_engine, engineLabel(engine)))
                    .setPositiveButton(R.string.open_engine, (d, w) -> openEngine(engine))
                    .setNeutralButton(R.string.change_engine, (d, w) -> showEngines())
                    .setNegativeButton(R.string.close, null)
                    .show();
            return;
        }
        String current = tts.currentVoice();
        CharSequence[] labels = new CharSequence[voices.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = voices.get(i).label;
            if (voices.get(i).name.equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    tts.setVoice(voices.get(which).name);
                    if (tts.isPlaying()) {
                        // La phrase en cours est relue avec la nouvelle voix.
                        tts.seekToSentence(tts.currentIndex());
                    } else {
                        tts.preview(getString(R.string.voice_sample));
                    }
                })
                .setNeutralButton(R.string.change_engine, (d, w) -> showEngines())
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void offerSupertonicDownload() {
        if (org.almoha.liseuse.tts.SupertonicDownloader.isRunning()) {
            showSupertonicDownload();
            return;
        }
        String message = getString(R.string.supertonic_download_message,
                org.almoha.liseuse.tts.SupertonicDownloader.SIZE / 1_000_000);
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm != null && cm.isActiveNetworkMetered()) message += "\n\n" + getString(R.string.supertonic_download_metered);
        new AlertDialog.Builder(this)
                .setTitle(R.string.supertonic_engine)
                .setMessage(message)
                .setPositiveButton(R.string.supertonic_download, (d, w) -> showSupertonicDownload())
                .setNeutralButton(R.string.change_engine, (d, w) -> showEngines())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** Téléchargement du modèle avec barre de progression ; il continue si l'on ferme la fenêtre. */
    private void showSupertonicDownload() {
        View view = getLayoutInflater().inflate(R.layout.dialog_download, null);
        ProgressBar bar = view.findViewById(R.id.download_bar);
        TextView label = view.findViewById(R.id.download_label);
        final org.almoha.liseuse.tts.SupertonicDownloader[] job = new org.almoha.liseuse.tts.SupertonicDownloader[1];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.supertonic_downloading)
                .setView(view)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    if (job[0] != null) job[0].cancel();
                })
                .setPositiveButton(R.string.background, null)
                .create();
        org.almoha.liseuse.tts.SupertonicDownloader.Listener l = new org.almoha.liseuse.tts.SupertonicDownloader.Listener() {
            @Override
            public void onProgress(int percent, String step) {
                bar.setProgress(percent);
                label.setText(step + " \u2026 " + percent + " %");
            }

            @Override
            public void onDone() {
                if (dialog.isShowing()) dialog.dismiss();
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(ReaderActivity.this, R.string.supertonic_ready, Toast.LENGTH_LONG).show();
                showVoices();
            }

            @Override
            public void onError(String message) {
                if (dialog.isShowing()) dialog.dismiss();
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(ReaderActivity.this)
                        .setTitle(R.string.supertonic_download_failed)
                        .setMessage(message)
                        .setPositiveButton(R.string.retry, (d, w) -> showSupertonicDownload())
                        .setNegativeButton(R.string.close, null)
                        .show();
            }
        };
        dialog.setOnDismissListener(d -> {
            if (job[0] != null) job[0].detach(l);
        });
        dialog.show();
        job[0] = org.almoha.liseuse.tts.SupertonicDownloader.start(this, l);
    }

    /** Choix de l'appli de synthèse vocale (Google, SherpaTTS, Acapela…). */
    private void showEngines() {
        if (tts == null) return;
        List<TtsService.EngineInfo> engines = tts.engines();
        String current = tts.currentEngine();
        CharSequence[] labels = new CharSequence[engines.size()];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = engines.get(i).label;
            if (engines.get(i).pkg.equals(current)) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.change_engine)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    String pkg = engines.get(which).pkg;
                    if (pkg.equals(current)) {
                        showVoices();
                        return;
                    }
                    updatePlayButton(false);
                    pendingVoiceDialog = true;
                    tts.setEngine(pkg);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openEngine(String pkg) {
        Intent install = new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(pkg);
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        try {
            startActivity(install);
        } catch (Exception e) {
            if (launch != null) startActivity(launch);
        }
    }

    @Override
    public void onSentence(int index) {
        currentSentence = index;
        main.post(() -> highlight(index, true));
    }

    @Override
    public void onState(boolean playing) {
        main.post(() -> {
            updatePlayButton(playing);
            if (!playing) showLoading(false);
        });
    }

    @Override
    public void onLoading(boolean loading) {
        main.post(() -> showLoading(loading));
    }

    @Override
    public void onTtsReady() {
        main.post(() -> {
            updateSpeedLabel();
            if (pendingVoiceDialog) {
                pendingVoiceDialog = false;
                showVoices();
            }
        });
    }

    @Override
    public void onTtsError(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
