package ru.iqwanoino.devhosy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/** Devhosy UI. All screens are built in Java; no XML layouts, AndroidX or AppCompat. */
public class MainActivity extends Activity {
    private static final int TAB_CLIENT = 0, TAB_SERVER = 1, TAB_SETTINGS = 2;
    private static final int REQ_PERMISSIONS = 702, REQ_UPLOAD = 703, REQ_DOWNLOAD = 704;
    private static final int C_BG = 0xff0f0d13;

    private SharedPreferences prefs;
    private boolean clientAnon;
    private MaterialSwitch anonSwitch, serverAnonSwitch;
    private boolean serverAnon;
    private boolean pendingServerStart, lastFilesGrid;
    private PushDrawerLayout drawer;
    private FrameLayout pageHost;
    private LinearLayout appColumn, drawerPanel, topBar;
    private String shown = "";
    private TextView toolbarTitle, toolbarSubtitle;
    private int currentTab = TAB_CLIENT;
    private String currentRemotePath = "/";
    private String clientError = "";
    private String pendingDownloadName = "";
    private InoLoadingIndicator clientSpinner;
    private TextView clientStatusText, remotePathText;
    private LinearLayout remoteFiles;
    private LinearLayout clientPage;
    private InoInputLayout hostInput, portInput, userInput, passwordInput;
    private InoInputLayout serverPortInput, serverUserInput, serverPasswordInput, serverRootInput;
    private TextView serverStatusText, serverAddressText, serverStatsText, serverStartButton;
    private Handler handler = new Handler();
    private Typeface meTypeface;
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (!isFinishing()) {
                refreshServerStatusViews();
                handler.postDelayed(this, 1500);
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("devhosy", MODE_PRIVATE);
        Prefs.init(this);
        Theme.load(this);
        Ui.init(this);
        loadTypeface();
        lastFilesGrid = Prefs.filesGrid();
        buildShell(TAB_CLIENT);
        handler.post(statusPoll);
        if (!hasStorageAccess()) showStorageSheet();
        maybeRequestNotificationPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingServerStart && hasStorageAccess()) { pendingServerStart = false; startServiceNow(); }
        // Tema atau warna diubah di Pengaturan: bangun ulang layar utama.
        if (!M3.paletteKey.equals(shown)) recreate();
        if (currentTab == TAB_CLIENT) showTab(TAB_CLIENT);
    }

    private void loadTypeface() {
        try { meTypeface = Typeface.createFromAsset(getAssets(), "me.ttf"); }
        catch (Exception e) { meTypeface = Typeface.create("sans-serif", Typeface.NORMAL); }
    }

    private static final String URL_DISCORD = "https://discord.gg/u7QPpQD7QC";
    private static final String URL_GITHUB = "https://github.com/IqwanXD/";
    private static final String URL_TELEGRAM = "https://t.me/IqwanoINO/";

    private void buildShell(int tab) {
        currentTab = tab;
        Theme.load(this);
        Ui.init(this);
        shown = M3.paletteKey;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        appColumn = new LinearLayout(this);
        appColumn.setOrientation(LinearLayout.VERTICAL);
        appColumn.setBackgroundColor(Theme.BG);

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(Ui.iconButton(this, R.drawable.ic_menu, Theme.ON, 48, () -> { if (drawer != null) drawer.toggle(); }),
                Ui.lp(Ui.dp(48), Ui.dp(48)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        toolbarTitle = Ui.text(this, "Devhosy", 22, Theme.ON, true);
        toolbarSubtitle = Ui.text(this, "FTP workspace", 13, Theme.ON_VAR, false);
        titles.addView(toolbarTitle, Ui.lp(-2, -2));
        titles.addView(toolbarSubtitle, Ui.lp(-2, -2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = Ui.dp(8);
        topBar.addView(titles, tl);
        appColumn.addView(topBar, Ui.lp(-1, -2));

        pageHost = new FrameLayout(this);
        appColumn.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1f));

        drawerPanel = new LinearLayout(this);
        buildDrawer(drawerPanel);

        drawer = new PushDrawerLayout(this, (int) (getResources().getDisplayMetrics().widthPixels * 0.80f));
        drawer.setViews(appColumn, drawerPanel);
        drawer.setOnApplyWindowInsetsListener((v, ins) -> {
            Ui.readInsets(ins);
            applyInsets();
            return ins;
        });
        setContentView(drawer);
        Ui.setupEdge(this);
        applyInsets();
        showTab(tab);
        applyTypefaceTree(drawer);
    }

    /** Padding yang bergantung pada inset sistem (status bar atas, navigasi bawah). */
    private void applyInsets() {
        int t = Ui.insetTop, b = Ui.insetBottom;
        if (topBar != null) topBar.setPadding(Ui.dp(8), t + Ui.dp(8), Ui.dp(12), Ui.dp(8));
        if (pageHost != null) pageHost.setPadding(0, 0, 0, b);
        if (drawerPanel != null) drawerPanel.setPadding(Ui.dp(24), t + Ui.dp(24), Ui.dp(24), b + Ui.dp(24));
    }

    private void buildDrawer(LinearLayout d) {
        d.setOrientation(LinearLayout.VERTICAL);
        d.setBackgroundColor(Theme.BG);
        d.setClickable(true);

        d.addView(Ui.text(this, "Devhosy", 34, Theme.PRIMARY, true), Ui.lp(-2, -2));
        d.addView(Ui.text(this, "Transfer file FTP di jaringan lokal", 14, Theme.ON_VAR, false),
                Ui.lp(-2, -2, 0, 0, 0, Ui.dp(20)));

        d.addView(drawerItem(R.drawable.ic_folder, "FTP Client", () -> showTab(TAB_CLIENT)));
        d.addView(drawerItem(R.drawable.ic_list, "FTP Server", () -> showTab(TAB_SERVER)));
        d.addView(drawerItem(R.drawable.ic_settings, "Pengaturan",
                () -> startActivity(new Intent(this, SettingsActivity.class))));
        d.addView(drawerItem(R.drawable.ic_info, "Informasi", this::showInfo));

        View div = new View(this);
        div.setBackgroundColor((0x33 << 24) | (Theme.ON & 0x00FFFFFF));
        d.addView(div, Ui.lp(-1, Ui.dp(1), 0, Ui.dp(16), 0, Ui.dp(16)));

        TextView related = Ui.text(this, "Tautan terkait", 14, Theme.ON_VAR, true);
        related.setPadding(0, 0, 0, Ui.dp(8));
        d.addView(related, Ui.lp(-2, -2));
        d.addView(drawerItem(R.drawable.ic_discord, "Discord", () -> openUrl(URL_DISCORD)));
        d.addView(drawerItem(R.drawable.ic_github, "GitHub", () -> openUrl(URL_GITHUB)));
        d.addView(drawerItem(R.drawable.ic_telegram, "Telegram", () -> openUrl(URL_TELEGRAM)));
    }

    private View drawerItem(int icon, String label, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        row.setBackground(Ui.ripple(0, 12));
        row.setClickable(true);
        row.addView(Ui.icon(this, icon, Theme.ON_VAR), Ui.lp(Ui.dp(26), Ui.dp(26)));
        LinearLayout.LayoutParams tl = Ui.lp(-2, -2);
        tl.leftMargin = Ui.dp(20);
        row.addView(Ui.text(this, label, 16, Theme.ON, false), tl);
        row.setOnClickListener(v -> {
            drawer.close();
            action.run();
        });
        return row;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Tidak bisa membuka tautan");
        }
    }

    private void showTab(int tab) {
        currentTab = tab;
        if (pageHost == null) return;
        pageHost.removeAllViews();
        boolean client = tab == TAB_CLIENT;
        toolbarTitle.setText(client ? "FTP Client" : "FTP Server");
        toolbarSubtitle.setText(client ? "Browse, upload and download" : "Control your local server");
        if (client) buildClientPage(); else buildServerPage();
        applyTypefaceTree(pageHost);
    }

    private void buildClientPage() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout body=vertical(); body.setPadding(Ui.dp(18),Ui.dp(12),Ui.dp(18),Ui.dp(24)); scroll.addView(body);
        LinearLayout hero=card();
        hero.addView(Ui.text(this,"Connect to a server",20,Theme.ON,true));
        TextView hint=Ui.text(this,"Masukkan alamat FTP perangkat yang ingin dibuka.",13,Theme.ON_VAR,false); LinearLayout.LayoutParams hp=Ui.lp(-1,-2);hp.topMargin=Ui.dp(4);hero.addView(hint,hp);
        clientAnon=prefs.getBoolean("client_anon",false);
        hostInput=field("Host / IP address",InputType.TYPE_CLASS_TEXT,prefs.getString("client_host","192.168.1.10"));
        portInput=field("Port",InputType.TYPE_CLASS_NUMBER,prefs.getString("client_port","2121"));
        LinearLayout hostPort=new LinearLayout(this); hostPort.setOrientation(LinearLayout.HORIZONTAL);
        hostPort.addView(hostInput,new LinearLayout.LayoutParams(0,-2,1)); LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(Ui.dp(104),-2); pp.leftMargin=Ui.dp(10);hostPort.addView(portInput,pp); hero.addView(hostPort);
        LinearLayout anonRow=new LinearLayout(this); anonRow.setGravity(Gravity.CENTER_VERTICAL); anonRow.setPadding(0,Ui.dp(12),0,Ui.dp(4));
        anonRow.setClickable(true);   // tanpa ripple: baris ini hanya menggeser switch
        LinearLayout anonTexts=new LinearLayout(this); anonTexts.setOrientation(LinearLayout.VERTICAL);
        anonTexts.addView(Ui.text(this,"Login anonim",15,Theme.ON,false),Ui.lp(-2,-2));
        anonTexts.addView(Ui.text(this,"Tanpa username dan password",12,Theme.ON_VAR,false),Ui.lp(-2,-2));
        anonRow.addView(anonTexts,new LinearLayout.LayoutParams(0,-2,1f));
        anonSwitch=styledSwitch(clientAnon); anonSwitch.setEnabled(false); anonSwitch.setFocusable(false);
        anonRow.addView(anonSwitch,Ui.lp(Ui.dp(54),Ui.dp(42)));
        anonRow.setOnClickListener(v->setClientAnon(!clientAnon));
        hero.addView(anonRow,Ui.lp(-1,-2));
        userInput=field("Username",InputType.TYPE_CLASS_TEXT,prefs.getString("client_user","devhosy"));hero.addView(userInput);
        passwordInput=field("Password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,prefs.getString("client_pass","devhosy123"));hero.addView(passwordInput);
        applyAnonVisibility();
        LinearLayout connectRow=new LinearLayout(this); connectRow.setGravity(Gravity.CENTER_VERTICAL); connectRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView connect=button(FtpClientText(),true,()->connectClient()); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,Ui.dp(52),1); connectRow.addView(connect,cp);
        clientSpinner=new InoLoadingIndicator(this); clientSpinner.setIndicatorColor(Theme.PRIMARY); clientSpinner.setVisibility(View.GONE); LinearLayout.LayoutParams sip=Ui.lp(Ui.dp(32),Ui.dp(32)); sip.leftMargin=Ui.dp(12);connectRow.addView(clientSpinner,sip);
        connectRow.setPadding(0,Ui.dp(8),0,0); connectRow.setClipChildren(false);
        hero.addView(connectRow);
        clientStatusText=Ui.text(this,clientError.isEmpty()?(ClientSession.connected()?"Terhubung":"Belum terhubung"):"Error: "+clientError,12,clientError.isEmpty()?Theme.ON_VAR:0xffba1a1a,false); LinearLayout.LayoutParams cs=Ui.lp(-1,-2);cs.topMargin=Ui.dp(10);hero.addView(clientStatusText,cs);
        if(ClientSession.connected()){
            LinearLayout.LayoutParams bp=Ui.lp(-1,Ui.dp(48)); bp.topMargin=Ui.dp(10);
            hero.addView(button("Buka file manager",false,()->startActivity(new Intent(this,ClientManager.class))),bp);
        }
        body.addView(hero);

        LinearLayout info=card();
        info.addView(Ui.text(this,"Quick start",16,Theme.ON,true));
        info.addView(infoLine("01","Di tab Server, tekan Start server pada perangkat sumber."));
        info.addView(infoLine("02","Gunakan IP lokal, port, username dan password di atas."));
        info.addView(infoLine("03","Setelah connect, ketuk folder untuk masuk dan ikon ⋮ untuk aksi file."));
        TextView security=Ui.text(this,"FTP biasa tidak mengenkripsi password atau isi file. Gunakan hanya jaringan tepercaya.",12,Theme.ON_VAR,false);LinearLayout.LayoutParams sec=Ui.lp(-1,-2);sec.topMargin=Ui.dp(12);info.addView(security,sec);
        LinearLayout.LayoutParams infop=Ui.lp(-1,-2);infop.topMargin=Ui.dp(14);body.addView(info,infop);
        pageHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
    }

    /** Server: ganti login anonim. Dikunci saat server berjalan karena dibaca saat start. */
    private void setServerAnon(boolean on){
        if(FtpServerService.running){ toast("Hentikan server dulu untuk mengganti login anonim"); return; }
        serverAnon=on;
        prefs.edit().putBoolean("server_anon",on).apply();
        if(serverAnonSwitch!=null) serverAnonSwitch.setChecked(on);
        applyServerAnonVisibility();
    }

    private void applyServerAnonVisibility(){
        int v=serverAnon?View.GONE:View.VISIBLE;
        if(serverUserInput!=null) serverUserInput.setVisibility(v);
        if(serverPasswordInput!=null) serverPasswordInput.setVisibility(v);
    }

    private void setClientAnon(boolean on){
        clientAnon=on;
        prefs.edit().putBoolean("client_anon",on).apply();
        if(anonSwitch!=null) anonSwitch.setChecked(on);
        applyAnonVisibility();
    }

    /** Saat anonim, username dan password disembunyikan; nilai yang sudah diketik tetap tersimpan. */
    private void applyAnonVisibility(){
        int v=clientAnon?View.GONE:View.VISIBLE;
        if(userInput!=null) userInput.setVisibility(v);
        if(passwordInput!=null) passwordInput.setVisibility(v);
    }

    private String FtpClientText(){return ClientSession.connected()?"Reconnect":"Connect";}

    private void connectClient() {
        final String host=hostInput.getText().trim();
        final String typedUser=userInput.getText().trim();
        final String typedPass=passwordInput.getText();
        int port; try { port=Integer.parseInt(portInput.getText().trim()); if(port<1||port>65535)throw new NumberFormatException(); } catch(Exception e){ toast("Port harus 1–65535"); return; }
        if(host.isEmpty()){toast("Host wajib diisi");return;}
        if(!clientAnon&&typedUser.isEmpty()){toast("Username wajib diisi");return;}
        final String user=clientAnon?"anonymous":typedUser;
        final String pass=clientAnon?"devhosy@":typedPass;
        final int p=port;
        prefs.edit().putString("client_host",host).putString("client_port",String.valueOf(p))
                .putString("client_user",typedUser).putString("client_pass",typedPass)
                .putBoolean("client_anon",clientAnon).apply();
        if(clientSpinner!=null)clientSpinner.setVisibility(View.VISIBLE); if(clientStatusText!=null)clientStatusText.setText("Menghubungkan…");
        new Thread(()->{
            FtpClient candidate=new FtpClient();
            try {
                candidate.connect(host,p,user,pass);
                runOnUiThread(()->{
                    ClientSession.set(candidate,host,p,user,pass); clientError="";
                    toast("Berhasil terhubung ke "+host+":"+p);
                    startActivity(new Intent(this,ClientManager.class));
                });
            } catch(Exception e) {
                candidate.close(); final String err=e.getMessage()==null?"Koneksi gagal":e.getMessage();
                runOnUiThread(()->{clientError=err;if(clientSpinner!=null)clientSpinner.setVisibility(View.GONE);if(clientStatusText!=null)clientStatusText.setText("Gagal: "+err);toast("FTP: "+err);});
            }
        },"Devhosy-Ftp-Connect").start();
    }

    private void buildServerPage() {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout body=vertical();body.setPadding(Ui.dp(18),Ui.dp(12),Ui.dp(18),Ui.dp(26));scroll.addView(body);
        LinearLayout status=card();LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout statusTexts=vertical();statusTexts.addView(Ui.text(this,"Server status",18,Theme.ON,true));serverStatusText=Ui.text(this,FtpServerService.running?"RUNNING":"STOPPED",12,FtpServerService.running?0xff4caf50:Theme.ON_VAR,true);LinearLayout.LayoutParams stp=Ui.lp(-1,-2);stp.topMargin=Ui.dp(4);statusTexts.addView(serverStatusText,stp);top.addView(statusTexts,new LinearLayout.LayoutParams(0,-2,1));
        serverStartButton=button(FtpServerService.running?"Stop server":"Start server",!FtpServerService.running,()->toggleServer());top.addView(serverStartButton,Ui.lp(Ui.dp(152),Ui.dp(48)));status.addView(top);
        TextView addressLabel=Ui.text(this,"LAN address",12,Theme.ON_VAR,false);LinearLayout.LayoutParams al=Ui.lp(-1,-2);al.topMargin=Ui.dp(16);status.addView(addressLabel,al);
        serverAddressText=Ui.text(this,formatServerAddress(),17,Theme.PRIMARY,true);LinearLayout.LayoutParams ad=Ui.lp(-1,-2);ad.topMargin=Ui.dp(3);status.addView(serverAddressText,ad);
        TextView tip=Ui.text(this,"Port default 2121 · mode PASV",12,Theme.ON_VAR,false);LinearLayout.LayoutParams ti=Ui.lp(-1,-2);ti.topMargin=Ui.dp(6);status.addView(tip,ti);body.addView(status);

        LinearLayout cfg=card();cfg.addView(Ui.text(this,"Server configuration",18,Theme.ON,true));
        serverPortInput=field("Port",InputType.TYPE_CLASS_NUMBER,String.valueOf(prefs.getInt("server_port",2121)));cfg.addView(serverPortInput);
        serverAnon=prefs.getBoolean("server_anon",false);
        LinearLayout sAnonRow=new LinearLayout(this); sAnonRow.setGravity(Gravity.CENTER_VERTICAL); sAnonRow.setPadding(0,Ui.dp(12),0,Ui.dp(4));
        sAnonRow.setClickable(true);
        LinearLayout sAnonTexts=new LinearLayout(this); sAnonTexts.setOrientation(LinearLayout.VERTICAL);
        sAnonTexts.addView(Ui.text(this,"Login anonim",15,Theme.ON,false),Ui.lp(-2,-2));
        sAnonTexts.addView(Ui.text(this,"User anonymous/ftp, password apa saja, hanya baca",12,Theme.ON_VAR,false),Ui.lp(-2,-2));
        sAnonRow.addView(sAnonTexts,new LinearLayout.LayoutParams(0,-2,1f));
        serverAnonSwitch=styledSwitch(serverAnon); serverAnonSwitch.setEnabled(false); serverAnonSwitch.setFocusable(false);
        sAnonRow.addView(serverAnonSwitch,Ui.lp(Ui.dp(54),Ui.dp(42)));
        sAnonRow.setOnClickListener(v->setServerAnon(!serverAnon));
        cfg.addView(sAnonRow,Ui.lp(-1,-2));
        serverUserInput=field("Username",InputType.TYPE_CLASS_TEXT,prefs.getString("server_user","devhosy"));cfg.addView(serverUserInput);
        serverPasswordInput=field("Password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,prefs.getString("server_pass","devhosy123"));cfg.addView(serverPasswordInput);
        applyServerAnonVisibility();
        serverRootInput=field("Shared root folder",InputType.TYPE_CLASS_TEXT,prefs.getString("server_root",Environment.getExternalStorageDirectory().getAbsolutePath()));cfg.addView(serverRootInput);
        TextView rootHint=Ui.text(this,"Folder ini akan menjadi / pada FTP. Android perlu memberi izin storage agar folder dapat dibaca/tulis.",12,Theme.ON_VAR,false);LinearLayout.LayoutParams rh=Ui.lp(-1,-2);rh.topMargin=Ui.dp(6);cfg.addView(rootHint,rh);LinearLayout.LayoutParams cfp=Ui.lp(-1,-2);cfp.topMargin=Ui.dp(14);body.addView(cfg,cfp);

        LinearLayout stats=card();stats.addView(Ui.text(this,"Live statistics",18,Theme.ON,true));serverStatsText=Ui.text(this,"0 active clients     ↑ 0 uploads     ↓ 0 downloads",13,Theme.ON_VAR,false);LinearLayout.LayoutParams ss=Ui.lp(-1,-2);ss.topMargin=Ui.dp(12);stats.addView(serverStatsText,ss);LinearLayout.LayoutParams sp=Ui.lp(-1,-2);sp.topMargin=Ui.dp(14);body.addView(stats,sp);
        LinearLayout security=card();security.addView(Ui.text(this,"Network safety",16,Theme.ON,true));security.addView(Ui.text(this,"FTP standar mengirim password dan file tanpa enkripsi. Pakai jaringan Wi‑Fi pribadi/tepercaya dan hentikan server saat selesai. Jangan meneruskan port FTP ke internet.",13,Theme.ON_VAR,false));LinearLayout.LayoutParams sec=Ui.lp(-1,-2);sec.topMargin=Ui.dp(14);body.addView(security,sec);
        pageHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));refreshServerStatusViews();
    }

    private void toggleServer(){
        if(FtpServerService.running){stopService(new Intent(this,FtpServerService.class));FtpServerService.running=false;refreshServerStatusViews();toast("FTP server dihentikan");return;}
        int port;try{port=Integer.parseInt(serverPortInput.getText().trim());if(port<1024||port>65535)throw new NumberFormatException();}catch(Exception e){toast("Port server harus 1024–65535");return;}
        String user=serverUserInput.getText().trim(),pass=serverPasswordInput.getText(),root=serverRootInput.getText().trim();
        if(root.isEmpty()||(!serverAnon&&(user.isEmpty()||pass.isEmpty()))){toast("Username, password dan folder wajib diisi");return;}
        File folder=new File(root);if(!folder.exists()||!folder.isDirectory()){toast("Folder root tidak ditemukan");return;}
        prefs.edit().putInt("server_port",port).putString("server_user",user).putString("server_pass",pass).putString("server_root",folder.getAbsolutePath()).apply();
        if(!hasStorageAccess()){ pendingServerStart=true; showStorageSheet(); return; }
        startServiceNow();
    }
    /** Akses penyimpanan: semua file di Android 11+, atau baca/tulis runtime di Android 10 ke bawah. */
    private boolean hasStorageAccess(){
        if(Build.VERSION.SDK_INT>=30) return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }

    /** Bottom sheet izin penyimpanan. "Izinkan" membuka halaman sistem (Android 11+) atau dialog runtime (Android 10-). */
    private void showStorageSheet(){
        final Sheet s=new Sheet(this,true);
        LinearLayout c=s.panel;
        ImageView badge=Ui.icon(this,R.drawable.ic_folder,Theme.PRIMARY);
        badge.setPadding(Ui.dp(16),Ui.dp(16),Ui.dp(16),Ui.dp(16));
        badge.setBackground(Ui.rect(Theme.PRIM_CONT,20));
        c.addView(badge,Ui.lp(Ui.dp(64),Ui.dp(64)));
        c.addView(Ui.text(this,"Izinkan akses penyimpanan",22,Theme.ON,true),Ui.lp(-2,-2,0,Ui.dp(16),0,Ui.dp(8)));
        String msg=Build.VERSION.SDK_INT>=30
                ?"Server FTP perlu membaca dan menulis folder di penyimpanan. Di layar berikutnya, aktifkan \"Izinkan akses ke semua file\" untuk Devhosy."
                :"Server FTP perlu izin baca dan tulis penyimpanan untuk membagikan folder.";
        c.addView(Ui.text(this,msg,15,Theme.ON_VAR,false),Ui.lp(-1,-2,0,0,0,Ui.dp(20)));
        c.addView(Ui.button(this,"Izinkan",Theme.PRIMARY,Theme.ON_PRIMARY,()->{s.dismiss();requestStorageAccess();}),Ui.lp(-1,Ui.dp(52)));
        c.addView(Ui.button(this,"Nanti",0,Theme.PRIMARY,s::dismiss),Ui.lp(-1,Ui.dp(52),0,Ui.dp(8),0,0));
        s.show();
    }

    private void requestStorageAccess(){
        if(Build.VERSION.SDK_INT>=30){
            try{
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));
            }catch(Exception e){
                try{startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
                catch(Exception ex){toast("Buka pengaturan izin semua file secara manual");}
            }
        }else{
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_PERMISSIONS);
        }
    }

    private boolean needsStoragePermission(){return Build.VERSION.SDK_INT>=23&&(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED||checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED);}
    private void requestStoragePermissions(){if(Build.VERSION.SDK_INT>=23)requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_PERMISSIONS);}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);if(requestCode==REQ_PERMISSIONS){boolean ok=true;for(int r:results)if(r!=PackageManager.PERMISSION_GRANTED)ok=false;if(ok){pendingServerStart=false;startServiceNow();}else toast("Izin storage diperlukan untuk folder yang dibagikan.");}}
    private void startServiceNow(){Intent i=new Intent(this,FtpServerService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);toast("Permintaan start server dikirim");handler.postDelayed(this::refreshServerStatusViews,700);}
    private void refreshServerStatusViews(){if(serverStatusText==null)return;boolean run=FtpServerService.running;serverStatusText.setText(run?"RUNNING":"STOPPED");serverStatusText.setTextColor(run?0xff4caf50:Theme.ON_VAR);serverAddressText.setText(formatServerAddress());serverStartButton.setText(run?"Stop server":"Start server");serverStatsText.setText(FtpServerService.connections+" active clients     ↑ "+FtpServerService.uploads+" uploads     ↓ "+FtpServerService.downloads+" downloads");if(!FtpServerService.lastError.isEmpty())serverStatusText.setText("ERROR · "+FtpServerService.lastError);}
    private String formatServerAddress(){return getLocalIp()+":"+prefs.getInt("server_port",2121);}

    /** Informasi, gaya bottom sheet Music. */
    private void showInfo(){
        Sheet s=new Sheet(this,true);
        String ver="-";
        try{ver=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception ignored){}
        s.panel.addView(Ui.text(this,"Devhosy",22,Theme.ON,true),Ui.lp(-2,-2,0,Ui.dp(8),0,Ui.dp(12)));
        s.panel.addView(Ui.text(this,"Versi "+ver,15,Theme.ON_VAR,false),Ui.lp(-2,-2,0,0,0,Ui.dp(6)));
        s.panel.addView(Ui.text(this,"Server: "+(FtpServerService.running?"berjalan":"berhenti"),15,Theme.ON_VAR,false),Ui.lp(-2,-2,0,0,0,Ui.dp(6)));
        s.panel.addView(Ui.text(this,"Transfer file FTP di jaringan lokal. Username, password, dan isi file dikirim tanpa enkripsi, jadi gunakan hanya di jaringan tepercaya.",14,Theme.ON_VAR,false),Ui.lp(-1,-2,0,0,0,Ui.dp(16)));
        s.panel.addView(Ui.button(this,"Tutup",Theme.PRIMARY,Theme.ON_PRIMARY,s::dismiss),Ui.lp(-1,Ui.dp(52)));
        s.show();
    }

    private InoInputLayout field(String label,int inputType,String value){InoInputLayout f=new InoInputLayout(this);f.setLabelText(label);f.setIsMultiline(false);f.setText(value==null?"":value);f.setInputType(inputType);f.setTextColor(Theme.ON);f.setColors(Theme.PRIMARY,Theme.OUTLINE);f.setCornerRadius(14);f.setTextSize(15);f.setLabelSize(12);f.setVerticalMargins(10,4);LinearLayout.LayoutParams p=Ui.lp(-1,-2);p.topMargin=Ui.dp(8);f.setLayoutParams(p);return f;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout card(){LinearLayout c=vertical();c.setPadding(Ui.dp(18),Ui.dp(16),Ui.dp(18),Ui.dp(18));c.setBackground(Ui.rect(Theme.SURFACE,26));c.setElevation(Ui.dp(1));c.setClipChildren(false);return c;}
    private TextView button(String text,boolean filled,Runnable action){int bg=filled?Theme.PRIMARY:Theme.SURFACE_HIGH;int fg=filled?Theme.ON_PRIMARY:Theme.ON;TextView t=Ui.button(this,text,bg,fg,action);t.setPadding(Ui.dp(12),0,Ui.dp(12),0);t.setMinHeight(Ui.dp(44));t.setTextSize(14);return t;}
    private MaterialSwitch styledSwitch(boolean checked){MaterialSwitch s=new MaterialSwitch(this);s.setActiveColor(Theme.PRIMARY);s.setStrokeActiveColor(Theme.PRIMARY);s.setThumbActiveColor(Theme.ON_PRIMARY);s.setInactiveColor(android.graphics.Color.TRANSPARENT);s.setStrokeInactiveColor(Theme.OUTLINE);s.setThumbInactiveColor(Theme.OUTLINE);s.setCheckedImmediate(checked);return s;}
    private View infoLine(String n,String text){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.TOP);TextView num=Ui.text(this,n,12,Theme.PRIMARY,true);num.setGravity(Gravity.CENTER);num.setBackground(Ui.rect(Theme.PRIM_CONT,12));row.addView(num,Ui.lp(Ui.dp(32),Ui.dp(28)));TextView body=Ui.text(this,text,13,Theme.ON_VAR,false);LinearLayout.LayoutParams p=Ui.lp(0,-2);p.width=0;p.weight=1;p.leftMargin=Ui.dp(12);row.addView(body,p);LinearLayout.LayoutParams rp=Ui.lp(-1,-2);rp.topMargin=Ui.dp(12);row.setLayoutParams(rp);return row;}
    private void toast(String text){if(text==null||text.trim().isEmpty())text="Selesai";M3.toast(this,text);}
    private String humanSize(long bytes){if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return String.format(java.util.Locale.US,"%.1f KB",bytes/1024.0);if(bytes<1024L*1024*1024)return String.format(java.util.Locale.US,"%.1f MB",bytes/(1024.0*1024));return String.format(java.util.Locale.US,"%.1f GB",bytes/(1024.0*1024*1024));}
    private String getLocalIp(){try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){NetworkInterface n=interfaces.nextElement();if(!n.isUp()||n.isLoopback())continue;Enumeration<InetAddress> addrs=n.getInetAddresses();while(addrs.hasMoreElements()){InetAddress a=addrs.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&!a.isLinkLocalAddress())return a.getHostAddress();}}}catch(Exception ignored){}return "127.0.0.1";}
    private void applyTypefaceTree(View view){Fonts.apply(view);}
    private void maybeRequestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){/* foreground service notification still works; ask only when server starts if needed */}}
    @Override public void onBackPressed(){if(drawer!=null&&drawer.isOpen()){drawer.close();return;}if(currentTab!=TAB_CLIENT){showTab(TAB_CLIENT);return;}super.onBackPressed();}
    @Override protected void onDestroy(){handler.removeCallbacks(statusPoll);super.onDestroy();}
}
