package com.securechat.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Intent;
import android.widget.*;
import android.view.View;

import com.goterl.lazysodium.LazySodiumAndroid;
import com.goterl.lazysodium.SodiumAndroid;
import org.bouncycastle.crypto.generators.SCrypt;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import okhttp3.*;

public class MainActivity extends Activity {
    private static final int BG=0xFF080D12, PANEL=0xFF101922, PANEL2=0xFF16232D,
            GREEN=0xFF65F4B5, MUTED=0xFF82949F, WHITE=0xFFE8F1F5, RED=0xFFFF7474;
    private static final String SALT="SecureChat-room-key-v1";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService crypto=Executors.newSingleThreadExecutor();
    private final OkHttpClient http=new OkHttpClient.Builder().pingInterval(25,TimeUnit.SECONDS).build();
    private LazySodiumAndroid sodium;
    private byte[] key;
    private WebSocket socket;
    private String host="", username="", room="lobby", wsUrl="";
    private boolean joined=false, manual=false;
    private int retryDelay=2;
    private final Map<String,Long> pings=new ConcurrentHashMap<>();
    private final ArrayList<String> transcript=new ArrayList<>();
    private LinearLayout root, messages;
    private ScrollView scroll;
    private TextView status, roomLabel, users;
    private EditText serverField, nameField, roomField, passField, messageField;
    private Button connectButton;

