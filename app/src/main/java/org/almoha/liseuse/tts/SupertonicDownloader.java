package org.almoha.liseuse.tts;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Télécharge le modèle Supertonic 3 depuis la page officielle de sherpa-onnx,
 * vérifie son empreinte SHA-256, puis l'extrait dans {@code files/supertonic}.
 * Une seule instance tourne à la fois ; elle survit à la fermeture de l'écran.
 */
public final class SupertonicDownloader {

    public interface Listener {
        void onProgress(int percent, String step);

        void onDone();

        void onError(String message);
    }

    private static final String URL_MODEL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
            + "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2";
    private static final String SHA256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427";
    public static final long SIZE = 128_774_318L;
    private static final Set<String> FILES = new HashSet<>(Arrays.asList(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin", "LICENSE"));

    private static SupertonicDownloader running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final File filesDir;
    private final File cacheDir;
    private volatile Listener listener;
    private volatile boolean cancelled;
    private int lastPercent = -1;
    private String lastStep = "";

    private SupertonicDownloader(Context context) {
        filesDir = context.getApplicationContext().getFilesDir();
        cacheDir = context.getApplicationContext().getCacheDir();
    }

    public static File modelDir(Context context) {
        return new File(context.getFilesDir(), "supertonic");
    }

    public static boolean isInstalled(Context context) {
        File d = modelDir(context);
        for (String f : FILES) {
            if (!new File(d, f).isFile()) return false;
        }
        return true;
    }

    /** Lance le téléchargement, ou se rattache à celui déjà en cours. */
    public static synchronized SupertonicDownloader start(Context context, Listener l) {
        if (running == null) {
            running = new SupertonicDownloader(context);
            running.listener = l;
            new Thread(running::run, "supertonic-download").start();
        } else {
            running.attach(l);
        }
        return running;
    }

    public static synchronized boolean isRunning() {
        return running != null;
    }

    public void attach(Listener l) {
        listener = l;
        if (l != null && lastPercent >= 0) main.post(() -> l.onProgress(lastPercent, lastStep));
    }

    public void detach(Listener l) {
        if (listener == l) listener = null;
    }

    public void cancel() {
        cancelled = true;
    }

    private void progress(int percent, String step) {
        if (percent == lastPercent && step.equals(lastStep)) return;
        lastPercent = percent;
        lastStep = step;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onProgress(percent, step);
        });
    }

    private void run() {
        File archive = new File(cacheDir, "supertonic.tar.bz2");
        File tmp = new File(filesDir, "supertonic.tmp");
        String error = null;
        try {
            download(archive);
            verify(archive);
            extract(archive, tmp);
            File dest = new File(filesDir, "supertonic");
            deleteRecursive(dest);
            if (!tmp.renameTo(dest)) throw new IOException("Stockage indisponible");
        } catch (Exception e) {
            error = cancelled ? null : (e.getMessage() != null ? e.getMessage() : e.toString());
        } finally {
            archive.delete();
            deleteRecursive(tmp);
            synchronized (SupertonicDownloader.class) {
                running = null;
            }
        }
        String finalError = error;
        main.post(() -> {
            Listener l = listener;
            if (l == null || cancelled) return;
            if (finalError == null) l.onDone();
            else l.onError(finalError);
        });
    }

    private void download(File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_MODEL).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IOException("Téléchargement refusé (code " + c.getResponseCode() + ")");
        }
        long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : SIZE;
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                if (cancelled) throw new IOException("Annulé");
                o.write(buf, 0, r);
                done += r;
                // Le téléchargement compte pour 85 % de la barre, l'extraction pour le reste.
                progress((int) (85 * done / total), "Téléchargement");
            }
        } finally {
            c.disconnect();
        }
    }

    private void verify(File f) throws Exception {
        progress(86, "Vérification");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(SHA256)) {
            throw new IOException("Fichier corrompu ou modifié, téléchargement annulé");
        }
    }

    private void extract(File archive, File dir) throws IOException {
        deleteRecursive(dir);
        if (!dir.mkdirs()) throw new IOException("Stockage indisponible");
        int found = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive))))) {
            TarArchiveEntry e;
            while ((e = tar.getNextTarEntry()) != null) {
                if (cancelled) throw new IOException("Annulé");
                if (e.isDirectory()) continue;
                String name = new File(e.getName()).getName();
                if (!FILES.contains(name)) continue;
                try (OutputStream o = new FileOutputStream(new File(dir, name))) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = tar.read(buf)) > 0) o.write(buf, 0, r);
                }
                found++;
                progress(86 + 14 * found / FILES.size(), "Installation");
            }
        }
        if (found < FILES.size()) throw new IOException("Archive incomplète");
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursive(c);
        f.delete();
    }
}
