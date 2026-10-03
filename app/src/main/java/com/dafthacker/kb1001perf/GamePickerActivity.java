package com.dafthacker.kb1001perf;

import android.app.*;
import android.content.Intent;
import android.content.pm.*;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

import java.util.*;
import java.util.concurrent.*;

public class GamePickerActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps=new ArrayList<>();
    private ListView list;
    private ProgressBar progress;

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(ui());load();}

    private View ui(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16),dp(16),dp(16),dp(16));root.setBackgroundResource(R.drawable.bg_app);
        TextView t=new TextView(this);t.setText("AutoBoost Games");t.setTextColor(Color.WHITE);t.setTextSize(25);root.addView(t);
        TextView h=new TextView(this);h.setText("Checked apps trigger the game profile. Detection continues with this app closed.");
        h.setTextColor(Color.rgb(156,176,201));h.setPadding(0,dp(4),0,dp(8));root.addView(h);
        progress=new ProgressBar(this);root.addView(progress);
        list=new ListView(this);list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        Button save=new Button(this);save.setText("SAVE GAME LIST");save.setTextColor(Color.WHITE);save.setBackgroundResource(R.drawable.bg_button_primary);
        save.setOnClickListener(v->save());root.addView(save);return root;
    }

    private void load(){io.execute(()->{
        RootBridge.Result cur=RootBridge.get().ctl("game list");Set<String> selected=new HashSet<>();
        if(cur.ok())for(String x:cur.output.split("\\R"))if(!x.trim().isEmpty())selected.add(x.trim());
        Intent in=new Intent(Intent.ACTION_MAIN);in.addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm=getPackageManager();List<ResolveInfo> rs=Build.VERSION.SDK_INT>=33?
                pm.queryIntentActivities(in,PackageManager.ResolveInfoFlags.of(0)):pm.queryIntentActivities(in,0);
        Map<String,AppEntry> unique=new LinkedHashMap<>();
        for(ResolveInfo r:rs){if(r.activityInfo==null)continue;String p=r.activityInfo.packageName;if(p.equals(getPackageName()))continue;
            CharSequence l=r.loadLabel(pm);unique.put(p,new AppEntry(l==null?p:l.toString(),p));}
        apps.clear();apps.addAll(unique.values());apps.sort(Comparator.comparing(a->a.label.toLowerCase()));
        ArrayList<String> rows=new ArrayList<>();for(AppEntry a:apps)rows.add(a.label+"\n"+a.pkg);
        runOnUiThread(()->{list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_multiple_choice,rows));
            for(int i=0;i<apps.size();i++)if(selected.contains(apps.get(i).pkg))list.setItemChecked(i,true);progress.setVisibility(View.GONE);});
    });}

    private void save(){List<String>s=new ArrayList<>();for(int i=0;i<apps.size();i++)if(list.isItemChecked(i))s.add(apps.get(i).pkg);
        progress.setVisibility(View.VISIBLE);io.execute(()->{RootBridge.Result r=RootBridge.get().ctl("game clear");boolean ok=r.ok();String err=r.output;
            if(ok)for(String p:s){r=RootBridge.get().ctl("game add "+p);if(!r.ok()){ok=false;err=r.output;break;}}
            boolean good=ok;String e=err;runOnUiThread(()->{progress.setVisibility(View.GONE);if(good){Toast.makeText(this,"Saved "+s.size()+" games",Toast.LENGTH_SHORT).show();finish();}
            else new AlertDialog.Builder(this).setTitle("Could not save").setMessage(e).setPositiveButton("OK",null).show();});});}

    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
    static class AppEntry{final String label,pkg;AppEntry(String l,String p){label=l;pkg=p;}}
}
