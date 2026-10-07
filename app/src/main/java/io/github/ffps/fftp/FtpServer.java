package io.github.ffps.fftp;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Minimal FTP server (RFC 959 + the commonly used extensions: PASV/EPSV/PORT,
 * SIZE, MDTM, REST for downloads, UTF-8 names). Pure Java, no Android APIs,
 * so it works on any API level. No TLS, no IPv6 active mode.
 */
public class FtpServer {

    private final int port;
    private final String user;
    private final String pass;
    private final File root;

    private volatile ServerSocket server;
    private volatile boolean stopped;
    private final Set<Session> sessions = Collections.synchronizedSet(new HashSet<Session>());

    public FtpServer(int port, String user, String pass, File root) {
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
        this.root = root;
    }

    public void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        if (stopped) {
            close(s);
            return;
        }
        server = s;
        Thread t = new Thread(new Runnable() {
            public void run() {
                acceptLoop();
            }
        }, "fftp-accept");
        t.start();
    }

    public void stop() {
        stopped = true;
        close(server);
        Session[] all;
        synchronized (sessions) {
            all = sessions.toArray(new Session[0]);
        }
        for (Session s : all) s.close();
    }

    private void acceptLoop() {
        while (!stopped) {
            try {
                Socket c = server.accept();
                Session s = new Session(c);
                sessions.add(s);
                s.start();
            } catch (IOException e) {
                if (stopped) break;
            }
        }
    }

    private static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static void close(Socket c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    private static void close(ServerSocket c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------

    private class Session extends Thread {
        private final Socket ctrl;
        private BufferedReader in;
        private OutputStream out;

        private String cwd = "/";
        private String userName = "";
        private boolean authed;
        private File renameFrom;
        private long restOffset;

        private ServerSocket pasv;
        private InetSocketAddress active;
        private Socket data;

        private final SimpleDateFormat recent = new SimpleDateFormat("MMM dd HH:mm", Locale.US);
        private final SimpleDateFormat old = new SimpleDateFormat("MMM dd  yyyy", Locale.US);

        Session(Socket c) {
            super("fftp-session");
            ctrl = c;
        }

        void close() {
            FtpServer.close(ctrl);
            FtpServer.close(pasv);
            FtpServer.close(data);
        }

        @Override
        public void run() {
            try {
                ctrl.setSoTimeout(10 * 60 * 1000);
                in = new BufferedReader(new InputStreamReader(ctrl.getInputStream(), "UTF-8"));
                out = new BufferedOutputStream(ctrl.getOutputStream());
                reply(220, "fFTP ready");
                String line;
                while (!stopped && (line = in.readLine()) != null) {
                    if (line.length() == 0) continue;
                    if (!handle(line)) break;
                }
            } catch (IOException ignored) {
            } finally {
                close();
                sessions.remove(this);
            }
        }

        private void reply(int code, String text) throws IOException {
            raw(code + " " + text + "\r\n");
        }

        private void raw(String s) throws IOException {
            out.write(s.getBytes("UTF-8"));
            out.flush();
        }

        // ---- paths ----

        /** Resolves arg against cwd; result is a normalized virtual path below "/". */
        private String resolve(String arg) {
            String p = arg.startsWith("/") ? arg : (cwd.equals("/") ? "/" + arg : cwd + "/" + arg);
            LinkedList<String> parts = new LinkedList<String>();
            for (String s : p.split("/")) {
                if (s.length() == 0 || s.equals(".")) continue;
                if (s.equals("..")) {
                    if (!parts.isEmpty()) parts.removeLast();
                } else {
                    parts.add(s);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String s : parts) sb.append('/').append(s);
            return sb.length() == 0 ? "/" : sb.toString();
        }

        private File file(String vpath) {
            return vpath.equals("/") ? root : new File(root, vpath.substring(1));
        }

        private String quote(String s) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }

        // ---- commands ----

        private boolean handle(String line) throws IOException {
            int sp = line.indexOf(' ');
            String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.US);
            String arg = sp < 0 ? "" : line.substring(sp + 1).trim();

            if (!authed) {
                switch (cmd) {
                    case "USER":
                        userName = arg;
                        reply(331, "Password required");
                        return true;
                    case "PASS":
                        if (pass.length() == 0 || (userName.equals(user) && arg.equals(pass))) {
                            authed = true;
                            reply(230, "Logged in");
                        } else {
                            reply(530, "Login incorrect");
                        }
                        return true;
                    case "QUIT":
                        reply(221, "Bye");
                        return false;
                    case "FEAT":
                    case "OPTS":
                    case "SYST":
                    case "NOOP":
                        break;
                    default:
                        reply(530, "Please log in");
                        return true;
                }
            }

            switch (cmd) {
                case "QUIT":
                    reply(221, "Bye");
                    return false;
                case "NOOP":
                case "ALLO":
                case "MODE":
                case "STRU":
                case "TYPE":
                    reply(200, "OK");
                    break;
                case "SYST":
                    reply(215, "UNIX Type: L8");
                    break;
                case "FEAT":
                    raw("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n211 End\r\n");
                    break;
                case "OPTS":
                    if (arg.toUpperCase(Locale.US).startsWith("UTF8")) reply(200, "UTF8 on");
                    else reply(501, "Not supported");
                    break;
                case "PWD":
                case "XPWD":
                    reply(257, quote(cwd) + " is current directory");
                    break;
                case "CWD":
                case "XCWD":
                case "CDUP":
                case "XCUP": {
                    String v = resolve(cmd.endsWith("UP") ? ".." : arg);
                    if (file(v).isDirectory()) {
                        cwd = v;
                        reply(250, "OK");
                    } else {
                        reply(550, "No such directory");
                    }
                    break;
                }
                case "MKD":
                case "XMKD": {
                    String v = resolve(arg);
                    if (file(v).mkdir()) reply(257, quote(v) + " created");
                    else reply(550, "Cannot create directory");
                    break;
                }
                case "RMD":
                case "XRMD": {
                    File f = file(resolve(arg));
                    if (f.isDirectory() && f.delete()) reply(250, "Removed");
                    else reply(550, "Cannot remove directory (not empty?)");
                    break;
                }
                case "DELE": {
                    File f = file(resolve(arg));
                    if (f.isFile() && f.delete()) reply(250, "Deleted");
                    else reply(550, "Cannot delete");
                    break;
                }
                case "RNFR": {
                    File f = file(resolve(arg));
                    if (f.exists()) {
                        renameFrom = f;
                        reply(350, "Ready for RNTO");
                    } else {
                        reply(550, "No such file");
                    }
                    break;
                }
                case "RNTO": {
                    File to = file(resolve(arg));
                    if (renameFrom != null && renameFrom.renameTo(to)) reply(250, "Renamed");
                    else reply(550, "Rename failed");
                    renameFrom = null;
                    break;
                }
                case "SIZE": {
                    File f = file(resolve(arg));
                    if (f.isFile()) reply(213, String.valueOf(f.length()));
                    else reply(550, "No such file");
                    break;
                }
                case "MDTM": {
                    File f = file(resolve(arg));
                    if (f.exists()) {
                        SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
                        df.setTimeZone(TimeZone.getTimeZone("UTC"));
                        reply(213, df.format(new Date(f.lastModified())));
                    } else {
                        reply(550, "No such file");
                    }
                    break;
                }
                case "REST":
                    try {
                        restOffset = Math.max(0, Long.parseLong(arg));
                        reply(350, "Restarting at " + restOffset);
                    } catch (NumberFormatException e) {
                        reply(501, "Bad offset");
                    }
                    break;
                case "PASV":
                    cmdPasv(false);
                    break;
                case "EPSV":
                    if (arg.equalsIgnoreCase("ALL")) reply(200, "OK");
                    else cmdPasv(true);
                    break;
                case "PORT":
                    cmdPort(arg);
                    break;
                case "LIST":
                case "NLST":
                    cmdList(arg, cmd.equals("NLST"));
                    break;
                case "RETR":
                    cmdRetr(arg);
                    break;
                case "STOR":
                case "APPE":
                    cmdStor(arg, cmd.equals("APPE"));
                    break;
                case "ABOR":
                    reply(226, "OK");
                    break;
                default:
                    reply(502, "Command not implemented");
            }
            return true;
        }

        // ---- data connection ----

        private void resetData() {
            FtpServer.close(pasv);
            pasv = null;
            active = null;
        }

        private void cmdPasv(boolean extended) throws IOException {
            resetData();
            try {
                pasv = new ServerSocket(0, 1, ctrl.getLocalAddress());
            } catch (IOException e) {
                reply(425, "Cannot open passive port");
                return;
            }
            int p = pasv.getLocalPort();
            if (extended) {
                reply(229, "Entering Extended Passive Mode (|||" + p + "|)");
            } else {
                byte[] a = ctrl.getLocalAddress().getAddress();
                if (a.length != 4) {
                    resetData();
                    reply(502, "Use EPSV");
                    return;
                }
                reply(227, "Entering Passive Mode (" + (a[0] & 255) + "," + (a[1] & 255) + ","
                        + (a[2] & 255) + "," + (a[3] & 255) + "," + (p >> 8) + "," + (p & 255) + ")");
            }
        }

        private void cmdPort(String arg) throws IOException {
            String[] n = arg.split(",");
            if (n.length != 6) {
                reply(501, "Bad PORT");
                return;
            }
            try {
                int port = (Integer.parseInt(n[4].trim()) << 8) | Integer.parseInt(n[5].trim());
                String host = n[0].trim() + "." + n[1].trim() + "." + n[2].trim() + "." + n[3].trim();
                resetData();
                active = new InetSocketAddress(host, port);
                reply(200, "PORT OK");
            } catch (NumberFormatException e) {
                reply(501, "Bad PORT");
            }
        }

        /** Sends 150 and opens the data socket; returns null (after replying 425) on failure. */
        private Socket openData() throws IOException {
            if (pasv == null && active == null) {
                reply(425, "Use PASV or PORT first");
                return null;
            }
            reply(150, "Opening data connection");
            Socket s = null;
            try {
                if (pasv != null) {
                    pasv.setSoTimeout(30000);
                    s = pasv.accept();
                } else {
                    s = new Socket();
                    s.connect(active, 15000);
                }
                s.setSoTimeout(60000);
            } catch (IOException e) {
                FtpServer.close(s);
                s = null;
            } finally {
                resetData();
            }
            if (s == null) {
                reply(425, "Cannot open data connection");
                return null;
            }
            data = s;
            return s;
        }

        private void closeData() {
            FtpServer.close(data);
            data = null;
        }

        private void cmdList(String arg, boolean namesOnly) throws IOException {
            if (arg.startsWith("-")) {
                int i = arg.indexOf(' ');
                arg = i < 0 ? "" : arg.substring(i + 1).trim();
            }
            File target = file(arg.length() == 0 ? cwd : resolve(arg));
            if (!target.exists()) {
                reply(550, "No such file or directory");
                return;
            }
            File[] list = target.isDirectory() ? target.listFiles() : new File[]{target};
            if (list == null) list = new File[0];
            Arrays.sort(list, new Comparator<File>() {
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            Socket s = openData();
            if (s == null) return;
            try {
                OutputStream o = new BufferedOutputStream(s.getOutputStream());
                long now = System.currentTimeMillis();
                for (File f : list) {
                    String row;
                    if (namesOnly) {
                        row = f.getName();
                    } else {
                        long t = f.lastModified();
                        boolean recentFile = Math.abs(now - t) < 180L * 24 * 3600 * 1000;
                        row = (f.isDirectory() ? "drwxr-xr-x" : "-rw-r--r--") + " 1 ftp ftp "
                                + pad(f.isDirectory() ? 4096 : f.length()) + " "
                                + (recentFile ? recent : old).format(new Date(t)) + " " + f.getName();
                    }
                    o.write((row + "\r\n").getBytes("UTF-8"));
                }
                o.flush();
                closeData();
                reply(226, "Transfer complete");
            } catch (IOException e) {
                closeData();
                reply(426, "Transfer aborted");
            }
        }

        private String pad(long n) {
            String s = String.valueOf(n);
            StringBuilder sb = new StringBuilder();
            for (int i = s.length(); i < 12; i++) sb.append(' ');
            return sb.append(s).toString();
        }

        private void cmdRetr(String arg) throws IOException {
            File f = file(resolve(arg));
            if (!f.isFile()) {
                reply(550, "No such file");
                return;
            }
            InputStream fin = null;
            try {
                fin = new FileInputStream(f);
            } catch (IOException e) {
                reply(550, "Cannot open file");
                return;
            }
            long skip = restOffset;
            restOffset = 0;
            Socket s = null;
            try {
                while (skip > 0) {
                    long k = fin.skip(skip);
                    if (k <= 0) break;
                    skip -= k;
                }
                s = openData();
                if (s == null) return;
                copy(fin, s.getOutputStream());
                closeData();
                reply(226, "Transfer complete");
            } catch (IOException e) {
                closeData();
                reply(426, "Transfer aborted");
            } finally {
                FtpServer.close(fin);
            }
        }

        private void cmdStor(String arg, boolean append) throws IOException {
            File f = file(resolve(arg));
            if (f.isDirectory()) {
                reply(550, "Is a directory");
                return;
            }
            restOffset = 0;
            OutputStream fout;
            try {
                fout = new FileOutputStream(f, append);
            } catch (IOException e) {
                reply(550, "Cannot write file");
                return;
            }
            try {
                Socket s = openData();
                if (s == null) return;
                copy(s.getInputStream(), fout);
                closeData();
                reply(226, "Transfer complete");
            } catch (IOException e) {
                closeData();
                reply(426, "Transfer aborted");
            } finally {
                FtpServer.close(fout);
            }
        }

        private void copy(InputStream from, OutputStream to) throws IOException {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = from.read(buf)) != -1) to.write(buf, 0, n);
            to.flush();
        }
    }
}
