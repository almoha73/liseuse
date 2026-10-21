package org.almoha.liseuse.tts;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moteur de synthèse vocale intégré : Supertonic 3 (hors ligne, via sherpa-onnx).
 * Exposé comme un moteur Android standard, il apparaît dans « Changer d'app de voix ».
 * Les fichiers du modèle sont dans le stockage privé de l'appli : {@code files/supertonic}.
 */
public class SupertonicService extends TextToSpeechService {

    private static final String TAG = "LiseuseSupertonic";
    // Ordre de voice.bin : fichiers de styles triés par nom (F1…F5 puis M1…M5).
    private static final String[] STYLES = {"F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"};
    private static final String PREFIX = "fr-FR-supertonic-";
    private static final int NUM_STEPS = 5;

    private OfflineTts tts;
    private volatile boolean stopped;
    private String voiceName = PREFIX + STYLES[0];

    private File modelDir() {
        return SupertonicDownloader.modelDir(this);
    }

    private boolean modelPresent() {
        return SupertonicDownloader.isInstalled(this);
    }

    private synchronized boolean ensureLoaded() {
        if (tts != null) return true;
        if (!modelPresent()) {
            Log.e(TAG, "Modèle absent : " + modelDir());
            return false;
        }
        String d = modelDir().getAbsolutePath();
        OfflineTtsSupertonicModelConfig st = new OfflineTtsSupertonicModelConfig();
        st.setDurationPredictor(d + "/duration_predictor.int8.onnx");
        st.setTextEncoder(d + "/text_encoder.int8.onnx");
        st.setVectorEstimator(d + "/vector_estimator.int8.onnx");
        st.setVocoder(d + "/vocoder.int8.onnx");
        st.setTtsJson(d + "/tts.json");
        st.setUnicodeIndexer(d + "/unicode_indexer.bin");
        st.setVoiceStyle(d + "/voice.bin");
        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setSupertonic(st);
        model.setNumThreads(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        long t = SystemClock.elapsedRealtime();
        try {
            tts = new OfflineTts(null, config);
        } catch (Throwable e) {
            Log.e(TAG, "Chargement impossible", e);
            return false;
        }
        Log.i(TAG, "Modèle chargé en " + (SystemClock.elapsedRealtime() - t) + " ms");
        return true;
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            if (tts != null) {
                tts.release();
                tts = null;
            }
        }
        super.onDestroy();
    }

    private static boolean isFrench(String lang) {
        return lang != null && (lang.equalsIgnoreCase("fra") || lang.equalsIgnoreCase("fr"));
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        if (!isFrench(lang)) return TextToSpeech.LANG_NOT_SUPPORTED;
        if (country != null && (country.equalsIgnoreCase("FRA") || country.equalsIgnoreCase("FR"))) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE;
        }
        return TextToSpeech.LANG_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() {
        return new String[]{"fra", "FRA", ""};
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    protected void onStop() {
        stopped = true;
    }

    @Override
    public List<Voice> onGetVoices() {
        List<Voice> out = new ArrayList<>();
        Set<String> features = new HashSet<>();
        if (!modelPresent()) features.add(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED);
        for (String s : STYLES) {
            out.add(new Voice(PREFIX + s, Locale.FRANCE, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL,
                    false, features));
        }
        return out;
    }

    private static int sidFor(String name) {
        if (name != null && name.startsWith(PREFIX)) {
            String style = name.substring(PREFIX.length());
            for (int i = 0; i < STYLES.length; i++) {
                if (STYLES[i].equals(style)) return i;
            }
        }
        return 0;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        return name != null && name.startsWith(PREFIX) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String name) {
        if (onIsValidVoiceName(name) != TextToSpeech.SUCCESS) return TextToSpeech.ERROR;
        voiceName = name;
        return TextToSpeech.SUCCESS;
    }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return isFrench(lang) ? PREFIX + STYLES[0] : null;
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        stopped = false;
        String text = request.getCharSequenceText() != null
                ? request.getCharSequenceText().toString() : request.getText();
        if (text == null || text.trim().isEmpty()) {
            callback.start(44100, AudioFormat.ENCODING_PCM_16BIT, 1);
            callback.done();
            return;
        }
        if (!ensureLoaded()) {
            callback.error(TextToSpeech.ERROR_SERVICE);
            return;
        }
        String name = request.getVoiceName() != null ? request.getVoiceName() : voiceName;
        GenerationConfig g = new GenerationConfig();
        g.setSid(sidFor(name));
        g.setSpeed(Math.max(0.5f, Math.min(2.5f, request.getSpeechRate() / 100f)));
        g.setNumSteps(NUM_STEPS);
        g.setExtra(Collections.singletonMap("lang", "fr"));

        long t = SystemClock.elapsedRealtime();
        GeneratedAudio audio;
        synchronized (this) {
            audio = tts.generateWithConfig(text, g);
        }
        float[] samples = audio.getSamples();
        int rate = audio.getSampleRate();
        long elapsed = SystemClock.elapsedRealtime() - t;
        Log.i(TAG, String.format(Locale.ROOT, "%d car. → %.1f s d'audio en %d ms (rtf %.2f)", text.length(),
                samples.length / (float) rate, elapsed, elapsed / 1000f / Math.max(0.01f, samples.length / (float) rate)));
        if (stopped) {
            callback.error(TextToSpeech.ERROR_SERVICE);
            return;
        }
        if (callback.start(rate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) return;
        byte[] pcm = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int v = (int) (Math.max(-1f, Math.min(1f, samples[i])) * 32767);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        int max = callback.getMaxBufferSize();
        for (int off = 0; off < pcm.length && !stopped; off += max) {
            if (callback.audioAvailable(pcm, off, Math.min(max, pcm.length - off)) != TextToSpeech.SUCCESS) return;
        }
        callback.done();
    }
}
