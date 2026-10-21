package org.almoha.liseuse.epub;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class EpubParser {

    private static class Item {
        String id;
        String href;
        String mediaType;
        String properties;
    }

    public static EpubBook parse(File file) throws IOException {
        EpubBook book = new EpubBook();
        try (ZipFile zip = new ZipFile(file)) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) throw new IOException("OPF introuvable");
            String opfDir = dirname(opfPath);

            List<Item> manifest = new ArrayList<>();
            Map<String, Item> byId = new HashMap<>();
            List<String> spine = new ArrayList<>();
            Map<String, String> metadata = new HashMap<>();
            String coverId = null;
            String navId = null;
            String spineToc = null;

            XmlPullParser p = newParser(readEntry(zip, opfPath));
            boolean inMetadata = false;
            boolean inManifest = false;
            String currentMeta = null;
            StringBuilder metaText = new StringBuilder();

            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    switch (name) {
                        case "metadata":
                            inMetadata = true;
                            break;
                        case "manifest":
                            inManifest = true;
                            break;
                        case "item":
                            if (inManifest) {
                                Item it = new Item();
                                it.id = attr(p, "id");
                                it.href = attr(p, "href");
                                it.mediaType = attr(p, "media-type");
                                it.properties = attr(p, "properties");
                                manifest.add(it);
                                if (it.id != null) byId.put(it.id, it);
                                if (it.properties != null && it.properties.contains("nav")) navId = it.id;
                            }
                            break;
                        case "spine":
                            spineToc = attr(p, "toc");
                            break;
                        case "itemref":
                            spine.add(attr(p, "idref"));
                            break;
                        case "meta":
                            if ("cover".equalsIgnoreCase(attr(p, "name"))) coverId = attr(p, "content");
                            break;
                        default:
                            if (inMetadata && (name.equals("title") || name.equals("creator"))) {
                                currentMeta = name;
                                metaText.setLength(0);
                            }
                            break;
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (currentMeta != null) metaText.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = local(p.getName());
                    if (name.equals("metadata")) inMetadata = false;
                    else if (name.equals("manifest")) inManifest = false;
                    else if (name.equals("title") || name.equals("creator")) {
                        if (currentMeta != null) {
                            String v = metaText.toString().replaceAll("\\s+", " ").trim();
                            if (!v.isEmpty()) {
                                String prev = metadata.get(currentMeta);
                                metadata.put(currentMeta, prev == null ? v : prev + ", " + v);
                            }
                        }
                        currentMeta = null;
                    }
                }
            }

            book.title = metadata.get("title");
            book.author = metadata.get("creator");
            if (book.title == null || book.title.trim().isEmpty()) {
                book.title = stripExtension(file.getName());
            }

            Item cover = null;
            if (coverId != null) cover = byId.get(coverId);
            if (cover == null) {
                for (Item it : manifest) {
                    if (it.properties != null && it.properties.contains("cover-image")) {
                        cover = it;
                        break;
                    }
                }
            }
            if (cover == null) {
                for (Item it : manifest) {
                    if (it.mediaType != null && it.mediaType.startsWith("image/")
                            && it.href != null && it.href.toLowerCase(java.util.Locale.ROOT).contains("cover")) {
                        cover = it;
                        break;
                    }
                }
            }
            if (cover != null && cover.href != null) {
                byte[] bytes = readBytes(zip, join(opfDir, cover.href));
                if (bytes != null) book.cover = bytes;
            }

            Map<String, String> tocTitles = new LinkedHashMap<>();
            if (navId != null && byId.containsKey(navId)) {
                Item nav = byId.get(navId);
                String navPath = join(opfDir, nav.href);
                String base = dirname(navPath);
                parseNav(readEntry(zip, navPath), base, tocTitles);
            } else if (spineToc != null && byId.containsKey(spineToc)) {
                Item ncx = byId.get(spineToc);
                String ncxPath = join(opfDir, ncx.href);
                String base = dirname(ncxPath);
                parseNcx(readEntry(zip, ncxPath), base, tocTitles);
            }

            StringBuilder text = new StringBuilder();
            List<Chapter> chapters = new ArrayList<>();
            int index = 0;
            for (String idref : spine) {
                Item it = byId.get(idref);
                if (it == null || it.href == null) continue;
                if (it.mediaType != null && !isDocument(it.mediaType)) continue;
                String path = join(opfDir, it.href);
                String raw = readEntry(zip, path);
                if (raw == null) continue;
                String body = htmlToText(raw);
                if (body.isEmpty()) continue;
                index++;
                String title = tocTitles.get(path);
                if (title == null) title = "Chapitre " + index;
                if (text.length() > 0) text.append('\n');
                chapters.add(new Chapter(title, text.length()));
                text.append(body).append('\n');
            }

            if (text.length() == 0) throw new IOException("Aucun texte extractible");

            String cleaned = collapse(text.toString());
            book.content = new BookText(cleaned, chapters);
            return book;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e);
        }
    }

    public static boolean isZip(File file) {
        try (InputStream in = new java.io.FileInputStream(file)) {
            return in.read() == 'P' && in.read() == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isDocument(String mediaType) {
        return mediaType.contains("html") || mediaType.contains("xml");
    }

    private static String findOpfPath(ZipFile zip) throws Exception {
        String container = readEntry(zip, "META-INF/container.xml");
        if (container == null) return null;
        XmlPullParser p = newParser(container);
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG && local(p.getName()).equals("rootfile")) {
                return attr(p, "full-path");
            }
        }
        return null;
    }

    private static void parseNav(String xml, String base, Map<String, String> out) {
        if (xml == null) return;
        try {
            XmlPullParser p = newParser(xml);
            boolean inNav = false;
            boolean inToc = false;
            String href = null;
            StringBuilder title = new StringBuilder();
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    if (name.equals("nav")) {
                        inNav = true;
                        String type = attr(p, "type");
                        inToc = type != null && type.contains("toc");
                    } else if (inNav && inToc && name.equals("a")) {
                        href = attr(p, "href");
                        title.setLength(0);
                    } else if (inNav && inToc && (name.equals("span") || name.equals("p"))) {
                        title.setLength(0);
                    }
                } else if (ev == XmlPullParser.TEXT) {
                    if (href != null) title.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String name = local(p.getName());
                    if (name.equals("a") && href != null) {
                        String key = resolve(base, href);
                        String t = title.toString().replaceAll("\\s+", " ").trim();
                        if (t.length() > 0 && !out.containsKey(key)) out.put(key, t);
                        href = null;
                    } else if (name.equals("nav")) {
                        inNav = false;
                        inToc = false;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void parseNcx(String xml, String base, Map<String, String> out) {
        if (xml == null) return;
        try {
            XmlPullParser p = newParser(xml);
            StringBuilder label = new StringBuilder();
            boolean inText = false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String name = local(p.getName());
                    if (name.equals("text")) {
                        inText = true;
                        label.setLength(0);
                    } else if (name.equals("content")) {
                        String src = attr(p, "src");
                        if (src != null && label.length() > 0) {
                            String key = resolve(base, src);
                            String t = label.toString().replaceAll("\\s+", " ").trim();
                            if (t.length() > 0 && !out.containsKey(key)) out.put(key, t);
                        }
                    }
                } else if (ev == XmlPullParser.TEXT && inText) {
                    label.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG && local(p.getName()).equals("text")) {
                    inText = false;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String htmlToText(String raw) {
        String prepared = replaceEntities(raw);
        try {
            XmlPullParser p = newParser(prepared);
            StringBuilder sb = new StringBuilder();
            int skip = 0;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    if (skip > 0) {
                        skip++;
                        continue;
                    }
                    String n = local(p.getName());
                    if (n.equals("head") || n.equals("script") || n.equals("style")
                            || n.equals("svg") || n.equals("title")) {
                        skip = 1;
                    } else if (n.equals("br")) {
                        sb.append('\n');
                    } else if (isBlock(n)) {
                        blockBreak(sb);
                    } else if (n.equals("td") || n.equals("th")) {
                        sb.append(' ');
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (skip > 0) {
                        skip--;
                        continue;
                    }
                    String n = local(p.getName());
                    if (isBlock(n)) blockBreak(sb);
                } else if (ev == XmlPullParser.TEXT && skip == 0) {
                    sb.append(p.getText());
                }
            }
            return collapse(sb.toString());
        } catch (Exception e) {
            return collapse(stripTags(prepared));
        }
    }

    private static boolean isBlock(String n) {
        switch (n) {
            case "p":
            case "div":
            case "h1":
            case "h2":
            case "h3":
            case "h4":
            case "h5":
            case "h6":
            case "li":
            case "ul":
            case "ol":
            case "blockquote":
            case "section":
            case "article":
            case "aside":
            case "header":
            case "footer":
            case "tr":
            case "figure":
            case "figcaption":
            case "pre":
            case "dd":
            case "dt":
            case "table":
            case "body":
                return true;
            default:
                return false;
        }
    }

    static void blockBreak(StringBuilder sb) {
        int len = sb.length();
        while (len > 0 && (sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\t')) {
            sb.setLength(--len);
        }
        if (len == 0) return;
        if (len >= 2 && sb.charAt(len - 1) == '\n' && sb.charAt(len - 2) == '\n') return;
        if (sb.charAt(len - 1) == '\n') sb.append('\n');
        else sb.append("\n\n");
    }

    static String collapse(String s) {
        s = s.replace('\u00A0', ' ').replace("\u200B", "").replace("\uFEFF", "");
        s = s.replaceAll("[ \\t\\r\\f]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("(?<!\\n)\\n(?!\\n)", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    private static String stripTags(String s) {
        s = s.replaceAll("(?is)<(script|style|head|svg)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|blockquote|section|article|tr)>", "\n\n");
        s = s.replaceAll("<[^>]+>", " ");
        return s;
    }

    private static final java.util.Set<String> XML_ENTITIES =
            new java.util.HashSet<>(java.util.Arrays.asList("amp", "lt", "gt", "quot", "apos"));

    private static final Map<String, String> ENTITIES = new HashMap<>();

    static {
        String[][] table = {
                {"nbsp", " "}, {"ensp", " "}, {"emsp", " "}, {"thinsp", " "},
                {"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""}, {"apos", "'"},
                {"mdash", "\u2014"}, {"ndash", "\u2013"}, {"hellip", "\u2026"},
                {"lsquo", "\u2018"}, {"rsquo", "\u2019"}, {"sbquo", "\u201A"},
                {"ldquo", "\u201C"}, {"rdquo", "\u201D"}, {"bdquo", "\u201E"},
                {"laquo", "\u00AB"}, {"raquo", "\u00BB"}, {"bull", "\u2022"},
                {"dagger", "\u2020"}, {"Dagger", "\u2021"}, {"permil", "\u2030"},
                {"prime", "\u2032"}, {"Prime", "\u2033"}, {"euro", "\u20AC"},
                {"copy", "\u00A9"}, {"reg", "\u00AE"}, {"trade", "\u2122"},
                {"deg", "\u00B0"}, {"plusmn", "\u00B1"}, {"micro", "\u00B5"},
                {"para", "\u00B6"}, {"sect", "\u00A7"}, {"middot", "\u00B7"},
                {"frac12", "\u00BD"}, {"frac14", "\u00BC"}, {"frac34", "\u00BE"},
                {"times", "\u00D7"}, {"divide", "\u00F7"}, {"minus", "\u2212"},
                {"agrave", "\u00E0"}, {"aacute", "\u00E1"}, {"acirc", "\u00E2"},
                {"atilde", "\u00E3"}, {"auml", "\u00E4"}, {"aring", "\u00E5"},
                {"aelig", "\u00E6"}, {"ccedil", "\u00E7"}, {"egrave", "\u00E8"},
                {"eacute", "\u00E9"}, {"ecirc", "\u00EA"}, {"euml", "\u00EB"},
                {"igrave", "\u00EC"}, {"iacute", "\u00ED"}, {"icirc", "\u00EE"},
                {"iuml", "\u00EF"}, {"ntilde", "\u00F1"}, {"ograve", "\u00F2"},
                {"oacute", "\u00F3"}, {"ocirc", "\u00F4"}, {"otilde", "\u00F5"},
                {"ouml", "\u00F6"}, {"oslash", "\u00F8"}, {"oelig", "\u0153"},
                {"ugrave", "\u00F9"}, {"uacute", "\u00FA"}, {"ucirc", "\u00FB"},
                {"uuml", "\u00FC"}, {"yacute", "\u00FD"}, {"yuml", "\u00FF"},
                {"szlig", "\u00DF"}, {"ccedil", "\u00E7"},
                {"Agrave", "\u00C0"}, {"Aacute", "\u00C1"}, {"Acirc", "\u00C2"},
                {"Atilde", "\u00C3"}, {"Auml", "\u00C4"}, {"Aring", "\u00C5"},
                {"AElig", "\u00C6"}, {"Ccedil", "\u00C7"}, {"Egrave", "\u00C8"},
                {"Eacute", "\u00C9"}, {"Ecirc", "\u00CA"}, {"Euml", "\u00CB"},
                {"Igrave", "\u00CC"}, {"Iacute", "\u00CD"}, {"Icirc", "\u00CE"},
                {"Iuml", "\u00CF"}, {"Ntilde", "\u00D1"}, {"Ograve", "\u00D2"},
                {"Oacute", "\u00D3"}, {"Ocirc", "\u00D4"}, {"Otilde", "\u00D5"},
                {"Ouml", "\u00D6"}, {"Oslash", "\u00D8"}, {"OElig", "\u0152"},
                {"Ugrave", "\u00D9"}, {"Uacute", "\u00DA"}, {"Ucirc", "\u00DB"},
                {"Uuml", "\u00DC"}, {"Yacute", "\u00DD"}, {"Yuml", "\u0178"},
                {"rarr", "\u2192"}, {"larr", "\u2190"}, {"harr", "\u2194"},
                {"iexcl", "\u00A1"}, {"iquest", "\u00BF"}
        };
        for (String[] e : table) ENTITIES.put(e[0], e[1]);
    }

    static String replaceEntities(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("&([a-zA-Z][a-zA-Z0-9]{1,10});")
                .matcher(s);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            if (XML_ENTITIES.contains(m.group(1))) {
                // Entités natives XML : le parseur s'en charge, les remplacer casserait le document.
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String rep = ENTITIES.get(m.group(1));
            if (rep == null) rep = ENTITIES.get(m.group(1).toLowerCase(java.util.Locale.ROOT));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep == null ? "" : rep));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static XmlPullParser newParser(String content) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(content));
        return p;
    }

    private static String local(String name) {
        int i = name.indexOf(':');
        return (i >= 0 ? name.substring(i + 1) : name).toLowerCase(java.util.Locale.ROOT);
    }

    private static String attr(XmlPullParser p, String name) {
        for (int i = 0; i < p.getAttributeCount(); i++) {
            if (local(p.getAttributeName(i)).equals(name)) return p.getAttributeValue(i);
        }
        return null;
    }

    private static String readEntry(ZipFile zip, String path) {
        ZipEntry e = findEntry(zip, path);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (IOException e1) {
            return null;
        }
    }

    private static byte[] readBytes(ZipFile zip, String path) {
        ZipEntry e = findEntry(zip, path);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return readAll(in);
        } catch (IOException e1) {
            return null;
        }
    }

    private static ZipEntry findEntry(ZipFile zip, String path) {
        if (path == null) return null;
        String p = path;
        ZipEntry e = zip.getEntry(p);
        if (e != null) return e;
        e = zip.getEntry(decode(p));
        if (e != null) return e;
        e = zip.getEntry("./" + p);
        if (e != null) return e;
        return zip.getEntry("./" + decode(p));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        return out.toByteArray();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String dirname(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String join(String dir, String href) {
        String h = href;
        int hash = h.indexOf('#');
        if (hash >= 0) h = h.substring(0, hash);
        String combined = dir.isEmpty() ? h : dir + "/" + h;
        return normalize(combined);
    }

    private static String resolve(String base, String href) {
        return join(base, href);
    }

    private static String normalize(String path) {
        String[] parts = path.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(part);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String part : stack) {
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    private static String stripExtension(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
