package ru.iqwanoino.devhosy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small passive-mode FTP client intended for trusted LANs. Plain FTP is not encrypted. */
public final class FtpClient implements Closeable {
    public static final class Entry {
        public final String name; public final boolean directory; public final long size;
        Entry(String n, boolean d, long s) { name=n; directory=d; size=s; }
    }
    private Socket control;
    private BufferedReader reader;
    private BufferedWriter writer;
    private String host;
    private int port;
    private volatile boolean connected;

    public synchronized String connect(String h, int p, String user, String pass) throws IOException {
        closeQuietly(); host = h; port = p;
        control = new Socket(); control.connect(new InetSocketAddress(h, p), 9000); control.setSoTimeout(15000);
        reader = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8));
        Reply welcome = readReply(); if (welcome.code / 100 != 2) throw new IOException(welcome.message);
        Reply ur = command("USER " + clean(user));
        if (ur.code == 331) ur = command("PASS " + clean(pass));
        if (ur.code / 100 != 2) throw new IOException("Login ditolak: " + ur.message);
        command("TYPE I"); connected = true;
        return host + ":" + port;
    }
    public boolean isConnected() { return connected && control != null && control.isConnected() && !control.isClosed(); }
    public synchronized String pwd() throws IOException {
        Reply r=command("PWD"); ensureOk(r, 2, "PWD"); Matcher m=Pattern.compile("\"([^\"]*)\"").matcher(r.message);
        return m.find()?m.group(1):"/";
    }
    public synchronized void cwd(String path) throws IOException { Reply r=command("CWD " + clean(path)); ensureOk(r,2,"CWD"); }
    public synchronized List<Entry> list(String path) throws IOException {
        Socket data = passiveSocket();
        Reply r=command(path == null || path.length()==0 ? "LIST" : "LIST " + clean(path));
        if (r.code != 125 && r.code != 150) { data.close(); throw new IOException("LIST gagal: " + r.message); }
        List<Entry> out = new ArrayList<>();
        try (BufferedReader br=new BufferedReader(new InputStreamReader(data.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line=br.readLine())!=null) {
                if (line.length()<10) continue;
                String[] parts=line.split("\\s+", 9);
                if (parts.length<9) continue;
                String name=parts[8]; if (".".equals(name)||"..".equals(name)) continue;
                boolean dir=parts[0].startsWith("d"); long size=0; try { size=Long.parseLong(parts[4]); } catch(Exception ignored) {}
                out.add(new Entry(name,dir,size));
            }
        } finally { data.close(); }
        Reply done=readReply(); if (done.code / 100 != 2) throw new IOException("Daftar gagal: " + done.message);
        return out;
    }
    public synchronized void download(String remoteName, OutputStream output) throws IOException {
        Socket data=passiveSocket(); Reply r=command("RETR " + clean(remoteName));
        if (r.code!=125 && r.code!=150) { data.close(); throw new IOException("Download gagal: " + r.message); }
        try (InputStream in=data.getInputStream()) { byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1) output.write(b,0,n); output.flush(); }
        finally { data.close(); }
        Reply done=readReply(); if(done.code/100!=2) throw new IOException(done.message);
    }
    public synchronized void upload(String remoteName, InputStream input) throws IOException {
        Socket data=passiveSocket(); Reply r=command("STOR " + clean(remoteName));
        if (r.code!=125 && r.code!=150) { data.close(); throw new IOException("Upload gagal: " + r.message); }
        try (OutputStream out=data.getOutputStream()) { byte[] b=new byte[32768]; int n; while((n=input.read(b))!=-1) out.write(b,0,n); out.flush(); }
        finally { data.close(); }
        Reply done=readReply(); if(done.code/100!=2) throw new IOException(done.message);
    }
    public synchronized void delete(String name) throws IOException { Reply r=command("DELE " + clean(name)); ensureOk(r,2,"DELE"); }
    public synchronized void mkdir(String name) throws IOException { Reply r=command("MKD " + clean(name)); ensureOk(r,2,"MKD"); }
    public synchronized void rmdir(String name) throws IOException { Reply r=command("RMD " + clean(name)); ensureOk(r,2,"RMD"); }
    public synchronized void rename(String from, String to) throws IOException {
        Reply f=command("RNFR " + clean(from)); if (f.code!=350) throw new IOException("RNFR: " + f.message);
        Reply t=command("RNTO " + clean(to)); ensureOk(t,2,"RNTO");
    }
    /** Cek koneksi hidup (NOOP). False jika socket sudah putus, mis. "pipe broken". */
    public synchronized boolean ping() {
        try { Reply r = command("NOOP"); return r.code / 100 == 2; }
        catch (IOException e) { return false; }
    }
    private Socket passiveSocket() throws IOException {
        Reply r=command("PASV"); if(r.code!=227) throw new IOException("Mode pasif gagal: " + r.message);
        Matcher m=Pattern.compile("(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3}),(\\d{1,3})").matcher(r.message);
        if(!m.find()) throw new IOException("Alamat PASV tidak valid: " + r.message);
        String ip=m.group(1)+"."+m.group(2)+"."+m.group(3)+"."+m.group(4);
        int p=Integer.parseInt(m.group(5))*256+Integer.parseInt(m.group(6));
        Socket s=new Socket(); s.connect(new InetSocketAddress(ip,p),9000); s.setSoTimeout(30000); return s;
    }
    private Reply command(String c) throws IOException {
        if(writer==null || control==null || control.isClosed()) throw new IOException("Belum terhubung");
        writer.write(c); writer.write("\r\n"); writer.flush(); return readReply();
    }
    private Reply readReply() throws IOException {
        String first=reader.readLine(); if(first==null) throw new IOException("Koneksi FTP terputus");
        int code=parseCode(first); StringBuilder msg=new StringBuilder(first.length()>4?first.substring(4):first);
        if(first.length()>3 && first.charAt(3)=='-') {
            String prefix=first.substring(0,3)+" "; String line;
            while((line=reader.readLine())!=null) { msg.append('\n').append(line); if(line.startsWith(prefix)) break; }
            if(line==null) throw new IOException("Balasan FTP terpotong");
        }
        return new Reply(code,msg.toString());
    }
    private int parseCode(String s) { try { return Integer.parseInt(s.substring(0,3)); } catch(Exception e) { return 500; } }
    private void ensureOk(Reply r,int family,String op) throws IOException { if(r.code/100!=family) throw new IOException(op+": "+r.message); }
    private String clean(String s) throws IOException { if(s==null || s.indexOf('\r')>=0 || s.indexOf('\n')>=0) throw new IOException("Parameter tidak valid"); return s; }
    @Override public synchronized void close() { try { if(isConnected()) { writer.write("QUIT\r\n"); writer.flush(); } } catch(Exception ignored){} closeQuietly(); }
    private void closeQuietly() { connected=false; try { if(control!=null) control.close(); } catch(Exception ignored){} control=null; reader=null; writer=null; }
    private static final class Reply { final int code; final String message; Reply(int c,String m){code=c;message=m;} }
}
