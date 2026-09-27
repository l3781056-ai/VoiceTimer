package com.example.voicetimer

import android.app.Activity
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Bundle
import android.view.Gravity
import android.widget.*

class SettingsActivity:Activity(){
 companion object{const val P="VoiceTimerPrefs";const val MUSIC="setting_music_type";const val CHIME="setting_clock_chime_mode";const val INTERVAL="setting_interval_remind_mode"}
 override fun onCreate(b:Bundle?){super.onCreate(b);val p=getSharedPreferences(P,MODE_PRIVATE);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(30,25,30,25);setBackgroundColor(Color.BLACK)}
 val title=TextView(this).apply{text="計時器設定";textSize=26f;setTextColor(Color.WHITE);gravity=Gravity.CENTER};root.addView(title)
 fun label(s:String){root.addView(TextView(this).apply{text=s;textSize=18f;setTextColor(Color.LTGRAY);setPadding(0,18,0,5)})}
 fun rb(s:String)=RadioButton(this).apply{text=s;setTextColor(Color.WHITE)}
 label("剩餘時間提醒（開始計時後沿用）");val ig=RadioGroup(this);val i0=rb("關閉");val i3=rb("每 3 分鐘");val i5=rb("每 5 分鐘");ig.addView(i0);ig.addView(i3);ig.addView(i5);when(p.getInt(INTERVAL,2)){1->i3.isChecked=true;2->i5.isChecked=true;else->i0.isChecked=true};root.addView(ig)
 label("時間到的聲音");val mg=RadioGroup(this);val ma=rb("預設鬧鐘聲");val mn=rb("通知鈴聲");val mr=rb("手機來電鈴聲");mg.addView(ma);mg.addView(mn);mg.addView(mr);when(p.getInt(MUSIC,0)){1->mn.isChecked=true;2->mr.isChecked=true;else->ma.isChecked=true};root.addView(mg)
 root.addView(Button(this).apply{text="🔊 預覽結束聲音";setOnClickListener{val type=when(mg.checkedRadioButtonId){mn.id->RingtoneManager.TYPE_NOTIFICATION;mr.id->RingtoneManager.TYPE_RINGTONE;else->RingtoneManager.TYPE_ALARM};RingtoneManager.getRingtone(this@SettingsActivity,RingtoneManager.getDefaultUri(type))?.play()}})
 label("現實鐘點報時");val cg=RadioGroup(this);val c0=rb("關閉");val c1=rb("每小時");val c2=rb("每 30 分鐘");val c3=rb("每 15 分鐘");cg.addView(c0);cg.addView(c1);cg.addView(c2);cg.addView(c3);when(p.getInt(CHIME,0)){1->c1.isChecked=true;2->c2.isChecked=true;3->c3.isChecked=true;else->c0.isChecked=true};root.addView(cg)
 root.addView(Button(this).apply{text="儲存並返回";setOnClickListener{val im=when(ig.checkedRadioButtonId){i3.id->1;i5.id->2;else->0};val mm=when(mg.checkedRadioButtonId){mn.id->1;mr.id->2;else->0};val cm=when(cg.checkedRadioButtonId){c1.id->1;c2.id->2;c3.id->3;else->0};p.edit().putInt(INTERVAL,im).putInt(MUSIC,mm).putInt(CHIME,cm).apply();finish()}})
 setContentView(root)}
}