package ru.iqwanoino.devhosy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal FTP server using passive data connections. Use on a trusted local network only. */
public class FtpServerService extends Service {
    public static volatile boolean running = false;
    public static volatile String activeRoot = "";
    public static volatile String lastError = "";
    public static volatile int connections = 0;
    public static volatile long uploads = 0;
    public static volatile long downloads = 0;
    public static volatile int port = 2121;
    /** Login anonim (user anonymous/ftp, password apa saja), hanya baca. */
    public static volatile boolean anonEnabled = false;

    private static final String CHANNEL = "devhosy_ftp_server";
    private volatile ServerSocket controlServer;
    private volatile boolean stopRequested;
    private ExecutorService sessions;

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences("devhosy", MODE_PRIVATE);
        port = p.getInt("server_port", 2121);
        anonEnabled = p.getBoolean("server_anon", false);
        String configuredRoot = p.getString("server_root", Environment.getExternalStorageDirectory().getAbsolutePath());
        File root = new File(configuredRoot);
        if (!root.exists() || !root.isDirectory() || !root.canRead()) {
            root = Environment.getExternalStorageDirectory();
        }
        activeRoot = root.getAbsolutePath();
        lastError = "";
        stopRequested = false;
        startForeground(40, buildNotification("Menyiapkan FTP server…"));
        if (controlServer != null && !controlServer.isClosed()) return START_STICKY;
        sessions = Executors.newCachedThreadPool();
        final File serverRoot = root;
        new Thread(() -> {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 32);
                if (stopRequested) { try { server.close(); } catch (Exception ignored) { } return; }
                controlServer = server;
                running = true;
                updateNotification("FTP aktif · port " + port);
                while (running && !server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        client.setSoTimeout(0);
                        connections++;
                        sessions.execute(new ClientSession(client, serverRoot));
                    } catch (SocketException e) { if (running) lastError = e.getMessage(); break; }
                    catch (IOException e) { if (running) lastError = e.getMessage(); }
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                running = false;
                updateNotification("Server gagal: " + lastError);
                stopSelf();
            }
        }, "Devhosy-Ftp-Acceptor").start();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopRequested = true;
        running = false;
        try { if (controlServer != null) controlServer.close(); } catch (Exception ignored) { }
        if (sessions != null) sessions.shutdownNow();
        controlServer = null;
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Devhosy FTP Server", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Status server FTP lokal");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }
    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Devhosy FTP Server").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true);
        return b.build();
    }
    private void updateNotification(String text) {
        try { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(40, buildNotification(text)); } catch (Exception ignored) { }
    }

    private final class ClientSession implements Runnable {
        private final Socket socket;
        private final File root;
        private BufferedReader in;
        private BufferedWriter out;
        private ServerSocket passive;
        private String cwd = "/";
        private String pendingRename;
        private boolean loggedIn;
        private boolean anonymous;
        private final SharedPreferences prefs = getSharedPreferences("devhosy", MODE_PRIVATE);
        ClientSession(Socket s, File r) { socket=s; root=r; }
        @Override public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                reply(220, "Devhosy FTP ready");
                String line;
                while (running && (line=in.readLine())!=null) {
                    int sp=line.indexOf(' ');
                    String cmd=(sp<0?line:line.substring(0,sp)).trim().toUpperCase(Locale.US);
                    String arg=sp<0?"":line.substring(sp+1).trim();
                    try {
                        if (cmd.equals("QUIT")) { reply(221,"Goodbye"); break; }
                        if (cmd.equals("USER")) { loginUser = arg; reply(331,"Password required"); continue; }
                        if (cmd.equals("PASS")) {
                            String expectedUser=prefs.getString("server_user","devhosy");
                            String expectedPass=prefs.getString("server_pass","devhosy123");
                            // USER is cached per connection.
                            boolean anonLogin = anonEnabled && loginUser != null
                                    && (loginUser.equalsIgnoreCase("anonymous") || loginUser.equalsIgnoreCase("ftp"));
                            if (anonLogin) { anonymous=true; loggedIn=true; reply(230,"Anonymous access granted (read-only)"); }
                            else if (loginUser != null && loginUser.equals(expectedUser) && arg.equals(expectedPass)) { anonymous=false; loggedIn=true; reply(230,"Login successful"); }
                            else reply(530,"Invalid username or password");
                            continue;
                        }
                        if (cmd.equals("NOOP")) { reply(200,"OK"); continue; }
                        if (cmd.equals("SYST")) { reply(215,"UNIX Type: L8"); continue; }
                        if (cmd.equals("FEAT")) { multiline("211-Features", " PASV", " SIZE", " UTF8", "211 End"); continue; }
                        if (!loggedIn) { reply(530,"Please login with USER and PASS"); continue; }
                        if (anonymous && (cmd.equals("STOR") || cmd.equals("DELE") || cmd.equals("MKD")
                                || cmd.equals("RMD") || cmd.equals("RNFR") || cmd.equals("RNTO"))) {
                            reply(550,"Akses anonim hanya baca");
                            continue;
                        }
                        switch (cmd) {
                            case "PWD": reply(257,"\""+cwd+"\" is current directory"); break;
                            case "TYPE": reply(200,"Type set"); break;
                            case "OPTS": reply(200,"Options accepted"); break;
                            case "CWD": {
                                String target=normalize(cwd,arg.isEmpty()?"/":arg);
                                File f=resolve(target);
                                if(f.isDirectory() && f.canRead()) { cwd=target; reply(250,"Directory changed"); }
                                else reply(550,"Directory unavailable");
                                break;
                            }
                            case "CDUP": { cwd=normalize(cwd,".."); reply(250,"Directory changed"); break; }
                            case "PASV": openPassive(); break;
                            case "PORT": reply(502,"Active data mode is not supported; use PASV"); break;
                            case "LIST": case "NLST": doList(arg, cmd.equals("NLST")); break;
                            case "RETR": doRetr(arg); break;
                            case "STOR": doStor(arg); break;
                            case "SIZE": {
                                File f=resolve(normalize(cwd,arg)); if(f.isFile()) reply(213,Long.toString(f.length())); else reply(550,"File not found"); break;
                            }
                            case "DELE": { File f=resolve(normalize(cwd,arg)); if(f.isFile()&&f.delete()) reply(250,"Deleted"); else reply(550,"Delete failed"); break; }
                            case "MKD": { File f=resolve(normalize(cwd,arg)); if(f.mkdir()) reply(257,"\""+normalize(cwd,arg)+"\" created"); else reply(550,"Create directory failed"); break; }
                            case "RMD": { File f=resolve(normalize(cwd,arg)); if(f.isDirectory()&&f.delete()) reply(250,"Directory removed"); else reply(550,"Remove directory failed"); break; }
                            case "RNFR": { File f=resolve(normalize(cwd,arg)); if(f.exists()) { pendingRename=f.getAbsolutePath(); reply(350,"Send RNTO"); } else reply(550,"Not found"); break; }
                            case "RNTO": {
                                if(pendingRename==null) { reply(503,"Use RNFR first"); break; }
                                File dest=resolve(normalize(cwd,arg)); boolean ok=new File(pendingRename).renameTo(dest); pendingRename=null; reply(ok?250:550,ok?"Renamed":"Rename failed"); break;
                            }
                            case "ABOR": closePassive(); reply(226,"Transfer aborted"); break;
                            default: reply(502,"Command not implemented"); break;
                        }
                    } catch (Exception e) { try { reply(550,"Operation failed: "+shortMessage(e)); } catch(Exception ignored){} }
                }
            } catch (Exception ignored) {
            } finally {
                closePassive(); try { socket.close(); } catch(Exception ignored) { }
                connections=Math.max(0,connections-1);
            }
        }
        private String loginUser;
        private void multiline(String... lines) throws IOException { for(String l:lines) { out.write(l); out.write("\r\n"); } out.flush(); }
        private void reply(int code,String msg) throws IOException { out.write(String.format(Locale.US,"%d %s\r\n",code,msg)); out.flush(); }
        private String shortMessage(Exception e) { String s=e.getMessage(); return s==null?e.getClass().getSimpleName():s; }
        private void openPassive() throws IOException {
            closePassive();
            int lo=prefs.getInt("pasv_min",50000), hi=prefs.getInt("pasv_max",50100);
            lo=Math.max(1024,Math.min(65534,lo)); hi=Math.max(lo,Math.min(65535,hi));
            IOException failure=null;
            for(int p=lo;p<=hi;p++) { try { passive=new ServerSocket(); passive.setReuseAddress(true); passive.bind(new java.net.InetSocketAddress(p)); passive.setSoTimeout(20000); break; } catch(IOException e){ failure=e; } }
            if(passive==null) { reply(425,"Cannot open passive port range: "+(failure==null?"unavailable":shortMessage(failure))); return; }
            String ip=socket.getLocalAddress().getHostAddress();
            if(ip.indexOf(':')>=0) { reply(522,"Use an IPv4 connection for PASV"); closePassive(); return; }
            int p=passive.getLocalPort(); String[] oct=ip.split("\\.");
            if(oct.length!=4) { reply(425,"Cannot determine IPv4 address"); closePassive(); return; }
            reply(227,"Entering Passive Mode ("+oct[0]+","+oct[1]+","+oct[2]+","+oct[3]+","+(p/256)+","+(p%256)+")");
        }
        private void closePassive() { if(passive!=null) { try{passive.close();}catch(Exception ignored){} passive=null; } }
        private Socket acceptData() throws IOException { if(passive==null) throw new IOException("Send PASV before transfer"); ServerSocket ss=passive; passive=null; try { return ss.accept(); } finally { try{ss.close();}catch(Exception ignored){} } }
        private void doList(String arg, boolean namesOnly) throws IOException {
            String path=arg.isEmpty()?cwd:normalize(cwd,arg); File dir=resolve(path);
            if(!dir.isDirectory()) { reply(550,"Not a directory"); return; }
            reply(150,"Opening data connection");
            try(Socket data=acceptData(); OutputStream os=data.getOutputStream()) {
                File[] files=dir.listFiles(); if(files!=null) {
                    java.util.Arrays.sort(files,(a,b)->a.getName().compareToIgnoreCase(b.getName()));
                    SimpleDateFormat sdf=new SimpleDateFormat("MMM dd  yyyy",Locale.US);
                    for(File f:files) {
                        String name=f.getName().replace("\r","").replace("\n","");
                        String line=namesOnly?name:(f.isDirectory()?"drwxr-xr-x":"-rw-r--r--")+" 1 owner group "+f.length()+" "+sdf.format(new Date(f.lastModified()))+" "+name;
                        os.write((line+"\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                os.flush();
            } finally { closePassive(); }
            reply(226,"Transfer complete");
        }
        private void doRetr(String arg) throws IOException {
            File f=resolve(normalize(cwd,arg)); if(!f.isFile()||!f.canRead()) { reply(550,"File unavailable"); return; }
            reply(150,"Opening binary data connection");
            try(Socket data=acceptData(); InputStream src=new java.io.FileInputStream(f); OutputStream dst=data.getOutputStream()) {
                byte[] b=new byte[32768]; int n; while((n=src.read(b))!=-1) dst.write(b,0,n); dst.flush(); downloads++;
            } finally { closePassive(); }
            reply(226,"Transfer complete");
        }
        private void doStor(String arg) throws IOException {
            if(arg.isEmpty()) { reply(501,"Filename required"); return; }
            File f=resolve(normalize(cwd,arg)); File parent=f.getParentFile();
            if(parent==null||!parent.isDirectory()||!parent.canWrite()) { reply(550,"Target directory is not writable"); return; }
            reply(150,"Opening binary data connection");
            try(Socket data=acceptData(); InputStream src=data.getInputStream(); OutputStream dst=new java.io.FileOutputStream(f,false)) {
                byte[] b=new byte[32768]; int n; while((n=src.read(b))!=-1) dst.write(b,0,n); dst.flush(); uploads++;
            } finally { closePassive(); }
            reply(226,"Transfer complete");
        }
        private String normalize(String base,String path) {
            String joined=path.startsWith("/")?path:(base.endsWith("/")?base+path:base+"/"+path);
            java.util.ArrayDeque<String> parts=new java.util.ArrayDeque<>();
            for(String part:joined.replace('\\','/').split("/")) { if(part.isEmpty()||part.equals(".")) continue; if(part.equals("..")) { if(!parts.isEmpty()) parts.removeLast(); } else parts.addLast(part); }
            StringBuilder b=new StringBuilder("/"); for(String part:parts) { if(b.length()>1)b.append('/'); b.append(part); } return b.toString();
        }
        private File resolve(String virtualPath) throws IOException {
            File f=new File(root,virtualPath.startsWith("/")?virtualPath.substring(1):virtualPath).getCanonicalFile();
            String rp=root.getCanonicalPath(); String fp=f.getCanonicalPath();
            if(!fp.equals(rp)&&!fp.startsWith(rp+File.separator)) throw new IOException("Path di luar root");
            return f;
        }
    }
}
