package com.example.voicetimer

import android.app.*
import android.content.*
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.*
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
import java.util.*
 
class TimerService:Service(),TextToSpeech.OnInitListener{
 private var tts:TextToSpeech?=null;private var ready=false;private var pending:String?=null;private var ring:Ringtone?=null;private val tickerHandler=Handler(Looper.getMainLooper());private var tickerRunnable:Runnable?=null
 companion object{
  const val ACTION_START="com.example.voicetimer.START";const val ACTION_PAUSE="com.example.voicetimer.PAUSE";const val ACTION_RESUME="com.example.voicetimer.RESUME";const val ACTION_STOP="com.example.voicetimer.STOP";const val ACTION_REMINDER="com.example.voicetimer.REMINDER";const val ACTION_FINISH="com.example.voicetimer.FINISH";const val ACTION_ALARM_STOP="com.example.voicetimer.ALARM_STOP";const val ACTION_CLOCK_CHIME="com.example.voicetimer.CLOCK_CHIME";const val EXTRA_SECONDS="seconds"
  const val P="VoiceTimerPrefs";const val RUN="running";const val PAUSE="paused";const val REM="remaining_seconds";const val END="end_elapsed";const val CFG="configured_seconds";const val ALARM="alarming";const val TOTAL="total_work_seconds";const val COUNT="total_work_count";const val INTERVAL="setting_interval_remind_mode";const val MUSIC="setting_music_type";const val CHIME="setting_clock_chime_mode";const val CH="voice_timer_running_v5";const val NID=10;const val RR=2001;const val FR=2002;const val CR=2003
 }
 override fun onCreate(){super.onCreate();channel();tts=TextToSpeech(this,this)}
 override fun onInit(s:Int){if(s==TextToSpeech.SUCCESS){val r=tts?.setLanguage(Locale.TAIWAN);ready=r!=TextToSpeech.LANG_MISSING_DATA&&r!=TextToSpeech.LANG_NOT_SUPPORTED;pending?.let{say(it);pending=null}}}
 override fun onBind(i:Intent?):IBinder?=null
 override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{when(i?.action){ACTION_START->startNew(i.getLongExtra(EXTRA_SECONDS,300));ACTION_RESUME->resume();ACTION_PAUSE->pause();ACTION_STOP->stopTimer();ACTION_REMINDER->reminder();ACTION_FINISH->finishTimer();ACTION_CLOCK_CHIME->chime();ACTION_ALARM_STOP->stopAlarm()};return START_NOT_STICKY}
 private fun prefs()=getSharedPreferences(P,MODE_PRIVATE)
 private fun startNew(s:Long){val n=s.coerceIn(1,10800);val e=SystemClock.elapsedRealtime()+n*1000;prefs().edit().putBoolean(RUN,true).putBoolean(PAUSE,false).putBoolean(ALARM,false).putLong(REM,n).putLong(CFG,n).putLong(END,e).apply();safeStartForeground(note("浩川計時器正在倒數",fmt(n),true));schedule(n)}
 private fun resume(){val n=prefs().getLong(REM,0).coerceIn(1,10800);if(n<=0)return;val e=SystemClock.elapsedRealtime()+n*1000;prefs().edit().putBoolean(RUN,true).putBoolean(PAUSE,false).putLong(END,e).apply();safeStartForeground(note("浩川計時器正在倒數",fmt(n),true));schedule(n)}
 private fun pause(){val n=remaining();cancelAll();prefs().edit().putBoolean(RUN,false).putBoolean(PAUSE,true).putLong(REM,n).remove(END).apply();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
 private fun stopTimer(){cancelAll();prefs().edit().putBoolean(RUN,false).putBoolean(PAUSE,false).remove(END).apply();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
 private fun reminder(){if(!prefs().getBoolean(RUN,false)){stopSelf();return};val n=remaining();if(n<=0){finishTimer();return};safeStartForeground(note("浩川計時器提醒","還剩 "+speechTime(n),true));say("還剩 "+speechTime(n));nextReminder(n)}
 private fun finishTimer(){val p=prefs();val cfg=p.getLong(CFG,0);cancelAll();p.edit().putBoolean(RUN,false).putBoolean(PAUSE,false).putBoolean(ALARM,true).putLong(REM,0).remove(END).putLong(TOTAL,p.getLong(TOTAL,0)+cfg).putLong(COUNT,p.getLong(COUNT,0)+1).apply();safeStartForeground(note("浩川計時器","時間到了！",false));say("時間到了！");alarmSound()}
 private fun stopAlarm(){ring?.stop();ring=null;tts?.stop();cancelAll();val cfg=prefs().getLong(CFG,300).coerceIn(1,10800);prefs().edit().putBoolean(RUN,false).putBoolean(PAUSE,false).putBoolean(ALARM,false).putLong(REM,cfg).remove(END).apply();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
 private fun startTicker(){stopTicker();tickerRunnable=object:Runnable{override fun run(){if(!prefs().getBoolean(RUN,false))return;val e=prefs().getLong(END,0);if(e>0private fun schedule(n:Long){private fun schedule(n:Long){SystemClock.elapsedRealtime()>=e){finishTimer()}else{tickerHandler.postDelayed(this,1000)}}};tickerHandler.post(tickerRunnable!!)} private fun stopTicker(){tickerRunnable?.let{tickerHandler.removeCallbacks(it)};tickerRunnable=null} private fun schedule(n:Long){startTicker();cancelAll();val am=getSystemService(ALARM_SERVICE) as AlarmManager;setExact(am,SystemClock.elapsedRealtime()+n*1000,pending(ACTION_FINISH,FR));nextReminder(n);clock()}
 private fun nextReminder(n:Long){val mode=prefs().getInt(INTERVAL,2);val iv=when(mode){1->180L;2->300L;else->0};if(iv<=0||n<=iv){cancelReminder();return};val am=getSystemService(ALARM_SERVICE) as AlarmManager;setExact(am,SystemClock.elapsedRealtime()+iv*1000,pending(ACTION_REMINDER,RR))}
 private fun clock(){val mode=prefs().getInt(CHIME,0);if(mode==0){cancelClock();return};val step=when(mode){1->60;2->30;else->15};val now=Calendar.getInstance();val next=Calendar.getInstance();next.set(Calendar.SECOND,0);next.set(Calendar.MILLISECOND,0);next.add(Calendar.MINUTE,step-(now.get(Calendar.MINUTE)%step));if(!next.after(now))next.add(Calendar.MINUTE,step);val am=getSystemService(ALARM_SERVICE) as AlarmManager;val pi=pending(ACTION_CLOCK_CHIME,CR);try{if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.timeInMillis,pi)else am.setExact(AlarmManager.RTC_WAKEUP,next.timeInMillis,pi)}catch(_:Exception){try{am.set(AlarmManager.RTC_WAKEUP,next.timeInMillis,pi)}catch(_:Exception){}}}
 private fun chime(){if(!prefs().getBoolean(RUN,false)){stopSelf();return};say("現在 "+SimpleDateFormat("a h 點",Locale.TAIWAN).format(Date()));notifySound();clock()}
 private fun pending(a:String,r:Int)=PendingIntent.getBroadcast(this,r,Intent(this,AlarmReceiver::class.java).apply{action=a;setPackage(packageName)},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 private fun setExact(am:AlarmManager,t:Long,p:PendingIntent){try{if(Build.VERSION.SDK_INT>=23)am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,t,p)else am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,t,p)}catch(_:SecurityException){try{am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,t,p)}catch(_:Exception){}}catch(_:Exception){}}
 private fun cancelAll(){stopTicker();cancelReminder();cancelClock();val am=getSystemService(ALARM_SERVICE) as AlarmManager;am.cancel(noCreate(ACTION_FINISH,FR))}
 private fun cancelReminder(){(getSystemService(ALARM_SERVICE) as AlarmManager).cancel(noCreate(ACTION_REMINDER,RR))}
 private fun cancelClock(){(getSystemService(ALARM_SERVICE) as AlarmManager).cancel(noCreate(ACTION_CLOCK_CHIME,CR))}
 private fun noCreate(a:String,r:Int):PendingIntent{val i=Intent(this,AlarmReceiver::class.java).apply{action=a;setPackage(packageName)};return PendingIntent.getBroadcast(this,r,i,PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?:PendingIntent.getBroadcast(this,r,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
 private fun remaining():Long{val p=prefs();if(!p.getBoolean(RUN,false))return p.getLong(REM,p.getLong(CFG,300)).coerceAtLeast(0);val e=p.getLong(END,0);val n=if(e>0)((e-SystemClock.elapsedRealtime())/1000).coerceAtLeast(0)else p.getLong(REM,0);p.edit().putLong(REM,n).apply();return n}
 private fun safeStartForeground(n:Notification){try{if(Build.VERSION.SDK_INT>=34){startForeground(NID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)}else if(Build.VERSION.SDK_INT>=29){startForeground(NID,n,0)}else{startForeground(NID,n)}}catch(_:Exception){}} private fun channel(){if(Build.VERSION.SDK_INT>=26)(getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CH,"VoiceTimer 計時服務",NotificationManager.IMPORTANCE_LOW))}
 private fun note(t:String,x:String,o:Boolean)=Notification.Builder(this,CH).setContentTitle(t).setContentText(x).setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(o).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_ALARM).build()
 private fun say(s:String){if(ready)tts?.speak(s,TextToSpeech.QUEUE_ADD,null,"VoiceTimerSpeech")else pending=s}
 private fun alarmSound(){val type=when(prefs().getInt(MUSIC,0)){1->RingtoneManager.TYPE_NOTIFICATION;2->RingtoneManager.TYPE_RINGTONE;else->RingtoneManager.TYPE_ALARM};play(type)}
 private fun notifySound()=play(RingtoneManager.TYPE_NOTIFICATION)
 private fun play(type:Int){try{ring?.stop();val u=RingtoneManager.getDefaultUri(type)?:RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);ring=RingtoneManager.getRingtone(applicationContext,u);if(Build.VERSION.SDK_INT>=21){ring?.audioAttributes=android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()};ring?.play()}catch(_:Exception){}}
 private fun fmt(n:Long)=String.format(Locale.TAIWAN,"%02d:%02d",n/60,n%60)
 private fun speechTime(n:Long):String{val m=n/60;val s=n%60;return if(m>0&&s>0)"$m 分 $s 秒" else if(m>0)"$m 分鐘" else "$s 秒"}
 override fun onDestroy(){ring?.stop();tts?.stop();tts?.shutdown();ring=null;tts=null;super.onDestroy()}
}