import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Platform,
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  Vibration,
  View,
} from 'react-native';
import * as Speech from 'expo-speech';
import * as Notifications from 'expo-notifications';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';
import {
  ExpoSpeechRecognitionModule,
  useSpeechRecognitionEvent,
} from 'expo-speech-recognition';

// 設定通知處理機制（鬧鐘等級：休眠時強制亮屏提示、播放聲音、最大優先權）
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
    shouldShowBanner: true,
    shouldShowList: true,
  }),
});

type VolumeLevel = '低' | '中' | '高';

export default function HomeScreen() {
  const [minutes, setMinutes] = useState<number | null>(null);
  const [secondsLeft, setSecondsLeft] = useState(0);
  const [isRunning, setIsRunning] = useState(false);
  const [isListening, setIsListening] = useState(false);
  const [isFinished, setIsFinished] = useState(false);
  const [volume, setVolume] = useState<VolumeLevel>('高');

  const endTimeRef = useRef<number | null>(null);
  const lastReminderRef = useRef<number | null>(null);
  const finishIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const finishTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 初始化 Android 系統「鬧鐘級」音訊通道（突破 Doze 深度休眠與靜音模式）
  useEffect(() => {
    (async () => {
      try {
        if (Platform.OS === 'android') {
          await Notifications.setNotificationChannelAsync('high-priority-alarm', {
            name: 'VoiceTimer 鬧鐘提醒',
            importance: Notifications.AndroidImportance.MAX,
            sound: 'default',
            vibrationPattern: [0, 500, 200, 500, 200, 500],
            enableVibrate: true,
            bypassDnd: true, // 忽視勿擾
            lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
            audioAttributes: {
              usage: Notifications.AndroidAudioUsage.ALARM, // 關鍵：以最高權限的鬧鐘通道發聲
              contentType: Notifications.AndroidAudioContentType.SONIFICATION,
            },
          });
        }
        const { status } = await Notifications.getPermissionsAsync();
        if (status !== 'granted') {
          await Notifications.requestPermissionsAsync();
        }
      } catch (err) {
        console.warn('Init notification error:', err);
      }
    })();
  }, []);

  // 播放極高頻率、最大聲嗶嗶聲（高音調 + 強烈急促震動）
  const playHighPitchBeep = (count: number = 3) => {
    try {
      // 急促且強烈的震動
      Vibration.vibrate([0, 200, 80, 200, 80, 200]);

      const volNum = volume === '低' ? 0.5 : volume === '中' ? 0.8 : 1.0;

      // 極限高頻 pitch: 2.0，短促清脆如金屬蜂鳴器
      for (let i = 0; i < count; i++) {
        setTimeout(() => {
          Speech.speak('嗶', {
            language: 'zh-TW',
            pitch: 2.0, // 2.0 為 Android TTS 最高音調，極度尖銳清晰
            rate: 1.8,
            volume: volNum,
          });
        }, i * 220);
      }
    } catch (e) {
      console.warn('Beep error:', e);
    }
  };

  const cancelScheduledNotifications = async () => {
    try {
      await Notifications.cancelAllScheduledNotificationsAsync();
    } catch (e) {
      console.warn('cancel notifications error:', e);
    }
  };

  // 背景休眠鬧鐘排程（使用精準日期 Date trigger 突破休眠凍結）
  const scheduleTimerNotifications = async (totalSec: number) => {
    await cancelScheduledNotifications();

    const now = Date.now();

    try {
      // 1. 每隔 5 分鐘的休眠排程通知
      for (let secRemaining = 300; secRemaining < totalSec; secRemaining += 300) {
        const triggerSec = totalSec - secRemaining;
        const triggerDate = new Date(now + triggerSec * 1000);
        const minLeft = Math.round(secRemaining / 60);

        await Notifications.scheduleNotificationAsync({
          content: {
            title: '⏰ 倒數提醒',
            body: `還剩下 ${minLeft} 分鐘！`,
            sound: 'default',
            channelId: 'high-priority-alarm',
          },
          trigger: {
            type: Notifications.SchedulableTriggerInputTypes.DATE,
            date: triggerDate,
          },
        });
      }

      // 2. 時間歸零（0 分鐘）深度休眠強制喚醒鬧鐘
      const finishDate = new Date(now + totalSec * 1000);
      await Notifications.scheduleNotificationAsync({
        content: {
          title: '🚨 時間到了！',
          body: '倒數計時已結束，請按停止！',
          sound: 'default',
          channelId: 'high-priority-alarm',
        },
        trigger: {
          type: Notifications.SchedulableTriggerInputTypes.DATE,
          date: finishDate,
        },
      });
    } catch (e) {
      console.warn('scheduleTimerNotifications error:', e);
    }
  };

  // 語音輸入：簡潔只回報時間
  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results?.[0]?.transcript ?? '';
    if (!text) return;
    setIsListening(false);

    const match = text.match(
      /(\d+(?:\.\d+)?)\s*(分鐘|分|min|mins|minute|minutes)/i
    );
    if (!match) {
      Speech.speak('請說倒數幾分鐘。', { language: 'zh-TW', rate: 1.0 });
      return;
    }

    const value = Math.floor(Number(match[1]));
    if (value < 1) {
      Speech.speak('至少一分鐘。', { language: 'zh-TW', rate: 1.0 });
      return;
    }
    if (value > 180) {
      Speech.speak('最多一百八十分鐘。', { language: 'zh-TW', rate: 1.0 });
      return;
    }

    setMinutes(value);
    setSecondsLeft(value * 60);
    setIsRunning(false);
    setIsFinished(false);
    endTimeRef.current = null;
    lastReminderRef.current = value;

    // 極簡回報：只講「X 分鐘。」
    Speech.speak(`${value} 分鐘。`, {
      language: 'zh-TW',
      rate: 1.0,
    });
  });

  useSpeechRecognitionEvent('start', () => setIsListening(true));
  useSpeechRecognitionEvent('end', () => setIsListening(false));
  useSpeechRecognitionEvent('error', () => setIsListening(false));

  const stopFinishedSound = () => {
    if (finishIntervalRef.current) {
      clearInterval(finishIntervalRef.current);
      finishIntervalRef.current = null;
    }
    if (finishTimeoutRef.current) {
      clearTimeout(finishTimeoutRef.current);
      finishTimeoutRef.current = null;
    }
    Speech.stop();
    Vibration.cancel();
    deactivateKeepAwake();
    cancelScheduledNotifications();
  };

  // 前台計時主迴圈
  useEffect(() => {
    if (!isRunning) return;

    activateKeepAwakeAsync();

    if (endTimeRef.current === null) {
      endTimeRef.current = Date.now() + secondsLeft * 1000;
    }

    const timer = setInterval(() => {
      const endTime = endTimeRef.current;
      if (!endTime) return;

      const remaining = Math.max(0, Math.ceil((endTime - Date.now()) / 1000));
      setSecondsLeft(remaining);

      // 時間到了
      if (remaining <= 0) {
        clearInterval(timer);
        setIsRunning(false);
        setIsFinished(true);
        endTimeRef.current = null;

        Speech.speak('時間到', { language: 'zh-TW', rate: 1.1 });
        playHighPitchBeep(4);

        // 結束後每 2.5 秒發出急促高頻聲，響滿 1 分鐘（60秒自動關閉）
        finishIntervalRef.current = setInterval(() => {
          playHighPitchBeep(3);
        }, 2500);

        finishTimeoutRef.current = setTimeout(() => {
          stopFinishedSound();
        }, 60000);
      }
    }, 500);

    return () => clearInterval(timer);
  }, [isRunning]);

  // 前台每 5 分鐘發出高頻嗶嗶聲
  useEffect(() => {
    if (!isRunning || secondsLeft <= 0) return;

    const remainingMinutes = Math.ceil(secondsLeft / 60);
    if (
      remainingMinutes > 0 &&
      remainingMinutes % 5 === 0 &&
      lastReminderRef.current !== remainingMinutes
    ) {
      lastReminderRef.current = remainingMinutes;
      playHighPitchBeep(3);
    }
  }, [secondsLeft, isRunning]);

  const startListening = async () => {
    if (isRunning || isFinished) return;
    if (Platform.OS !== 'android') {
      Alert.alert('提示', '目前以 Android 版本為主。');
      return;
    }

    try {
      const permission =
        await ExpoSpeechRecognitionModule.requestPermissionsAsync();
      if (!permission.granted) {
        Alert.alert('需要麥克風權限', '請允許使用麥克風。');
        return;
      }
      ExpoSpeechRecognitionModule.start({
        lang: 'zh-TW',
        interimResults: false,
        continuous: false,
      });
    } catch {
      setIsListening(false);
    }
  };

  const toggleTimer = async () => {
    if (isFinished) {
      stopFinishedSound();
      setIsFinished(false);
      setSecondsLeft(0);
      setMinutes(null);
      lastReminderRef.current = null;
      return;
    }

    if (secondsLeft <= 0) {
      Speech.speak('請先設定時間。', { language: 'zh-TW', rate: 1.0 });
      return;
    }

    if (isRunning) {
      setIsRunning(false);
      endTimeRef.current = null;
      deactivateKeepAwake();
      cancelScheduledNotifications();
      return;
    }

    endTimeRef.current = Date.now() + secondsLeft * 1000;
    setIsRunning(true);
    await scheduleTimerNotifications(secondsLeft);
  };

  const announceRemainingTime = () => {
    if (!isRunning || secondsLeft <= 0) return;
    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;
    if (m > 0 && s > 0) {
      Speech.speak(`${m} 分 ${s} 秒。`, { language: 'zh-TW', rate: 1.0 });
    } else if (m > 0) {
      Speech.speak(`${m} 分鐘。`, { language: 'zh-TW', rate: 1.0 });
    } else {
      Speech.speak(`${s} 秒。`, { language: 'zh-TW', rate: 1.0 });
    }
  };

  const formatTime = () => {
    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  };

  const volumeOptions: VolumeLevel[] = ['低', '中', '高'];

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        {/* 大型數字顯示區 */}
        <TouchableOpacity
          style={styles.timerArea}
          activeOpacity={0.8}
          onPress={announceRemainingTime}
        >
          <Text style={styles.timer}>{formatTime()}</Text>
        </TouchableOpacity>

        {/* 語音按鈕：與開始鍵同等大小 */}
        {!isRunning && !isFinished && (
          <>
            <TouchableOpacity
              style={[styles.mainButton, styles.voiceButton]}
              onPress={startListening}
              disabled={isListening}
            >
              <Text style={styles.voiceButtonText}>
                {isListening ? '🎙️ 正在聆聽中...' : '🎙️ 點擊語音設定時間'}
              </Text>
            </TouchableOpacity>

            <View style={styles.volumeRow}>
              {volumeOptions.map((item) => (
                <TouchableOpacity
                  key={item}
                  style={[
                    styles.volumeButton,
                    volume === item && styles.volumeSelected,
                  ]}
                  onPress={() => setVolume(item)}
                >
                  <Text
                    style={[
                      styles.volumeText,
                      volume === item && styles.volumeSelectedText,
                    ]}
                  >
                    {item}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </>
        )}

        {/* 開始 / 暫停 / 停止 按鈕 */}
        <TouchableOpacity
          style={[styles.mainButton, isFinished && styles.stopButton]}
          onPress={toggleTimer}
        >
          <Text style={styles.mainButtonText}>
            {isFinished ? '停止' : isRunning ? '暫停' : '開始'}
          </Text>
        </TouchableOpacity>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000000',
  },
  content: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingBottom: 20,
  },
  timerArea: {
    width: '100%',
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  timer: {
    fontSize: 96,
    fontWeight: 'bold',
    color: '#ffffff',
    letterSpacing: 3,
  },
  mainButton: {
    width: '85%',
    paddingVertical: 18,
    borderRadius: 18,
    backgroundColor: '#ffffff',
    alignItems: 'center',
    marginBottom: 16,
  },
  voiceButton: {
    backgroundColor: '#1f2937',
    borderWidth: 1.5,
    borderColor: '#3b82f6',
  },
  voiceButtonText: {
    fontSize: 20,
    fontWeight: 'bold',
    color: '#ffffff',
  },
  stopButton: {
    backgroundColor: '#ef4444',
  },
  mainButtonText: {
    fontSize: 24,
    fontWeight: 'bold',
    color: '#000000',
  },
  volumeRow: {
    flexDirection: 'row',
    marginBottom: 16,
    gap: 12,
  },
  volumeButton: {
    paddingHorizontal: 22,
    paddingVertical: 8,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#777777',
  },
  volumeSelected: {
    backgroundColor: '#ffffff',
    borderColor: '#ffffff',
  },
  volumeText: {
    color: '#ffffff',
    fontSize: 16,
  },
  volumeSelectedText: {
    color: '#000000',
    fontWeight: 'bold',
  },
});
