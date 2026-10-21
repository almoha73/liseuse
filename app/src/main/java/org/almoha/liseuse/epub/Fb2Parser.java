package org.almoha.liseuse.epub;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Fb2Parser {

    private static class Piece {
        String title;
        final StringBuilder text = new StringBuilder();
    }

    public static EpubBook parse(File file) throws IOException {
        try {
            return parseXml(decode(readFb2Bytes(file)));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /** Accepte un .fb2 ou un .fb2.zip. */
    private static byte[] readFb2Bytes(File file) throws IOException {
        if (EpubParser.isZip(file)) {
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().toLowerCase(Locale.ROOT).endsWith(".fb2")) {
                        try (InputStream in = zip.getInputStream(e)) {
                            return readAll(in);
                        }
                    }
                }
            }
            throw new IOException("Aucun fichier FB2 dans l'archive");
        }
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static String decode(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(bytes.length, 200), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("encoding=[\"']([A-Za-z0-9_\\-]+)[\"']").matcher(head);
        Charset cs = StandardCharsets.UTF_8;
        if (m.find()) {
            try {
                cs = Charset.forName(m.group(1));
            } catch (Exception ignored) {
            }
        }
        String s = new String(bytes, cs);
        if (s.startsWith("﻿")) s = s.substring(1);
        // La déclaration XML ne correspond plus une fois le texte décodé.
        return s.replaceFirst("^\\s*<\\?xml[^>]*\\?>", "");
    }

    private static EpubBook parseXml(String xml) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(EpubParser.replaceEntities(xml)));

        EpubBook book = new EpubBook();
        List<Piece> pieces = new ArrayList<>();
        Piece current = new Piece();
        pieces.add(current);

        boolean inTitleInfo = false;
        boolean inAuthor = false;
        boolean inBookTitle = false;
        boolean inBody = false;
        boolean bodyDone = false;
        boolean inBinary = false;
        int titleDepth = 0;
        String coverHref = null;
        String binaryId = null;
        StringBuilder field = new StringBuilder();
        StringBuilder titleBuf = new StringBuilder();
        List<String> authors = new ArrayList<>();
        StringBuilder authorName = new StringBuilder();

        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = local(p.getName());
                if (inBody) {
                    switch (n) {
                        case "section":
                            current = new Piece();
                            pieces.add(current);
                            break;
                        case "title":
                            titleDepth++;
                            titleBuf.setLength(0);
                            EpubParser.blockBreak(current.text);
                            break;
                        case "empty-line":
                            EpubParser.blockBreak(current.text);
                            break;
                        default:
                            if (isBlock(n)) EpubParser.blockBreak(current.text);
                            break;
                    }
                    continue;
                }
                switch (n) {
                    case "title-info":
                        inTitleInfo = true;
                        break;
                    case "author":
                        if (inTitleInfo) {
                            inAuthor = true;
                            authorName.setLength(0);
                        }
                        break;
                    case "first-name":
                    case "middle-name":
                    case "last-name":
                    case "nickname":
                        field.setLength(0);
                        break;
                    case "book-title":
                        if (inTitleInfo) {
                            inBookTitle = true;
                            field.setLength(0);
                        }
                        break;
                    case "image":
                        if (inTitleInfo && coverHref == null) {
                            String href = attr(p, "href");
                            if (href != null && href.startsWith("#")) coverHref = href.substring(1);
                        }
                        break;
                    case "body":
                        String name = attr(p, "name");
                        if (!bodyDone && (name == null || !name.equalsIgnoreCase("notes"))) inBody = true;
                        break;
                    case "binary":
                        binaryId = attr(p, "id");
                        inBinary = coverHref != null && coverHref.equals(binaryId);
                        field.setLength(0);
                        break;
                    default:
                        break;
                }
            } else if (ev == XmlPullParser.TEXT) {
                String t = p.getText();
                if (inBody) {
                    current.text.append(t);
                    if (titleDepth > 0) titleBuf.append(t);
                } else {
                    field.append(t);
                }
            } else if (ev == XmlPullParser.END_TAG) {
                String n = local(p.getName());
                if (inBody) {
                    if (n.equals("body")) {
                        inBody = false;
                        bodyDone = true;
                    } else if (n.equals("title") && titleDepth > 0) {
                        titleDepth--;
                        EpubParser.blockBreak(current.text);
                        String t = titleBuf.toString().replaceAll("\\s+", " ").trim()
                                .replaceAll("^(\u2014\\s*)+|(\\s*\u2014)+$", "")
                                .replaceAll("(\u2014\\s*){2,}", "\u2014 ")
                                .replaceAll("^([0-9IVXLC]+[.)]?) \u2014 ", "$1 ");
                        if (current.title == null && !t.isEmpty()) current.title = t;
                    } else if (isBlock(n)) {
                        EpubParser.blockBreak(current.text);
                        if (titleDepth > 0 && n.equals("p")) titleBuf.append(" \u2014 ");
                    }
                    continue;
                }
                switch (n) {
                    case "title-info":
                        inTitleInfo = false;
                        break;
                    case "first-name":
                    case "middle-name":
                    case "last-name":
                    case "nickname":
                        if (inAuthor) {
                            String v = field.toString().trim();
                            if (!v.isEmpty()) {
                                if (authorName.length() > 0) authorName.append(' ');
                                authorName.append(v);
                            }
                        }
                        break;
                    case "author":
                        if (inAuthor && authorName.length() > 0) authors.add(authorName.toString());
                        inAuthor = false;
                        break;
                    case "book-title":
                        if (inBookTitle) book.title = field.toString().replaceAll("\\s+", " ").trim();
                        inBookTitle = false;
                        break;
                    case "binary":
                        if (inBinary) {
                            try {
                                book.cover = Base64.getMimeDecoder().decode(field.toString().trim());
                            } catch (Exception ignored) {
                            }
                        }
                        inBinary = false;
                        break;
                    default:
                        break;
                }
            }
        }

        if (!authors.isEmpty()) book.author = String.join(", ", authors);

        StringBuilder text = new StringBuilder();
        List<Chapter> chapters = new ArrayList<>();
        int index = 0;
        for (Piece piece : pieces) {
            String body = EpubParser.collapse(piece.text.toString());
            if (body.isEmpty()) continue;
            index++;
            String title = piece.title != null ? piece.title : (index == 1 ? "Début" : "Partie " + index);
            if (text.length() > 0) text.append('\n');
            chapters.add(new Chapter(title, text.length()));
            text.append(body).append('\n');
        }
        if (text.length() == 0) throw new IOException("Aucun texte extractible");

        book.content = new BookText(EpubParser.collapse(text.toString()), chapters);
        return book;
    }

    private static boolean isBlock(String n) {
        switch (n) {
            case "p":
            case "subtitle":
            case "v":
            case "stanza":
            case "poem":
            case "cite":
            case "epigraph":
            case "text-author":
            case "table":
            case "tr":
                return true;
            default:
                return false;
        }
    }

    private static String local(String name) {
        int i = name.indexOf(':');
        return (i >= 0 ? name.substring(i + 1) : name).toLowerCase(Locale.ROOT);
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (local(p.getAttributeName(i)).equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }
}
