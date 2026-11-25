package dev.nikita.voice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Streams signed APK entries to the same file layout the native engines use. */
final class EmbeddedModelFiles {
    static final String BASE = "assets/embedded_models/";
    static final String INDEX = "pack-v1.json";
    private static final long RESERVE = 32L * 1024L * 1024L;
    private static final int BUFFER = 256 * 1024;

    interface Progress {
        void update(long done, long total);
    }

    static final class Model {
        final String path;
        final long size;
        final String digest;
        Model(String path, long size, String digest) {
            this.path = path;
            this.size = size;
            this.digest = digest;
        }
    }

    static byte[] readSmall(InputStream stream) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > 128 * 1024) throw new IOException("Слишком большой индекс моделей.");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] value) {
        char[] chars = "0123456789abcdef".toCharArray();
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(chars[(b & 255) >>> 4]).append(chars[b & 15]);
        return out.toString();
    }

    private static void directory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Не удалось открыть хранилище приложения.");
        }
    }

    private static void discard(File f) throws IOException {
        if (f.exists() && !f.delete()) throw new IOException("Не удалось удалить временный файл.");
    }

    private static void replace(File from, File to) throws IOException {
        // On Android these are same-filesystem POSIX rename operations.
        if (!from.renameTo(to)) throw new IOException("Не удалось сохранить файл " + to.getName() + ".");
    }

    private static String stamp(File file, Model model) {
        return model.digest + "/" + file.length() + "/" + file.lastModified();
    }

    private static void saveStamps(Properties props, File file) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            props.store(out, null);
            out.getFD().sync();
        }
        replace(temp, file);
    }

    private static boolean matches(File file, Model m, byte[] buf) throws IOException {
        if (!file.isFile() || file.length() != m.size) return false;
        MessageDigest sha = sha();
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) sha.update(buf, 0, n);
        }
        return m.digest.equals(hex(sha.digest()));
    }

    static void install(ZipFile archive, byte[] index, List<Model> models, File root,
                        File receipts, Model cache, Progress progress) throws IOException {
        directory(root);
        directory(receipts.getParentFile());
        String prefix = root.getCanonicalPath() + File.separator;
        Properties stamps = new Properties();
        if (receipts.isFile()) {
            try (InputStream in = new FileInputStream(receipts)) {
                stamps.load(in);
            } catch (IOException | IllegalArgumentException ignored) {
                stamps.clear(); // Recover from a lost receipt by verifying existing files.
            }
        }
        List<Model> pending = new ArrayList<>();
        byte[] buf = new byte[BUFFER];
        long total = 0, required = 0;
        Set<String> seen = new HashSet<>();
        for (Model m : models) {
            File file = new File(root, m.path);
            ZipEntry entry = archive.getEntry(BASE + m.path);
            if (m.size < 1 || !m.digest.matches("[0-9a-f]{64}") ||
                    m.path.startsWith("/") || !file.getCanonicalPath().startsWith(prefix) ||
                    !seen.add(file.getCanonicalPath()) || entry == null || entry.getSize() != m.size) {
                throw new IOException("Повреждён комплект моделей в приложении.");
            }
            total = Math.addExact(total, m.size);
            discard(new File(file.getParentFile(), file.getName() + ".sv-tmp"));
            if (file.isFile() && file.length() == m.size && stamp(file, m).equals(stamps.getProperty(m.path))) continue;
            if (matches(file, m, buf)) {
                stamps.setProperty(m.path, stamp(file, m));
                saveStamps(stamps, receipts);
            } else {
                pending.add(m);
                required = Math.addExact(required, m.size);
            }
        }
        if (total == 0) throw new IOException("В комплекте нет моделей.");
        long done = total - required;
        progress.update(done, total);
        long cacheBytes = 0;
        if (cache != null) {
            File decoder = new File(root, cache.path);
            if (!decoder.isFile() || decoder.length() != cache.size) {
                discard(new File(decoder.getParentFile(), decoder.getName() + ".part"));
                cacheBytes = cache.size;
            }
        }
        long freeNeeded = Math.addExact(required, cacheBytes);
        if (freeNeeded > 0 && root.getUsableSpace() < freeNeeded + RESERVE) {
            throw new IOException(String.format(Locale.forLanguageTag("ru"),
                    "Освободите место на телефоне. Для подготовки нужно ещё %.2f ГБ свободного места.",
                    (freeNeeded + RESERVE) / 1.0e9));
        }
        for (Model m : pending) {
            File file = new File(root, m.path);
            directory(file.getParentFile());
            File temp = new File(file.getParentFile(), file.getName() + ".sv-tmp");
            try {
                long copied = 0;
                MessageDigest digest = sha();
                try (InputStream in = archive.getInputStream(archive.getEntry(BASE + m.path));
                     FileOutputStream out = new FileOutputStream(temp)) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        copied += n;
                        if (copied > m.size) throw new IOException("Повреждён файл " + m.path + ".");
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                        progress.update(done + copied, total);
                    }
                    if (copied != m.size || !m.digest.equals(hex(digest.digest()))) {
                        throw new IOException("Проверка файла " + m.path + " не пройдена.");
                    }
                    out.getFD().sync();
                }
                replace(temp, file);
            } finally {
                // A killed process can leave a partial file. The next run removes it as well.
                if (temp.exists()) temp.delete();
            }
            stamps.setProperty(m.path, stamp(file, m));
            saveStamps(stamps, receipts);
            done += m.size;
        }
        File target = new File(root, INDEX);
        if (target.isFile() && target.length() == index.length &&
                Arrays.equals(index, readSmall(new FileInputStream(target)))) return;
        File temp = new File(root, INDEX + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(index);
            out.getFD().sync();
        }
        replace(temp, target);
    }
}
