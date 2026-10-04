package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public class GamePickerActivity extends Activity {
    private static final int ACCENT=Color.rgb(190,112,255);
    private static final int ACCENT_SOFT=Color.rgb(216,170,255);
    private static final int TEXT=Color.rgb(236,244,242);
    private static final int MUTED=Color.rgb(154,173,171);

    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final List<AppEntry> launcherApps=new ArrayList<>();
    private final Set<String> selected=new LinkedHashSet<>();

    private GridLayout grid;
    private EditText search;
    private TextView count;
    private ProgressBar progress;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        prefs=getSharedPreferences("game_library",MODE_PRIVATE);
        setContentView(buildUi());
        load();
    }

    private View buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_app);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0,bottom=0;
            if(Build.VERSION.SDK_INT>=30){
                Insets bars=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.navigationBars());
                top=bars.top;
                bottom=bars.bottom;
            }else{
                top=insets.getSystemWindowInsetTop();
                bottom=insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(12),top+dp(10),dp(12),bottom+dp(10));
            return insets;
        });
        root.requestApplyInsets();

        LinearLayout top=row();
        Button back=button("← Back",ACCENT,v->finish());
        back.setTextColor(Color.rgb(25,10,32));
        back.setBackground(tintedCard(ACCENT,190));
        top.addView(back,new LinearLayout.LayoutParams(dp(86),dp(44)));

        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8),0,0,0);
        titles.addView(text("Add App",26,TEXT,true));
        titles.addView(text("Choose any installed launcher app",11,ACCENT_SOFT,true));
        top.addView(titles,new LinearLayout.LayoutParams(0,-2,1));

        TextView badge=text("APPS",10,Color.rgb(25,10,32),true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(pill(ACCENT));
        top.addView(badge,new LinearLayout.LayoutParams(dp(58),dp(30)));
        root.addView(top);

        LinearLayout searchRow=row();
        searchRow.setPadding(0,dp(10),0,dp(8));

        search=new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search apps…");
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(14);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(13),0,dp(13),0);
        search.setBackground(tintedCard(ACCENT,45));
        searchRow.addView(search,new LinearLayout.LayoutParams(0,dp(46),1));

        Button searchButton=button("Search",ACCENT,v->renderGrid(search.getText().toString()));
        LinearLayout.LayoutParams sbp=new LinearLayout.LayoutParams(dp(92),dp(46));
        sbp.setMargins(dp(7),0,0,0);
        searchRow.addView(searchButton,sbp);
        root.addView(searchRow);

        LinearLayout meta=row();
        count=text("Loading installed apps…",10,MUTED,false);
        meta.addView(count,new LinearLayout.LayoutParams(0,-2,1));

        progress=new ProgressBar(this);
        meta.addView(progress,new LinearLayout.LayoutParams(dp(26),dp(26)));
        root.addView(meta);

        ScrollView scroller=new ScrollView(this);
        scroller.setFillViewport(true);

        grid=new GridLayout(this);
        grid.setColumnCount(4);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        grid.setPadding(0,dp(8),0,dp(20));
        scroller.addView(grid,new ScrollView.LayoutParams(-1,-2));

        root.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));

        search.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                renderGrid(s==null?"":s.toString());
            }
            @Override public void afterTextChanged(Editable e){}
        });
        search.setOnEditorActionListener((v,action,event)->{
            if(action==EditorInfo.IME_ACTION_SEARCH){
                renderGrid(search.getText().toString());
                return true;
            }
            return false;
        });

        return root;
    }

    private void load(){
        io.execute(()->{
            RootBridge.Result current=RootBridge.get().ctl("game list");
            selected.clear();
            if(current.ok()){
                for(String line:current.output.split("\\R")){
                    String pkg=line.trim();
                    if(!pkg.isEmpty())selected.add(pkg);
                }
            }

            scanLauncherApps();

            runOnUiThread(()->{
                progress.setVisibility(View.GONE);
                renderGrid(search.getText().toString());
            });
        });
    }

    private void scanLauncherApps(){
        launcherApps.clear();

        Intent launcher=new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        PackageManager pm=getPackageManager();
        List<ResolveInfo> resolved=Build.VERSION.SDK_INT>=33
                ? pm.queryIntentActivities(launcher,PackageManager.ResolveInfoFlags.of(0))
                : pm.queryIntentActivities(launcher,0);

        Map<String,AppEntry> unique=new LinkedHashMap<>();
        for(ResolveInfo r:resolved){
            if(r.activityInfo==null||r.activityInfo.packageName==null)continue;
            String pkg=r.activityInfo.packageName;
            if(pkg.equals(getPackageName()))continue;

            CharSequence raw=r.loadLabel(pm);
            String name=raw==null?pkg:raw.toString();
            Drawable icon=r.loadIcon(pm);

            boolean androidGame=false;
            try{
                ApplicationInfo ai=Build.VERSION.SDK_INT>=33
                        ? pm.getApplicationInfo(pkg,PackageManager.ApplicationInfoFlags.of(0))
                        : pm.getApplicationInfo(pkg,0);
                if(Build.VERSION.SDK_INT>=26)androidGame=ai.category==ApplicationInfo.CATEGORY_GAME;
            }catch(Exception ignored){}

            unique.put(pkg,new AppEntry(name,pkg,icon,androidGame));
        }

        launcherApps.addAll(unique.values());
        launcherApps.sort(Comparator.comparing(a->a.name.toLowerCase(Locale.US)));
    }

    private void renderGrid(String query){
        if(grid==null)return;
        grid.removeAllViews();

        String needle=query==null?"":query.trim().toLowerCase(Locale.US);
        List<AppEntry> visible=new ArrayList<>();
        for(AppEntry app:launcherApps){
            if(selected.contains(app.pkg))continue;
            if(!needle.isEmpty() &&
                    !app.name.toLowerCase(Locale.US).contains(needle) &&
                    !app.pkg.toLowerCase(Locale.US).contains(needle))continue;
            visible.add(app);
        }

        count.setText(visible.size()+" available app"+(visible.size()==1?"":"s"));

        if(visible.isEmpty()){
            TextView empty=text(
                    needle.isEmpty()?"All launcher apps are already in the game library.":"No apps match “"+query+"”.",
                    12,MUTED,false);
            empty.setGravity(Gravity.CENTER);
            GridLayout.LayoutParams ep=new GridLayout.LayoutParams();
            ep.columnSpec=GridLayout.spec(0,4);
            ep.width=GridLayout.LayoutParams.MATCH_PARENT;
            ep.height=dp(100);
            grid.addView(empty,ep);
            return;
        }

        int screen=getResources().getDisplayMetrics().widthPixels;
        int cellWidth=Math.max(dp(76),(screen-dp(28))/4);

        for(int i=0;i<visible.size();i++){
            AppEntry app=visible.get(i);
            LinearLayout tile=appTile(app);
            GridLayout.LayoutParams p=new GridLayout.LayoutParams();
            p.width=cellWidth;
            p.height=dp(116);
            p.columnSpec=GridLayout.spec(i%4);
            p.rowSpec=GridLayout.spec(i/4);
            p.setMargins(dp(2),dp(3),dp(2),dp(3));
            grid.addView(tile,p);
        }
    }

    private LinearLayout appTile(AppEntry app){
        LinearLayout tile=new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(5),dp(8),dp(5),dp(6));
        tile.setBackground(tintedCard(app.androidGame?ACCENT:Color.rgb(72,105,108),app.androidGame?52:30));
        tile.setClickable(true);
        tile.setFocusable(true);

        ImageView icon=new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if(app.icon!=null)icon.setImageDrawable(app.icon);
        tile.addView(icon,new LinearLayout.LayoutParams(dp(58),dp(58)));

        TextView name=text(app.name,10,TEXT,true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,0,1);
        np.setMargins(0,dp(4),0,0);
        tile.addView(name,np);

        if(app.androidGame){
            TextView game=text("GAME",7,ACCENT_SOFT,true);
            game.setGravity(Gravity.CENTER);
            tile.addView(game,new LinearLayout.LayoutParams(-1,dp(14)));
        }

        tile.setContentDescription("Add "+app.name);
        tile.setOnClickListener(v->addApp(app));
        return tile;
    }

    private void addApp(AppEntry app){
        io.execute(()->{
            RootBridge.Result r=RootBridge.get().ctl("game add "+app.pkg);
            if(r.ok()){
                selected.add(app.pkg);

                Set<String> ignored=new LinkedHashSet<>(
                        prefs.getStringSet("ignored_games",Collections.emptySet()));
                ignored.remove(app.pkg);
                prefs.edit().putStringSet("ignored_games",ignored).apply();

                runOnUiThread(()->{
                    Toast.makeText(this,app.name+" added",Toast.LENGTH_SHORT).show();
                    renderGrid(search.getText().toString());
                });
            }else{
                runOnUiThread(()->Toast.makeText(
                        this,"Could not add "+app.name,Toast.LENGTH_LONG).show());
            }
        });
    }

    private LinearLayout row(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private Button button(String label,int color,View.OnClickListener listener){
        Button b=new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(11);
        b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        b.setTextColor(TEXT);
        b.setBackground(tintedCard(color,100));
        b.setOnClickListener(listener);
        return b;
    }

    private TextView text(String value,int sp,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }

    private GradientDrawable tintedCard(int color,int alpha){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(11,18,19),
                        Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color)),
                        Color.rgb(8,14,15)
                });
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1),Color.argb(105,Color.red(color),Color.green(color),Color.blue(color)));
        return g;
    }

    private GradientDrawable pill(int color){
        GradientDrawable g=new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(999));
        return g;
    }

    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy(){
        io.shutdownNow();
        super.onDestroy();
    }

    private static final class AppEntry{
        final String name,pkg;
        final Drawable icon;
        final boolean androidGame;

        AppEntry(String name,String pkg,Drawable icon,boolean androidGame){
            this.name=name;
            this.pkg=pkg;
            this.icon=icon;
            this.androidGame=androidGame;
        }
    }
}