    private final Runnable heartbeat=new Runnable(){ public void run(){
        if(joined && socket!=null){send(obj("type","ping"));main.postDelayed(this,30000);}
    }};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        try { sodium=new LazySodiumAndroid(new SodiumAndroid(),StandardCharsets.UTF_8); if(sodium.sodiumInit()<0)sodium=null; }
        catch(Throwable e){sodium=null;}
        loginScreen();
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color,int border,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));
        if(border!=0)d.setStroke(dp(1),border);return d;
    }
    private TextView label(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private EditText field(String hint,boolean secret){
        EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(WHITE);e.setHintTextColor(MUTED);
        e.setHint(hint);e.setTextSize(15);e.setPadding(dp(13),dp(10),dp(13),dp(10));
        e.setBackground(shape(PANEL2,0xFF263844,12));
        if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private Button button(String s){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextColor(BG);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(shape(GREEN,0,13));return b;
    }
    private void base(){root=col();root.setBackgroundColor(BG);setContentView(root);}
    private void addField(LinearLayout parent,String title,EditText edit){
        TextView t=label(title,10,MUTED,true);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.topMargin=dp(15);p.bottomMargin=dp(6);parent.addView(t,p);
        parent.addView(edit,new LinearLayout.LayoutParams(-1,dp(50)));
    }
    private void loginScreen(){
        joined=false;main.removeCallbacks(heartbeat);base();
        ScrollView sc=new ScrollView(this);root.addView(sc,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout body=col();body.setPadding(dp(24),dp(38),dp(24),dp(28));sc.addView(body);
        TextView logo=label("SC",28,BG,true);logo.setGravity(Gravity.CENTER);logo.setBackground(shape(GREEN,0,18));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(66),dp(66));p.gravity=Gravity.CENTER;body.addView(logo,p);
        TextView title=label("SecureApp",29,WHITE,true);title.setGravity(Gravity.CENTER);
        p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(15);body.addView(title,p);
        TextView sub=label("PRIVATE  •  ENCRYPTED  •  REAL-TIME",11,GREEN,true);sub.setGravity(Gravity.CENTER);
        p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(6);p.bottomMargin=dp(24);body.addView(sub,p);
        LinearLayout card=col();card.setPadding(dp(18),dp(18),dp(18),dp(20));
        card.setBackground(shape(PANEL,0xFF20303B,20));body.addView(card,new LinearLayout.LayoutParams(-1,-2));
        card.addView(label("CONNECT TO YOUR SECURECHAT SERVER",11,GREEN,true));
        serverField=field("Server IP or domain (example.com:8080)",false);
        serverField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        nameField=field("Username",false);roomField=field("Room (default: lobby)",false);
        passField=field("Room password (minimum 8 characters)",true);
        addField(card,"SERVER ADDRESS",serverField);addField(card,"USERNAME",nameField);
        addField(card,"ROOM",roomField);addField(card,"ROOM PASSWORD",passField);
        connectButton=button("Connect securely  →");p=new LinearLayout.LayoutParams(-1,dp(52));
        p.topMargin=dp(20);card.addView(connectButton,p);connectButton.setOnClickListener(v->connectForm());
        TextView foot=label("Compatible with the SecureChat Python relay and web client.",12,MUTED,false);
        foot.setGravity(Gravity.CENTER);p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(20);body.addView(foot,p);
        serverField.setText(getPreferences(0).getString("server",""));
        nameField.setText(getPreferences(0).getString("user",""));
        roomField.setText(getPreferences(0).getString("room","lobby"));
    }
    private void connectForm(){
        host=serverField.getText().toString().trim();
        username=nameField.getText().toString().trim();
        room=roomField.getText().toString().trim();if(room.isEmpty())room="lobby";
        String password=passField.getText().toString();
        if(host.isEmpty()||username.isEmpty()){toast("Server and username are required");return;}
        if(password.length()<8){toast("Password must contain at least 8 characters");return;}
        if(sodium==null){toast("Encryption library failed to initialize");return;}
        if(username.length()>32)username=username.substring(0,32);
        getPreferences(0).edit().putString("server",host).putString("user",username).putString("room",room).apply();
        connectButton.setEnabled(false);connectButton.setText("Deriving encryption key…");
        crypto.execute(()->{
            try{
                byte[] derived=SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),SALT.getBytes(StandardCharsets.UTF_8),16384,8,1,32);
                main.post(()->{key=derived;passField.setText("");connectButton.setEnabled(true);connectButton.setText("Connect securely  →");connect(false);});
            }catch(Exception e){main.post(()->{connectButton.setEnabled(true);connectButton.setText("Connect securely  →");toast("Key derivation failed");});}
        });
    }
    private void connect(boolean retry){
        manual=false;
        String s=host.trim().replaceFirst("^https?://","").replaceFirst("/+$","");
        String scheme="ws://";
        if(s.startsWith("wss://")){scheme="wss://";s=s.substring(6);}
        else if(s.startsWith("ws://"))s=s.substring(5);
        if(!s.matches("^\\[[^\\]]+\\]:[0-9]+$")&&!s.matches("^[^/]+:[0-9]+$"))s+=":8080";
        wsUrl=scheme+s;
        try{
            Request request=new Request.Builder().url(wsUrl).build();
            socket=http.newWebSocket(request,new WebSocketListener(){
                @Override public void onOpen(WebSocket ws,Response response){
                    main.post(()->{
                        retryDelay=2;send(obj("type","hello","username",username,"room",room));
                        if(!joined){joined=true;chatScreen();addSystem("Connected to "+wsUrl);addSystem("End-to-end encryption is active. Type /help for commands.");}
                        else addSystem("Connection restored.");
                        if(status!=null){status.setText("● CONNECTED");status.setTextColor(GREEN);}
                        main.removeCallbacks(heartbeat);main.postDelayed(heartbeat,30000);
                    });
                }
                @Override public void onMessage(WebSocket ws,String text){
                    try{JSONObject f=new JSONObject(text);main.post(()->handle(f));}catch(Exception ignored){}
                }
                @Override public void onClosed(WebSocket ws,int code,String reason){lost(reason);}
                @Override public void onFailure(WebSocket ws,Throwable error,Response response){lost(error.getMessage());}
            });
        }catch(Exception e){lost(e.getMessage());}
    }
    private void lost(String reason){
        main.post(()->{
            main.removeCallbacks(heartbeat);
            if(manual)return;
            if(status!=null){status.setText("● RECONNECTING");status.setTextColor(0xFFFFC857);}
            if(!joined){toast("Could not connect. Check the server address and port.");return;}
            addError("Connection lost. Reconnecting in "+retryDelay+" seconds…");
            int wait=retryDelay;retryDelay=Math.min(retryDelay*2,30);
            main.postDelayed(()->{if(!manual)connect(true);},wait*1000L);
        });
    }
    private void chatScreen(){
        base();
        LinearLayout bar=row();bar.setPadding(dp(16),dp(12),dp(16),dp(12));bar.setBackgroundColor(PANEL);
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(64)));
        TextView logo=label("SC",17,GREEN,true);logo.setGravity(Gravity.CENTER);logo.setBackground(shape(PANEL2,GREEN,10));
        bar.addView(logo,new LinearLayout.LayoutParams(dp(40),dp(40)));
        LinearLayout title=col();LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.leftMargin=dp(10);bar.addView(title,tp);
        title.addView(label("SecureApp",16,WHITE,true));roomLabel=label("#"+room+" · E2E ENCRYPTED",10,GREEN,true);title.addView(roomLabel);
        status=label("● CONNECTED",10,GREEN,true);bar.addView(status);
        TextView fp=label("ROOM KEY  "+fingerprint(),10,MUTED,false);fp.setPadding(dp(16),dp(9),dp(16),dp(9));root.addView(fp);
        LinearLayout area=col();area.setPadding(dp(12),0,dp(12),0);root.addView(area,new LinearLayout.LayoutParams(-1,0,1));
        scroll=new ScrollView(this);scroll.setBackground(shape(PANEL,0xFF20303B,14));
        area.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        messages=col();messages.setPadding(dp(12),dp(12),dp(12),dp(12));scroll.addView(messages);
        users=label("ROOM MEMBERS · use /users",10,MUTED,true);users.setPadding(dp(8),dp(10),dp(8),dp(8));area.addView(users);
        LinearLayout composer=row();composer.setPadding(dp(8),dp(6),dp(8),dp(6));composer.setBackground(shape(PANEL,0xFF253640,14));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(58));cp.topMargin=dp(7);area.addView(composer,cp);
        messageField=new EditText(this);messageField.setSingleLine(true);messageField.setTextColor(WHITE);
        messageField.setHintTextColor(MUTED);messageField.setHint("Message or /command…");messageField.setTextSize(14);
        messageField.setBackgroundColor(Color.TRANSPARENT);messageField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composer.addView(messageField,new LinearLayout.LayoutParams(0,-1,1));
        TextView send=label("↑",23,BG,true);send.setGravity(Gravity.CENTER);send.setBackground(shape(GREEN,0,12));
        composer.addView(send,new LinearLayout.LayoutParams(dp(42),dp(42)));
        send.setOnClickListener(v->submit());messageField.setOnEditorActionListener((v,id,event)->{
            if(id==EditorInfo.IME_ACTION_SEND){submit();return true;}return false;
        });
    }
    private void handle(JSONObject f){
        String type=f.optString("type","");
        switch(type){
            case "system":addSystem(f.optString("text",""));send(obj("type","users"));break;
            case "you":username=f.optString("username",username);room=f.optString("room",room);
                if(roomLabel!=null)roomLabel.setText("#"+room+" · E2E ENCRYPTED");addSystem("You are "+username+" in #"+room);break;
            case "users":
                JSONArray a=f.optJSONArray("users");if(a!=null){ArrayList<String> names=new ArrayList<>();
                    for(int i=0;i<a.length();i++)names.add(a.optString(i));
                    if(users!=null)users.setText("ONLINE · "+android.text.TextUtils.join("  •  ",names));}break;
            case "pong":Long sent=pings.remove(f.optString("id"));if(sent!=null)addSystem("Ping: "+(System.currentTimeMillis()-sent)+" ms");break;
            case "chat":case "pm":showMessage(f,type.equals("pm"));break;
        }
    }
    private void showMessage(JSONObject f,boolean pm){
        String who=f.optString("username","Unknown");
        try{
            JSONObject data=unseal(f.getString("ciphertext"));String body=data.optString("t","");
            long ts=data.optLong("ts",System.currentTimeMillis()/1000);
            String time=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(ts*1000));
            String prefix=pm?"PRIVATE MESSAGE":(data.optString("k","").equals("me")?"ACTION":"MESSAGE");
            addLine("["+time+"] "+prefix+" · "+who,body,who.equals(username)?GREEN:WHITE);
        }catch(Exception e){addError("Cannot decrypt message from "+who+". Verify the room password.");}
    }
    private void addSystem(String s){addLine("SYSTEM",s,MUTED);}
    private void addError(String s){addLine("NOTICE",s,RED);}
    private void addLine(String head,String body,int color){
        if(messages==null)return;
        LinearLayout bubble=col();bubble.setPadding(dp(11),dp(8),dp(11),dp(8));
        bubble.setBackground(shape(0xFF14212A,0xFF22333E,10));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(7);messages.addView(bubble,p);
        bubble.addView(label(head.toUpperCase(Locale.ROOT),9,color,true));
        TextView content=label(body,14,WHITE,false);content.setPadding(0,dp(3),0,0);content.setTextIsSelectable(true);bubble.addView(content);
        transcript.add(head+": "+body);if(transcript.size()>2000)transcript.remove(0);
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void submit(){
        String line=messageField.getText().toString().trim();messageField.setText("");
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(messageField.getWindowToken(),0);
        if(!line.isEmpty())command(line);
    }
    private void command(String line){
        if(!line.startsWith("/")){
            if(line.length()>8000){addError("Message too long (maximum 8000 characters)");return;}
            try{send(obj("type","chat","ciphertext",seal(line,"msg")));addLine("YOU",line,GREEN);}
            catch(Exception e){addError("Encryption failed");}return;
        }
        String[] parts=line.split("\\s+",3);String cmd=parts[0].toLowerCase(Locale.ROOT);
        String arg=line.length()>cmd.length()?line.substring(cmd.length()).trim():"";
        switch(cmd){
            case "/help":addSystem("/users  /nick NAME  /join ROOM  /msg USER TEXT  /me TEXT  /ping  /fingerprint  /save  /clear  /quit");break;
            case "/users":send(obj("type","users"));break;
            case "/nick":if(!arg.isEmpty())send(obj("type","nick","username",arg));else addError("Usage: /nick NAME");break;
            case "/join":if(!arg.isEmpty())send(obj("type","join","room",arg));else addError("Usage: /join ROOM");break;
            case "/msg":
                if(parts.length==3){String[] p=arg.split("\\s+",2);try{send(obj("type","pm","to",p[0],"ciphertext",seal(p[1],"msg")));addLine("PRIVATE TO "+p[0],p[1],GREEN);}catch(Exception e){addError("Usage: /msg USER TEXT");}}
                else addError("Usage: /msg USER TEXT");break;
            case "/me":if(!arg.isEmpty())try{send(obj("type","chat","ciphertext",seal(arg,"me")));addLine("ACTION","* "+username+" "+arg,GREEN);}catch(Exception e){addError("Encryption failed");}break;
            case "/ping":String id=String.valueOf(System.currentTimeMillis());pings.put(id,System.currentTimeMillis());send(obj("type","ping","id",id));break;
            case "/fingerprint":addSystem("Room key fingerprint: "+fingerprint());break;
            case "/clear":messages.removeAllViews();transcript.clear();break;
            case "/save":shareTranscript();break;
            case "/quit":disconnect(true);loginScreen();break;
            default:addError("Unknown command. Type /help");
        }
    }
    private String seal(String message,String kind)throws Exception{
        JSONObject payload=obj("t",message,"ts",System.currentTimeMillis()/1000,"k",kind);
        byte[] nonce=new byte[24];new SecureRandom().nextBytes(nonce);
        byte[] plain=payload.toString().getBytes(StandardCharsets.UTF_8),cipher=new byte[plain.length+16];
        if(!sodium.cryptoSecretBoxEasy(cipher,plain,plain.length,nonce,key))throw new Exception("SecretBox failed");
        byte[] full=new byte[nonce.length+cipher.length];System.arraycopy(nonce,0,full,0,24);
        System.arraycopy(cipher,0,full,24,cipher.length);
        return android.util.Base64.encodeToString(full,android.util.Base64.NO_WRAP);
    }
    private JSONObject unseal(String encoded)throws Exception{
        byte[] full=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);
        if(full.length<40)throw new Exception("Invalid ciphertext");
        byte[] nonce=Arrays.copyOfRange(full,0,24),cipher=Arrays.copyOfRange(full,24,full.length);
        byte[] plain=new byte[cipher.length-16];
        if(!sodium.cryptoSecretBoxOpenEasy(plain,cipher,cipher.length,nonce,key))throw new Exception("Authentication failed");
        return new JSONObject(new String(plain,StandardCharsets.UTF_8));
    }
    private String fingerprint(){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-512");md.update("SecureChat-fp".getBytes(StandardCharsets.UTF_8));
            byte[] d=md.digest(key);StringBuilder h=new StringBuilder();
            for(byte x:Arrays.copyOf(d,8))h.append(String.format(Locale.ROOT,"%02x",x&255));
            String z=h.toString();return z.substring(0,4)+" "+z.substring(4,8)+" "+z.substring(8,12)+" "+z.substring(12,16);
        }catch(Exception e){return "unavailable";}
    }
    private JSONObject obj(Object... pairs){
        JSONObject o=new JSONObject();try{for(int i=0;i+1<pairs.length;i+=2)o.put(String.valueOf(pairs[i]),pairs[i+1]);}catch(Exception ignored){}return o;
    }
    private void send(JSONObject o){WebSocket s=socket;if(s!=null&&o!=null)s.send(o.toString());}
    private void shareTranscript(){
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,android.text.TextUtils.join("\n",transcript));
        startActivity(Intent.createChooser(i,"Share SecureApp chat"));
    }
    private void disconnect(boolean explicit){
        manual=explicit;joined=false;main.removeCallbacks(heartbeat);
        if(socket!=null){send(obj("type","quit"));socket.close(1000,"client quit");socket=null;}
        if(explicit)key=null;
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override public void onBackPressed(){if(joined){disconnect(true);loginScreen();}else super.onBackPressed();}
    @Override protected void onDestroy(){disconnect(true);crypto.shutdownNow();http.dispatcher().executorService().shutdown();super.onDestroy();}
}
