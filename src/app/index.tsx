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

// 配置系統通知音效與彈出樣式
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
  const [volume, setVolume] = useState<VolumeLevel>('中');

  const endTimeRef = useRef<number | null>(null);
  const lastReminderRef = useRef<number | null>(null);
  const finishIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const finishTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // 初始化 Android 系統通知音效頻道（使用系統預設鬧鐘/通知鈴聲）
  useEffect(() => {
    (async () => {
      try {
        if (Platform.OS === 'android') {
          await Notifications.setNotificationChannelAsync('system-alarm', {
            name: '計時提醒鈴聲',
            importance: Notifications.AndroidImportance.MAX,
            sound: 'default', // Android 系統內建標準鈴聲
            vibrationPattern: [0, 400, 200, 400],
            enableVibrate: true,
            bypassDnd: true,
            lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
          });
        }
        const perm = await Notifications.getPermissionsAsync();
        if (perm.status !== 'granted') {
          await Notifications.requestPermissionsAsync();
        }
      } catch (err) {
        console.warn('Channel init error:', err);
      }
    })();
  }, []);

  // 播放 Android 系統內建標準提示鈴聲
  const playSystemBeep = async () => {
    try {
      Vibration.vibrate([0, 300, 150, 300]);
      // 立即排程 1 毫秒後的本地即時通知，觸發 Android 原生系統鈴聲
      await Notifications.scheduleNotificationAsync({
        content: {
          title: '倒數提醒',
          body: '嗶！時間提醒',
          sound: 'default',
          channelId: 'system-alarm',
        },
        trigger: null, // null 代表立即發送
      });
    } catch (e) {
      console.warn('Play ringtone error:', e);
    }
  };

  // 取消背景排程
  const cancelScheduledNotifications = async () => {
    try {
      await Notifications.cancelAllScheduledNotificationsAsync();
    } catch (e) {
      console.warn('Cancel error:', e);
    }
  };

  // 註冊背景休眠通知（5 分鐘與歸零）
  const scheduleTimerNotifications = async (totalSec: number) => {
    await cancelScheduledNotifications();

    try {
      // 1. 每 5 分鐘休眠提醒
      for (let secRemaining = 300; secRemaining < totalSec; secRemaining += 300) {
        const triggerDelay = totalSec - secRemaining;
        const minLeft = Math.round(secRemaining / 60);

        await Notifications.scheduleNotificationAsync({
          content: {
            title: 'VoiceTimer 倒數提醒',
            body: `還剩 ${minLeft} 分鐘`,
            sound: 'default',
            channelId: 'system-alarm',
          },
          trigger: {
            type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL,
            seconds: triggerDelay,
          },
        });
      }

      // 2. 時間到了休眠提醒
      await Notifications.scheduleNotificationAsync({
        content: {
          title: 'VoiceTimer 時間到！',
          body: '倒數已結束，請點擊停止',
          sound: 'default',
          channelId: 'system-alarm',
        },
        trigger: {
          type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL,
          seconds: totalSec,
        },
      });
    } catch (e) {
      console.warn('schedule error:', e);
    }
  };

  // 語音輸入：簡潔只報出數字
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

    // 【修改點】：極簡報時，只報出時間（例如：「10 分鐘。」）
    Speech.speak(`${value} 分鐘。`, {
      language: 'zh-TW',
      rate: 1.0,
    });
  });

  useSpeechRecognitionEvent('start', () => setIsListening(true));
  useSpeechRecognitionEvent('end', () => setIsListening(false));
  useSpeechRecognitionEvent('error', () => setIsListening(false));

  // 停止結束後的響鈴
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

  // 計時主迴圈
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

        // 簡短報「時間到」並響 Android 系統內建鈴聲
        Speech.speak('時間到', { language: 'zh-TW', rate: 1.0 });
        playSystemBeep();

        // 結束後每 3 秒響一次系統鈴聲，響滿 1 分鐘（60秒自動關閉）
        finishIntervalRef.current = setInterval(() => {
          playSystemBeep();
        }, 3000);

        finishTimeoutRef.current = setTimeout(() => {
          stopFinishedSound();
        }, 60000);
      }
    }, 500);

    return () => clearInterval(timer);
  }, [isRunning]);

  // 前台每 5 分鐘發出 Android 系統鈴聲提醒
  useEffect(() => {
    if (!isRunning || secondsLeft <= 0) return;

    const remainingMinutes = Math.ceil(secondsLeft / 60);
    if (
      remainingMinutes > 0 &&
      remainingMinutes % 5 === 0 &&
      lastReminderRef.current !== remainingMinutes
    ) {
      lastReminderRef.current = remainingMinutes;
      playSystemBeep();
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
      Speech.speak('請設定時間。', { language: 'zh-TW', rate: 1.0 });
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
        {/* 大型時間顯示區 */}
        <TouchableOpacity
          style={styles.timerArea}
          activeOpacity={0.8}
          onPress={announceRemainingTime}
        >
          <Text style={styles.timer}>{formatTime()}</Text>
        </TouchableOpacity>

        {/* 語音按鈕：與開始鍵同等大小樣式 */}
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

        {/* 主操作鍵：開始 / 暫停 / 停止 */}
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
