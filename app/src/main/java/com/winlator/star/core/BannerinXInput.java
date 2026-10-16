package com.winlator.star.core;

import android.content.Context;
import android.util.Log;

import com.winlator.star.container.Container;
import com.winlator.star.container.Shortcut;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * "Direct XInput (BANNERIN)": the BANNERIN-XInput DLLs in a prefix, so XInput games read the pad
 * straight from the BANNERIN slot files instead of through Wine's winebus/hidclass report queue.
 *
 * <p><b>The bundle.</b> CI builds the DLLs from {@code app/src/main/cpp/bannerin/xinput} (one source,
 * one DLL per XInput name: xinput1_1, 1_2, 1_3, 1_4, 9_1_0 and xinputuap, each with its own export
 * table) for x86_64, i386 and ARM64X into the assets folder {@code bannerin-xinput/}, with a
 * manifest.json of their sha256s. Nothing binary is committed; a build without that step carries no
 * bundle and the setting does nothing.
 *
 * <p><b>The prefix.</b> While the setting is on, every launch makes sure system32 holds the 64-bit
 * build and syswow64 the i386 build. In an x86_64 prefix (box64) that is the x86_64 build. In an
 * arm64ec prefix it is the ARM64X build: one image with an ARM64EC view, which x64 games load, and a
 * native ARM64 view, which Wine's own ARM64 programs load (control.exe with joy.cpl, explorer, ...);
 * an x64 or ARM64EC-only image there fails to load in every native ARM64 process (c000007b), so
 * {@code BANNERIN_XINPUT_ARCH=x86_64} (the x64 build there instead) is for diagnosis only,
 * and the session gets {@code WINEDLLOVERRIDES} xinput1_1,...,xinputuap=n,b. Whatever was in a slot
 * before (Wine's placeholder dlls) is moved to {@code <prefix>/bannerlator-bxinput/backup/} once,
 * every copy goes through a temporary file and a rename, and what was installed is recorded in
 * {@code installed.properties} before it is put in place, so an interrupted launch leaves nothing the
 * next one cannot finish or undo. With the setting off, the next launch puts the prefix's own files
 * back and nothing is overridden, so Wine's builtin xinput is used. A slot counts as ours only when
 * its sha256 is one we installed; a file that replaced ours since is never deleted.
 *
 * <p><b>Switch.</b> Per container ({@link Container#isBannerinXInput()}, default off), per game (the
 * shortcut extra {@value #EXTRA_KEY}, "1"/"0", empty = the container's), and for testing the env var
 * {@value #ENV_SWITCH}=1/0 in the container's or the game's environment, which wins over both.
 */
public final class BannerinXInput {
    private BannerinXInput() {}

    private static final String TAG = "BannerinXInput";

    static final String ASSET_DIR = "bannerin-xinput";
    public static final String ENV_SWITCH = "BANNERIN_XINPUT";
    public static final String ENV_ARCH = "BANNERIN_XINPUT_ARCH";
    public static final String EXTRA_KEY = "bannerinXInput";

    /** The DLL names it stands in for (and that the WINEDLLOVERRIDES entry covers). */
    public static final String[] DLLS = {"xinput1_1", "xinput1_2", "xinput1_3", "xinput1_4", "xinput9_1_0", "xinputuap"};
    private static final String OVERRIDE_MODE = "n,b";

    private static final String STATE_DIR = "bannerlator-bxinput";
    private static final String MARKER = "installed.properties";

    // ── Which way this launch goes ───────────────────────────────────────────────────────────────

    /** On or off for this launch: env var (game's, then container's) > game extra > container. */
    public static boolean effective(Container container, Shortcut shortcut) {
        String env = null;
        if (shortcut != null) env = envValue(shortcut.getExtra("envVars"));
        if (env == null && container != null) env = envValue(container.getEnvVars());
        if ("1".equals(env)) return true;
        if ("0".equals(env)) return false;
        if (shortcut != null) {
            String extra = shortcut.getExtra(EXTRA_KEY);
            if ("1".equals(extra)) return true;
            if ("0".equals(extra)) return false;
        }
        return container != null && container.isBannerinXInput();
    }

    /** {@value #ENV_ARCH} from the game's or the container's env vars, or null. */
    public static String archOverride(Container container, Shortcut shortcut) {
        String v = null;
        if (shortcut != null) v = envGet(shortcut.getExtra("envVars"), ENV_ARCH);
        if (v == null && container != null) v = envGet(container.getEnvVars(), ENV_ARCH);
        return v;
    }

    private static String envValue(String raw) {
        String v = envGet(raw, ENV_SWITCH);
        if (v == null) return null;
        v = v.trim().toLowerCase(Locale.ROOT);
        if (v.equals("1") || v.equals("on") || v.equals("true")) return "1";
        if (v.equals("0") || v.equals("off") || v.equals("false")) return "0";
        return null;
    }

    private static String envGet(String raw, String key) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            EnvVars e = new EnvVars(raw);
            return e.has(key) ? e.get(key) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ── The bundle ───────────────────────────────────────────────────────────────────────────────

    static final class Bundle {
        final String version;
        /** "x86_64/xinput1_4.dll" → sha256 */
        final Map<String, String> files;

        Bundle(String version, Map<String, String> files) {
            this.version = version;
            this.files = files;
        }

        boolean has(String arch) {
            for (String d : DLLS) if (!files.containsKey(arch + "/" + d + ".dll")) return false;
            return true;
        }
    }

    private static volatile Bundle cachedBundle;
    private static volatile boolean bundleRead;

    static Bundle bundle(Context context) {
        if (bundleRead) return cachedBundle;
        Bundle b = null;
        try {
            JSONObject m = new JSONObject(FileUtils.readString(context, ASSET_DIR + "/manifest.json"));
            JSONArray arr = m.getJSONArray("files");
            Map<String, String> files = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject f = arr.getJSONObject(i);
                files.put(f.getString("asset"), f.getString("sha256").toLowerCase(Locale.ROOT));
            }
            if (!files.isEmpty()) b = new Bundle(m.optString("version", "?"), files);
        } catch (Exception e) {
            Log.w(TAG, "no bundled BANNERIN-XInput in this build: " + e);
        }
        cachedBundle = b;
        bundleRead = true;
        return b;
    }

    // ── The prefix ───────────────────────────────────────────────────────────────────────────────

    /** What {@link #sync} did, for the session log. */
    public static final class Result {
        public final boolean on;
        /** On: the DLLs are in place, so the override may be set. */
        public final boolean installed;
        /** One line for the session log, or null when there is nothing to say (off, never on). */
        public final String detail;

        Result(boolean on, boolean installed, String detail) {
            this.on = on;
            this.installed = installed;
            this.detail = detail;
        }
    }

    /**
     * Bring the prefix at {@code prefixDir} (the .wine folder) in line with the setting. Safe on every
     * launch and after an interrupted one. Never throws.
     *
     * @param arm64ec the container's Wine is arm64ec (system32 then gets the arm64ec build)
     * @param arch    {@value #ENV_ARCH} ("x86_64" forces the x64 build into an arm64ec prefix), or null
     */
    public static Result sync(Context context, File prefixDir, boolean on, boolean arm64ec, String arch) {
        File windows = new File(prefixDir, "drive_c/windows");
        File state = new File(prefixDir, STATE_DIR);
        File marker = new File(state, MARKER);
        try {
            return on ? install(context, windows, state, marker, arm64ec, arch)
                      : restore(windows, state, marker);
        } catch (Throwable t) {
            Log.w(TAG, "sync failed", t);
            return new Result(on, false, (on ? "could not install BANNERIN-XInput: " : "could not restore the prefix's xinput dlls: ") + t);
        }
    }

    private static Result install(Context context, File windows, File state, File marker, boolean arm64ec, String archOverride) throws Exception {
        Bundle b = bundle(context);
        if (b == null) return new Result(true, false, "not in this build (no bundled BANNERIN-XInput), nothing installed");
        // x86_64 prefix: x86_64. arm64ec prefix: ARM64X (see the class comment), unless forced to x86_64.
        boolean forceX64 = "x86_64".equalsIgnoreCase(archOverride != null ? archOverride.trim() : "");
        String sys64 = arm64ec && !forceX64 ? "arm64x" : "x86_64";
        if (!b.has(sys64)) return new Result(true, false, "the bundle has no " + sys64 + " build, nothing installed");
        Properties m = load(marker);
        List<String> did = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int placed = 0;
        String[][] dirs = {{"system32", sys64}, {"syswow64", "i386"}};
        for (String[] dir : dirs) {
            File folder = new File(windows, dir[0]);
            // A prefix without the folder (no syswow64 = no 32-bit side) doesn't get one made for us.
            if (!folder.isDirectory()) {
                skipped.add(dir[0] + " (not in this prefix)");
                continue;
            }
            if (!b.has(dir[1])) {
                skipped.add(dir[0] + " (no " + dir[1] + " build bundled)");
                continue;
            }
            int changed = 0;
            for (String dll : DLLS) {
                String slot = dir[0] + "/" + dll + ".dll";
                String asset = dir[1] + "/" + dll + ".dll";
                String want = b.files.get(asset);
                File target = new File(windows, slot);
                File backup = new File(state, "backup/" + slot);
                String have = target.isFile() ? sha256(target) : null;
                boolean ours = have != null && isOurs(have, slot, m);
                // An original we haven't kept yet: keep it before anything overwrites it (once).
                if (have != null && !ours && !backup.isFile()) {
                    copyAtomically(new FileInputStream(target), backup);
                    m.setProperty(slot, "backup");
                    store(m, marker);
                } else if (m.getProperty(slot) == null) {
                    m.setProperty(slot, backup.isFile() ? "backup" : "absent");
                    store(m, marker);
                }
                placed++;
                if (want.equals(have)) continue;
                File tmp = new File(folder, "." + target.getName() + ".bannerlator-tmp");
                String sha;
                try (InputStream in = context.getAssets().open(ASSET_DIR + "/" + asset)) {
                    sha = copyTo(in, tmp);
                }
                if (!want.equals(sha)) {
                    tmp.delete();
                    throw new IllegalStateException(asset + " in the APK does not match its manifest hash");
                }
                addSha(m, slot, sha);
                store(m, marker);
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    throw new java.io.IOException("could not put " + slot + " in place");
                }
                changed++;
            }
            if (changed > 0) did.add(dir[0] + " (" + dir[1] + ", " + changed + " dll" + (changed == 1 ? "" : "s") + ")");
        }
        if (placed == 0) return new Result(true, false, "nothing installed: " + String.join(", ", skipped));
        m.setProperty("version", b.version);
        m.setProperty("system32", sys64);
        store(m, marker);
        String detail = "BANNERIN-XInput " + b.version + (did.isEmpty()
                ? " in place (system32 " + sys64 + (new File(windows, "syswow64").isDirectory() ? " + syswow64 i386" : "") + ")"
                : " installed: " + String.join(", ", did))
                + (skipped.isEmpty() ? "" : "; skipped " + String.join(", ", skipped));
        Log.i(TAG, detail);
        return new Result(true, true, detail);
    }

    private static Result restore(File windows, File state, File marker) throws Exception {
        if (!marker.isFile()) return new Result(false, false, null); // never installed here
        Properties m = load(marker);
        List<String> back = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        for (String slot : slotPaths(m)) {
            File target = new File(windows, slot);
            File backup = new File(state, "backup/" + slot);
            if (target.isFile() && !isOurs(sha256(target), slot, m)) {
                // Not ours: the original is already back (an interrupted restore) or something replaced
                // our dll since (a Wine update). Leave it; the backup is older than what is there.
                if (backup.isFile()) backup.delete();
                kept.add(slot);
            } else {
                if (target.isFile() && !target.delete()) throw new java.io.IOException("could not remove " + slot);
                if (backup.isFile()) {
                    if (!backup.renameTo(target)) {
                        copyAtomically(new FileInputStream(backup), target);
                        backup.delete();
                    }
                }
                back.add(slot);
            }
            m.remove(slot);
            m.remove(slot + ".sha256");
            store(m, marker);
        }
        marker.delete();
        deleteEmptyDirs(new File(state, "backup"));
        state.delete(); // only if empty
        String detail = "BANNERIN-XInput removed: " + back.size() + " dll(s) back to the prefix's own (Wine's builtin xinput)"
                + (kept.isEmpty() ? "" : "; left as is (not ours): " + String.join(", ", kept));
        Log.i(TAG, detail);
        return new Result(false, false, detail);
    }

    private static boolean isOurs(String sha, String slot, Properties m) {
        if (sha == null) return false;
        for (String h : m.getProperty(slot + ".sha256", "").split(",")) if (h.equals(sha)) return true;
        return false;
    }

    private static void addSha(Properties m, String slot, String sha) {
        String key = slot + ".sha256";
        String rec = m.getProperty(key, "");
        for (String h : rec.split(",")) if (h.equals(sha)) return;
        m.setProperty(key, rec.isEmpty() ? sha : rec + "," + sha);
    }

    private static List<String> slotPaths(Properties m) {
        List<String> r = new ArrayList<>();
        for (String k : m.stringPropertyNames()) if (k.endsWith(".dll")) r.add(k);
        return r;
    }

    // ── Environment ──────────────────────────────────────────────────────────────────────────────

    /**
     * {@code existing} (a WINEDLLOVERRIDES value, may be null/empty) with "xinput1_1,...=n,b" added for
     * the dlls it doesn't already name: an override the user set for one stays theirs (reported via
     * {@code keptOut}). Grouped entries ("a,b=n") are understood.
     */
    public static String withDllOverrides(String existing, List<String> keptOut) {
        Set<String> named = new LinkedHashSet<>();
        String e = existing == null ? "" : existing.trim();
        for (String entry : e.split(";")) {
            int eq = entry.indexOf('=');
            String dlls = eq >= 0 ? entry.substring(0, eq) : entry;
            for (String d : dlls.split(",")) {
                String n = d.trim().toLowerCase(Locale.ROOT);
                if (n.endsWith(".dll")) n = n.substring(0, n.length() - 4);
                if (!n.isEmpty()) named.add(n);
            }
        }
        List<String> add = new ArrayList<>();
        for (String d : DLLS) {
            if (named.contains(d)) { if (keptOut != null) keptOut.add(d); }
            else add.add(d);
        }
        if (add.isEmpty()) return e;
        String ours = String.join(",", add) + "=" + OVERRIDE_MODE;
        if (e.isEmpty()) return ours;
        return e.endsWith(";") ? e + ours : e + ";" + ours;
    }

    // ── Files ────────────────────────────────────────────────────────────────────────────────────

    private static Properties load(File marker) {
        Properties p = new Properties();
        if (marker.isFile()) {
            try (InputStream in = new FileInputStream(marker)) { p.load(in); } catch (Exception ignored) {}
        }
        return p;
    }

    private static void store(Properties p, File marker) throws Exception {
        File dir = marker.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File tmp = new File(dir, marker.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            p.store(out, "Bannerlator: BANNERIN-XInput installed into this prefix (see core/BannerinXInput)");
            out.getFD().sync();
        }
        if (!tmp.renameTo(marker)) throw new java.io.IOException("could not write " + marker);
    }

    private static String copyAtomically(InputStream in, File dst) throws Exception {
        File dir = dst.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        File tmp = new File(dir, "." + dst.getName() + ".bannerlator-tmp");
        String sha;
        try (InputStream src = in) { sha = copyTo(src, tmp); }
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            throw new java.io.IOException("could not write " + dst);
        }
        return sha;
    }

    private static String copyTo(InputStream in, File dst) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
            }
            out.getFD().sync();
        }
        return hex(md.digest());
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte x : d) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static void deleteEmptyDirs(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteEmptyDirs(k);
        dir.delete(); // only succeeds when empty
    }
}
