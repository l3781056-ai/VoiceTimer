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

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowAlert: true,
    shouldPlaySound: true,
    shouldSetBadge: true,
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

  // 初始化具備喚醒螢幕能力的 Android 原生鬧鐘頻道
  useEffect(() => {
    (async () => {
      try {
        if (Platform.OS === 'android') {
          await Notifications.setNotificationChannelAsync('system-alarm-channel', {
            name: 'VoiceTimer 鬧鐘提醒',
            importance: Notifications.AndroidImportance.MAX,
            sound: 'default',
            vibrationPattern: [0, 800, 400, 800],
            enableVibrate: true,
            bypassDnd: true,
            lockscreenVisibility: Notifications.AndroidNotificationVisibility.PUBLIC,
            audioAttributes: {
              usage: Notifications.AndroidAudioUsage.ALARM,
              contentType: Notifications.AndroidAudioContentType.SONIFICATION,
              flags: {
                enforceAudibility: true,
                requestHardwareAudioVideoSynchronization: false,
              },
            },
          });
        }
        const { status } = await Notifications.getPermissionsAsync();
        if (status !== 'granted') {
          await Notifications.requestPermissionsAsync();
        }
      } catch (err) {
        console.warn('Notification init error:', err);
      }
    })();
  }, []);

  // 標準自然語音播報（正常音調 pitch: 1.0）
  const speakNormal = (text: string) => {
    try {
      const volNum = volume === '低' ? 0.4 : volume === '中' ? 0.75 : 1.0;
      Speech.speak(text, {
        language: 'zh-TW',
        pitch: 1.0, // 正常平常常用音調
        rate: 1.0,  // 標準正常語速
        volume: volNum,
      });
    } catch (e) {
      console.warn('Speech error:', e);
    }
  };

  // 取消所有排程
  const cancelScheduledNotifications = async () => {
    try {
      await Notifications.cancelAllScheduledNotificationsAsync();
    } catch (e) {
      console.warn('Cancel notifications error:', e);
    }
  };

  // 註冊鎖屏休眠排程通知
  const scheduleTimerNotifications = async (totalSec: number) => {
    await cancelScheduledNotifications();
    const now = Date.now();

    try {
      // 每 5 分鐘休眠喚醒提醒
      for (let secRemaining = 300; secRemaining < totalSec; secRemaining += 300) {
        const triggerSec = totalSec - secRemaining;
        const triggerDate = new Date(now + triggerSec * 1000);
        const minLeft = Math.round(secRemaining / 60);

        await Notifications.scheduleNotificationAsync({
          content: {
            title: '⏰ 倒數提醒',
            body: `還剩下 ${minLeft} 分鐘`,
            sound: 'default',
            channelId: 'system-alarm-channel',
            priority: Notifications.AndroidNotificationPriority.MAX,
          },
          trigger: {
            type: Notifications.SchedulableTriggerInputTypes.DATE,
            date: triggerDate,
          },
        });
      }

      // 倒數歸零（0 分鐘）鬧鐘喚醒提醒
      const finishDate = new Date(now + totalSec * 1000);
      await Notifications.scheduleNotificationAsync({
        content: {
          title: '🚨 時間到了！',
          body: '倒數時間到，請按停止',
          sound: 'default',
          channelId: 'system-alarm-channel',
          priority: Notifications.AndroidNotificationPriority.MAX,
        },
        trigger: {
          type: Notifications.SchedulableTriggerInputTypes.DATE,
          date: finishDate,
        },
      });
    } catch (e) {
      console.warn('Schedule error:', e);
    }
  };

  // 語音辨識：簡潔只報時間數字
  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results?.[0]?.transcript ?? '';
    if (!text) return;
    setIsListening(false);

    const match = text.match(
      /(\d+(?:\.\d+)?)\s*(分鐘|分|min|mins|minute|minutes)/i
    );
    if (!match) {
      speakNormal('請說倒數幾分鐘');
      return;
    }

    const value = Math.floor(Number(match[1]));
    if (value < 1) {
      speakNormal('至少一分鐘');
      return;
    }
    if (value > 180) {
      speakNormal('最多一百八十分鐘');
      return;
    }

    setMinutes(value);
    setSecondsLeft(value * 60);
    setIsRunning(false);
    setIsFinished(false);
    endTimeRef.current = null;
    lastReminderRef.current = value;

    // 簡潔報時：例如「10 分鐘」
    speakNormal(`${value} 分鐘`);
  });

  useSpeechRecognitionEvent('start', () => setIsListening(true));
  useSpeechRecognitionEvent('end', () => setIsListening(false));
  useSpeechRecognitionEvent('error', () => setIsListening(false));

  // 停止結束提醒
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

  // 前台計時器迴圈
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

      // 時間歸零
      if (remaining <= 0) {
        clearInterval(timer);
        setIsRunning(false);
        setIsFinished(true);
        endTimeRef.current = null;

        // 倒數到 0 時以正常自然音調語音通知，並觸發原生鬧鐘警報震動
        speakNormal('倒數時間到了');
        Vibration.vibrate([0, 800, 400, 800]);

        // 持續提醒，滿 1 分鐘自動結束
        finishIntervalRef.current = setInterval(() => {
          speakNormal('倒數時間到了');
          Vibration.vibrate([0, 600, 300, 600]);
        }, 4000);

        finishTimeoutRef.current = setTimeout(() => {
          stopFinishedSound();
        }, 60000);
      }
    }, 500);

    return () => clearInterval(timer);
  }, [isRunning]);

  // 前台每 5 分鐘提醒
  useEffect(() => {
    if (!isRunning || secondsLeft <= 0) return;

    const remainingMinutes = Math.ceil(secondsLeft / 60);
    if (
      remainingMinutes > 0 &&
      remainingMinutes % 5 === 0 &&
      lastReminderRef.current !== remainingMinutes
    ) {
      lastReminderRef.current = remainingMinutes;
      // 語音與系統震動提醒
      speakNormal(`還剩 ${remainingMinutes} 分鐘`);
      Vibration.vibrate([0, 300, 150, 300]);
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
      speakNormal('請先設定時間');
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
      speakNormal(`${m} 分 ${s} 秒`);
    } else if (m > 0) {
      speakNormal(`${m} 分鐘`);
    } else {
      speakNormal(`${s} 秒`);
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
        <TouchableOpacity
          style={styles.timerArea}
          activeOpacity={0.8}
          onPress={announceRemainingTime}
        >
          <Text style={styles.timer}>{formatTime()}</Text>
        </TouchableOpacity>

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
