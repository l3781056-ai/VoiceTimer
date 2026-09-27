package com.example.voicetimer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var display: TextView
    private lateinit var status: TextView
    private lateinit var total: TextView
    private lateinit var control: Button
    private lateinit var prefs: SharedPreferences
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val PREFS="VoiceTimerPrefs"
        private const val RUNNING="running"; private const val PAUSED="paused"
        private const val REMAINING="remaining_seconds"; private const val CONFIGURED="configured_seconds"
        private const val ALARMING="alarming"; private const val END="end_elapsed"
        private const val TOTAL="total_work_seconds"; private const val COUNT="total_work_count"
        private const val INTERVAL="setting_interval_remind_mode"
    }

    private val ticker=object:Runnable{override fun run(){refresh();handler.postDelayed(this,500)}}

    override fun onCreate(b:Bundle?){super.onCreate(b);prefs=getSharedPreferences(PREFS,MODE_PRIVATE);tts=TextToSpeech(this,this);requestNotification();build();}

    private fun build(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setBackgroundColor(Color.BLACK);setPadding(24,20,24,20)}
        val head=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val title=TextView(this).apply{text="浩川計時器";textSize=12f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD);layoutParams=LinearLayout.LayoutParams(0,60,1f)}
        val set=Button(this).apply{text="⚙";textSize=22f;setOnClickListener{startActivity(Intent(this@MainActivity,SettingsActivity::class.java))}}
        head.addView(title);head.addView(set)
        display=TextView(this).apply{textSize=86f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD);gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(-1,0,1f)}
        total=TextView(this).apply{textSize=16f;setTextColor(Color.WHITE);gravity=Gravity.CENTER}
        val reset=Button(this).apply{text="清除";textSize=14f;minWidth=0;minimumWidth=0;minHeight=0;minimumHeight=0;setPadding(18,4,18,4);setOnClickListener{AlertDialog.Builder(this@MainActivity).setTitle("清除累計").setMessage("確定將累計時間與完成次數歸零嗎？").setNegativeButton("取消",null).setPositiveButton("確定"){_,_->prefs.edit().putLong(TOTAL,0).putLong(COUNT,0).apply();refresh()}.show()}}
        val voice=Button(this).apply{text="🎤 語音輸入時間";textSize=19f;setOnClickListener{speech()}}
        control=Button(this).apply{text="開始";textSize=22f;setTypeface(null,Typeface.BOLD);setTextColor(Color.WHITE);setOnClickListener{controlClick()}}
        val totalRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT).apply{bottomMargin=12}}
        total.layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f).apply{leftMargin=8}
        totalRow.addView(total)
        totalRow.addView(reset)
        root.addView(head);root.addView(display);root.addView(totalRow);root.addView(voice);root.addView(control);setContentView(root)
        display.setOnTouchListener(object:View.OnTouchListener{
            var sx=0f;var sy=0f
            override fun onTouch(v:View,e:MotionEvent):Boolean{when(e.action){MotionEvent.ACTION_DOWN->{sx=e.x;sy=e.y;return true};MotionEvent.ACTION_UP->{val dy=sy-e.y;val running=prefs.getBoolean(RUNNING,false);if(running&&!prefs.getBoolean(PAUSED,false)&&abs(dy)<60){speakRemaining()}else if(!running&&abs(dy)>60){val d=if(sx<display.width*2f/3f)300L else 60L;adjust(if(dy>0)d else -d)};return true}};return true}
        })
    }

    private fun controlClick(){
        when{
            prefs.getBoolean(ALARMING,false)->send(TimerService.ACTION_ALARM_STOP)
            prefs.getBoolean(RUNNING,false)&&!prefs.getBoolean(PAUSED,false)->send(TimerService.ACTION_PAUSE)
            prefs.getBoolean(PAUSED,false)->send(TimerService.ACTION_RESUME)
            else->send(TimerService.ACTION_START,prefs.getLong(CONFIGURED,300).coerceIn(1,10800))
        }
        refresh()
    }
    private fun send(action:String,seconds:Long?=null){val i=Intent(this,TimerService::class.java).apply{this.action=action;if(seconds!=null)putExtra(TimerService.EXTRA_SECONDS,seconds)};if(Build.VERSION.SDK_INT>=26)startForegroundService(i) else startService(i)}
    private fun adjust(d:Long){val n=(prefs.getLong(CONFIGURED,300)+d).coerceIn(60,10800);prefs.edit().putLong(CONFIGURED,n).putLong(REMAINING,n).apply();speak("設定 "+(n/60)+" 分鐘");refresh()}
    private fun speech(){try{startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-TW");putExtra(RecognizerIntent.EXTRA_PROMPT,"請說出時間，例如5分鐘")},1001)}catch(_:Exception){Toast.makeText(this,"裝置未支援語音識別服務",Toast.LENGTH_SHORT).show()}}
    override fun onActivityResult(r:Int,c:Int,d:Intent?){super.onActivityResult(r,c,d);if(r==1001&&c==RESULT_OK){val s=d?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?:"";parse(s)}}
    private fun parse(s:String){val x=s.replace(" ","").replace("個","");val h=Regex("(\\d+)小時").find(x)?.groupValues?.get(1)?.toLongOrNull()?:0;val m=Regex("(\\d+)分").find(x)?.groupValues?.get(1)?.toLongOrNull()?:0;val sec=Regex("(\\d+)秒").find(x)?.groupValues?.get(1)?.toLongOrNull()?:0;var n=h*3600+m*60+sec;if(h==0L&&m==0L&&sec==0L)n=(Regex("^\\d+$").find(x)?.value?.toLongOrNull()?:0)*60;if(n<=0){Toast.makeText(this,"未能辨識時間："+s,Toast.LENGTH_SHORT).show();return};n=n.coerceIn(1,10800);prefs.edit().putLong(CONFIGURED,n).putLong(REMAINING,n).apply();speak("開始倒數 "+(n/60)+" 分鐘");send(TimerService.ACTION_START,n)}
    private fun remain():Long{if(!prefs.getBoolean(RUNNING,false))return prefs.getLong(REMAINING,prefs.getLong(CONFIGURED,300)).coerceAtLeast(0);val e=prefs.getLong(END,0);return if(e>0)((e-SystemClock.elapsedRealtime())/1000).coerceAtLeast(0) else prefs.getLong(REMAINING,0)}
    private fun speakRemaining(){val n=remain();speak(if(n/60>0)"還剩 "+(n/60)+" 分鐘" else "還剩 "+n+" 秒")}
    private fun speak(s:String){if(ttsReady)tts?.speak(s,TextToSpeech.QUEUE_FLUSH,null,"VoiceTimerMain")}
    private fun refresh(){val n=remain();display.text=String.format(Locale.TAIWAN,"%02d:%02d",n/60,n%60);val t=prefs.getLong(TOTAL,0);total.text="累計 "+(t/60)+" 分 "+prefs.getLong(COUNT,0)+" 次";control.text=when{prefs.getBoolean(ALARMING,false)->"停止警報";prefs.getBoolean(PAUSED,false)->"繼續（長按重設）";prefs.getBoolean(RUNNING,false)->"暫停";else->"開始"}}
    private fun requestNotification(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),101)}
    override fun onInit(s:Int){if(s==TextToSpeech.SUCCESS){tts?.language=Locale.TAIWAN;ttsReady=true}}
    override fun onResume(){super.onResume();handler.post(ticker)}
    override fun onPause(){super.onPause();handler.removeCallbacks(ticker)}
    override fun onDestroy(){tts?.stop();tts?.shutdown();super.onDestroy()}
}