package org.almoha.liseuse.tts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.Nullable;

import org.almoha.liseuse.R;
import org.almoha.liseuse.epub.BookText;
import org.almoha.liseuse.epub.Sentence;
import org.almoha.liseuse.model.Library;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TtsService extends Service implements TextToSpeech.OnInitListener {

    public static final String ACTION_TOGGLE = "org.almoha.liseuse.TOGGLE";
    public static final String ACTION_PAUSE = "org.almoha.liseuse.PAUSE";
    public static final String ACTION_PREV = "org.almoha.liseuse.PREV";
    public static final String ACTION_NEXT = "org.almoha.liseuse.NEXT";
    public static final String ACTION_STOP = "org.almoha.liseuse.STOP";

    public interface Listener {
        void onSentence(int index);

        void onState(boolean playing);

        void onTtsReady();

        void onTtsError(String message);
    }

    public class LocalBinder extends Binder {
        public TtsService service() {
            return TtsService.this;
        }
    }

    private static final String CHANNEL_ID = "tts";
    private static final int NOTIFICATION_ID = 42;

    private final IBinder binder = new LocalBinder();
    private TextToSpeech tts;
    private boolean ready;
    private boolean playing;
    private float speed = 1.0f;
    private String voiceName;
    private String enginePkg;
    private int engineGeneration;
    private android.content.SharedPreferences prefs;

    private BookText text;
    private String textPath;
    private String bookId;
    private Library library;
    private int index;
    private Listener listener;

    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        library = new Library(this);
        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        enginePkg = prefs.getString("engine", null);
        createEngine();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "liseuse:tts");
            wakeLock.setReferenceCounted(false);
        }
    }

    /** (Re)crée le moteur de synthèse ; seul le dernier créé est pris en compte à l'initialisation. */
    private void createEngine() {
        ready = false;
        voiceName = prefs.getString(voiceKey(), null);
        final int generation = ++engineGeneration;
        TextToSpeech.OnInitListener init = status -> {
            if (generation == engineGeneration) onInit(status);
        };
        tts = enginePkg == null ? new TextToSpeech(this, init) : new TextToSpeech(this, init, enginePkg);
    }

    private String voiceKey() {
        return enginePkg == null ? "voice" : "voice:" + enginePkg;
    }

    public static class EngineInfo {
        public final String pkg;
        public final String label;

        EngineInfo(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** Applis de synthèse vocale installées (Google, SherpaTTS, Acapela…). */
    public List<EngineInfo> engines() {
        List<EngineInfo> out = new ArrayList<>();
        if (tts == null) return out;
        for (TextToSpeech.EngineInfo e : tts.getEngines()) out.add(new EngineInfo(e.name, e.label));
        return out;
    }

    public String currentEngine() {
        if (enginePkg != null) return enginePkg;
        return tts != null ? tts.getDefaultEngine() : null;
    }

    public void setEngine(String pkg) {
        if (pkg == null || pkg.equals(currentEngine())) return;
        if (playing) pause();
        enginePkg = pkg;
        prefs.edit().putString("engine", pkg).apply();
        TextToSpeech old = tts;
        createEngine();
        if (old != null) {
            old.stop();
            old.shutdown();
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            if (listener != null) listener.onTtsError("Synthèse vocale indisponible");
            return;
        }
        ready = true;
        int r = tts.setLanguage(Locale.FRENCH);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
        }
        // Voix enregistrée absente ou non installée : on reprend une voix française installée.
        if (voiceName == null || !applyVoice(voiceName)) pickFrenchVoice();
        tts.setSpeechRate(speed);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                int i = parseId(utteranceId);
                if (i >= 0) {
                    measureRate(i);
                    index = i;
                    queueAhead(i);
                    saveProgress();
                    if (listener != null) listener.onSentence(i);
                    updateNotification();
                }
            }

            @Override
            public void onDone(String utteranceId) {
                if (!playing) return;
                int i = parseId(utteranceId);
                if (i != index) return;
                // Les phrases suivantes sont déjà en file d'attente (queueAhead) : rien à faire sauf en fin de livre.
                if (text == null || index + 1 >= text.sentences.size()) {
                    playing = false;
                    releaseAudio();
                    if (listener != null) listener.onState(false);
                    stopForeground(false);
                    updateNotification();
                }
            }

            @Override
            public void onError(String utteranceId) {
                Log.e("Liseuse", "TTS onError " + utteranceId);
                playing = false;
                releaseAudio();
                if (listener != null) listener.onState(false);
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
            }
        });
        if (playing && text != null) speakCurrent();
        if (listener != null) listener.onTtsReady();
    }

    private int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (Exception e) {
            return -1;
        }
    }

    public void setListener(Listener l) {
        listener = l;
        if (ready && l != null) l.onTtsReady();
    }

    public void clearListener(Listener l) {
        if (listener == l) listener = null;
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isPlaying() {
        return playing;
    }

    public int currentIndex() {
        return index;
    }

    public float speed() {
        return speed;
    }

    // Vitesse de lecture mesurée (caractères/seconde à la vitesse 1×), pour les sauts de 10 s.
    private float charsPerSecond = 14f;
    private long lastStartTime;
    private int lastStartIndex = -1;

    private void measureRate(int i) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (text != null && lastStartIndex == i - 1 && lastStartIndex >= 0 && lastStartTime > 0) {
            float seconds = (now - lastStartTime) / 1000f;
            Sentence prev = text.sentences.get(lastStartIndex);
            if (seconds > 0.5f && seconds < 30f) {
                float rate = (prev.end - prev.start) / seconds / speed;
                charsPerSecond = charsPerSecond * 0.85f + rate * 0.15f;
            }
        }
        lastStartTime = now;
        lastStartIndex = i;
    }

    /** Nombre de caractères lus en {@code seconds} secondes à la vitesse actuelle. */
    public int charsFor(float seconds) {
        return Math.round(charsPerSecond * speed * seconds);
    }

    public String bookId() {
        return bookId;
    }

    /** Enregistre la position même quand l'écran du livre est fermé (écoute écran éteint). */
    private void saveProgress() {
        if (bookId == null || text == null || index < 0 || index >= text.sentences.size()) return;
        library.updateProgress(bookId, text.sentences.get(index).start, index);
    }

    public void playFrom(String id, String path, int startIndex) {
        if (path == null) return;
        bookId = id;
        if (!path.equals(textPath)) {
            textPath = path;
            text = loadText(path);
        }
        if (text == null || text.sentences.isEmpty()) {
            if (listener != null) listener.onTtsError("Texte indisponible");
            return;
        }
        index = Math.max(0, Math.min(startIndex, text.sentences.size() - 1));
        playing = true;
        requestAudio();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (ready) {
            speakCurrent();
        }
        if (listener != null) listener.onState(true);
        updateNotification();
    }

    public void pause() {
        saveProgress();
        playing = false;
        if (ready) tts.stop();
        releaseAudio();
        if (listener != null) listener.onState(false);
        stopForeground(false);
        updateNotification();
    }

    public void resume() {
        if (text == null) return;
        playing = true;
        requestAudio();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (ready) speakCurrent();
        if (listener != null) listener.onState(true);
        updateNotification();
    }

    public void toggle() {
        if (playing) pause();
        else resume();
    }

    /** Saut d'environ {@code seconds} secondes, au début de phrase le plus proche. */
    public void jumpSeconds(int seconds) {
        if (text == null || text.sentences.isEmpty()) return;
        int from = Math.max(0, Math.min(index, text.sentences.size() - 1));
        int startOffset = text.sentences.get(from).start;
        int target = startOffset + (seconds > 0 ? 1 : -1) * charsFor(Math.abs(seconds));
        target = Math.max(0, Math.min(text.text.length() - 1, target));
        int i = text.sentenceIndexAt(target);
        if (i + 1 < text.sentences.size()
                && text.sentences.get(i + 1).start - target < target - text.sentences.get(i).start) {
            i++;
        }
        if (i == from) i += seconds > 0 ? 1 : -1;
        seekToSentence(i);
        updateNotification();
    }

    private void stopAll() {
        saveProgress();
        playing = false;
        if (ready) tts.stop();
        releaseAudio();
        if (listener != null) listener.onState(false);
        stopForeground(true);
        stopSelf();
    }

    public void next() {
        if (text == null) return;
        index = Math.min(index + 1, text.sentences.size() - 1);
        if (playing && ready) speakCurrent();
        if (listener != null) listener.onSentence(index);
        updateNotification();
    }

    public void previous() {
        if (text == null) return;
        index = Math.max(index - 1, 0);
        if (playing && ready) speakCurrent();
        if (listener != null) listener.onSentence(index);
        updateNotification();
    }

    public void seekToSentence(int i) {
        if (text == null) return;
        index = Math.max(0, Math.min(i, text.sentences.size() - 1));
        if (listener != null) listener.onSentence(index);
        if (playing && ready) speakCurrent();
    }

    public void setSpeed(float value) {
        speed = Math.max(0.5f, Math.min(2.5f, value));
        if (ready) tts.setSpeechRate(speed);
        updateNotification();
    }

    public static class VoiceInfo {
        public final String name;
        public final String label;
        public final boolean installed;

        VoiceInfo(String name, String label, boolean installed) {
            this.name = name;
            this.label = label;
            this.installed = installed;
        }
    }

    public String currentVoice() {
        return voiceName;
    }

    /** Voix françaises du moteur : France d'abord, voix installées d'abord, sans les doublons « en ligne ». */
    public List<VoiceInfo> frenchVoices() {
        List<VoiceInfo> out = new ArrayList<>();
        if (!ready) return out;
        List<Voice> list = new ArrayList<>();
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    // Les voix « non installées » de Google ne se téléchargent pas depuis une app.
                    if (!isFrench(v) || v.isNetworkConnectionRequired() || !isInstalled(v)) continue;
                    if (v.getFeatures() != null && v.getFeatures().contains("legacySetLanguageVoice")) continue;
                    list.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        java.util.Collections.sort(list, (a, b) -> {
            int ra = regionRank(a), rb = regionRank(b);
            if (ra != rb) return Integer.compare(ra, rb);
            boolean ia = isInstalled(a), ib = isInstalled(b);
            if (ia != ib) return ia ? -1 : 1;
            return a.getName().compareTo(b.getName());
        });
        for (Voice v : list) out.add(new VoiceInfo(v.getName(), voiceLabel(v), isInstalled(v)));
        return out;
    }

    private static boolean isFrench(Voice v) {
        try {
            return v.getLocale() != null && v.getLocale().getISO3Language().equals("fra");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isInstalled(Voice v) {
        return v.getFeatures() == null || !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
    }

    private static int regionRank(Voice v) {
        String c = v.getLocale().getCountry();
        if (c.equals("FR") || c.equals("FRA")) return 0;
        if (c.equals("BE") || c.equals("BEL") || c.equals("CH") || c.equals("CHE")) return 1;
        return 2;
    }

    /** « fr-fr-x-frb-local » → « France · Voix B » ; « fr-fr-x-star04-local » → « France · Voix 4 ». */
    private static String voiceLabel(Voice v) {
        String c = v.getLocale().getCountry();
        String region = c.startsWith("FR") ? "France" : c.startsWith("CA") ? "Canada"
                : c.startsWith("BE") ? "Belgique" : c.startsWith("CH") ? "Suisse" : v.getLocale().getDisplayCountry(Locale.FRENCH);
        if (region.isEmpty()) region = "Français";
        String name = v.getName();
        int x = name.indexOf("-x-");
        int st = name.indexOf("-supertonic-");
        if (st >= 0) {
            // Voix intégrées de Liseuse : « fr-FR-supertonic-F1 » → « Femme 1 ».
            String style = name.substring(st + "-supertonic-".length());
            return region + " \u00B7 " + (style.startsWith("F") ? "Femme " : "Homme ") + style.substring(1);
        }
        if (x < 0) {
            // Certaines applis (SherpaTTS…) ne nomment leur voix que par la langue : « fr », « fra ».
            if (name.length() <= 6) return region + " \u00B7 voix install\u00E9e dans l'app";
            return region + " \u00B7 " + name;
        }
        // Noms Google : « fr-fr-x-frb-local » → « Voix B ».
        String code = name.substring(x + 3);
        int dash = code.indexOf('-');
        if (dash > 0) code = code.substring(0, dash);
        String friendly;
        if (code.startsWith("star")) {
            friendly = "Voix " + Integer.parseInt(code.replaceAll("\\D", "0").substring(4));
        } else if (code.length() == 3) {
            friendly = "Voix " + Character.toUpperCase(code.charAt(2));
        } else {
            friendly = code;
        }
        return region + " \u00B7 " + friendly;
    }

    public void setVoice(String name) {
        voiceName = name;
        prefs.edit().putString(voiceKey(), name).apply();
        if (ready) applyVoice(name);
    }

    /** Lit un court extrait avec la voix actuelle (sans toucher à la lecture du livre). */
    public void preview(String sample) {
        if (!ready || playing) return;
        tts.speak(sample, TextToSpeech.QUEUE_FLUSH, null, "preview");
    }

    private void pickFrenchVoice() {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return;
            Voice best = null;
            for (Voice v : voices) {
                if (!isFrench(v) || v.isNetworkConnectionRequired() || !isInstalled(v)) continue;
                if (v.getFeatures() != null && v.getFeatures().contains("legacySetLanguageVoice")) continue;
                if (best == null || regionRank(v) < regionRank(best)
                        || (regionRank(v) == regionRank(best) && v.getName().compareTo(best.getName()) < 0)) {
                    best = v;
                }
            }
            if (best != null) {
                voiceName = best.getName();
                tts.setVoice(best);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean applyVoice(String name) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return false;
            for (Voice v : voices) {
                if (v.getName().equals(name) && isInstalled(v)) {
                    tts.setVoice(v);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    // Phrases envoyées d'avance au moteur : il calcule la suivante pendant que l'on écoute la courante.
    private static final int AHEAD = 2;
    private int queuedUpTo = -1;

    private void speakCurrent() {
        if (!ready || text == null) return;
        if (index < 0 || index >= text.sentences.size()) return;
        tts.speak(phrase(index), TextToSpeech.QUEUE_FLUSH, null, String.valueOf(index));
        queuedUpTo = index;
        queueAhead(index);
    }

    private void queueAhead(int from) {
        if (!playing || !ready || text == null) return;
        int last = Math.min(text.sentences.size() - 1, from + AHEAD);
        for (int j = Math.max(queuedUpTo + 1, from + 1); j <= last; j++) {
            tts.speak(phrase(j), TextToSpeech.QUEUE_ADD, null, String.valueOf(j));
            queuedUpTo = j;
        }
    }

    private String phrase(int i) {
        Sentence s = text.sentences.get(i);
        String p = text.text.substring(s.start, s.end);
        return p.trim().isEmpty() ? " " : p;
    }

    private BookText loadText(String path) {
        try {
            File f = new File(path);
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return new BookText(content, new ArrayList<>());
        } catch (Exception e) {
            return null;
        }
    }

    private void requestAudio() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(change -> {
                            if (change == AudioManager.AUDIOFOCUS_LOSS
                                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
                        })
                        .build();
            }
            audioManager.requestAudioFocus(focusRequest);
        }
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(60 * 60 * 1000L);
    }

    private void releaseAudio() {
        if (audioManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Lecture vocale", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private android.media.session.MediaSession session;
    private String coverPathShown;
    private android.graphics.Bitmap cover;

    /** Session multimédia : lecteur sur l'écran verrouillé, réglages rapides, boutons du casque. */
    private void ensureSession() {
        if (session != null) return;
        session = new android.media.session.MediaSession(this, "Liseuse");
        session.setCallback(new android.media.session.MediaSession.Callback() {
            @Override
            public void onPlay() {
                resume();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onStop() {
                stopAll();
            }

            @Override
            public void onSkipToNext() {
                jumpSeconds(10);
            }

            @Override
            public void onSkipToPrevious() {
                jumpSeconds(-10);
            }

            @Override
            public void onFastForward() {
                jumpSeconds(10);
            }

            @Override
            public void onRewind() {
                jumpSeconds(-10);
            }
        });
        Intent open = openIntent();
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        session.setActive(true);
    }

    private Intent openIntent() {
        return new Intent(this, org.almoha.liseuse.ReaderActivity.class)
                .putExtra(org.almoha.liseuse.ReaderActivity.EXTRA_BOOK_ID, bookId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void updateSession() {
        ensureSession();
        org.almoha.liseuse.model.Book book = bookId == null ? null : library.find(bookId);
        String coverPath = book != null ? book.coverPath : null;
        if (coverPath == null ? coverPathShown != null : !coverPath.equals(coverPathShown)) {
            coverPathShown = coverPath;
            cover = coverPath == null ? null : android.graphics.BitmapFactory.decodeFile(coverPath);
        }
        android.media.MediaMetadata.Builder meta = new android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE,
                        book != null ? book.title : "Lecture vocale")
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST,
                        book != null && book.author != null ? book.author : "");
        if (cover != null) meta.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
        session.setMetadata(meta.build());
        long actions = android.media.session.PlaybackState.ACTION_PLAY
                | android.media.session.PlaybackState.ACTION_PAUSE
                | android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                | android.media.session.PlaybackState.ACTION_STOP
                | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | android.media.session.PlaybackState.ACTION_FAST_FORWARD
                | android.media.session.PlaybackState.ACTION_REWIND;
        session.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? android.media.session.PlaybackState.STATE_PLAYING
                                : android.media.session.PlaybackState.STATE_PAUSED,
                        android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? speed : 0f)
                .build());
    }


    private Notification buildNotification() {
        shownPlaying = playing;
        shownBook = bookId;
        updateSession();
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        org.almoha.liseuse.model.Book book = bookId == null ? null : library.find(bookId);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_play)
                .setContentTitle(book != null ? book.title : "Lecture vocale")
                .setContentText(book != null && book.author != null ? book.author : "")
                .setLargeIcon(cover)
                .setContentIntent(openPi)
                .setDeleteIntent(action(ACTION_STOP))
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(
                        this, R.drawable.ic_replay10), "Reculer de 10 s", action(ACTION_PREV)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(
                        this, playing ? R.drawable.ic_pause : R.drawable.ic_play),
                        playing ? "Pause" : "Lecture", action(ACTION_TOGGLE)).build())
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(
                        this, R.drawable.ic_forward10), "Avancer de 10 s", action(ACTION_NEXT)).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    private PendingIntent action(String action) {
        Intent i = new Intent(this, TtsService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // Dernier état affiché : le lecteur n'est republié que s'il change. Le republier à chaque
    // phrase faisait redessiner la carte du lecteur et perdre des appuis sur pause/lecture.
    private Boolean shownPlaying;
    private String shownBook;

    private void updateNotification() {
        if (!playing && !ready) return;
        if (shownPlaying != null && shownPlaying == playing
                && (bookId == null ? shownBook == null : bookId.equals(shownBook))) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (a != null) {
            switch (a) {
                case ACTION_TOGGLE:
                    toggle();
                    break;
                case ACTION_PAUSE:
                    pause();
                    break;
                case ACTION_PREV:
                    jumpSeconds(-10);
                    break;
                case ACTION_NEXT:
                    jumpSeconds(10);
                    break;
                case ACTION_STOP:
                    stopAll();
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        playing = false;
        if (session != null) {
            session.setActive(false);
            session.release();
            session = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        releaseAudio();
        super.onDestroy();
    }
}
