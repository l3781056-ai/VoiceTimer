import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  Platform,
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import * as Speech from 'expo-speech';
import { Audio } from 'expo-av';
import * as Notifications from 'expo-notifications';
import {
  ExpoSpeechRecognitionModule,
  useSpeechRecognitionEvent,
} from 'expo-speech-recognition';

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

  useEffect(() => {
    (async () => {
      if (Platform.OS === 'android') {
        await Notifications.setNotificationChannelAsync('timer-channel', {
          name: '倒數計時提醒',
          importance: Notifications.AndroidImportance.MAX,
          vibrationPattern: [0, 250, 250, 250],
          lightColor: '#FF231F7C',
          sound: 'default',
        });
      }
      const { status } = await Notifications.getPermissionsAsync();
      if (status !== 'granted') {
        await Notifications.requestPermissionsAsync();
      }
    })();
  }, []);

  const playBeep = async (count: number = 2) => {
    try {
      const beepBase64 =
        'data:audio/wav;base64,UklGRl9vT19XQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YU' +
        'AAAAAAA////wAAAP///wAAAP///wAAAP///wAAAP///wAAAP///wAAAP///wAAAP///wAAAP///wAAAP///wAAAP///wAA';
      const volNum = volume === '低' ? 0.3 : volume === '中' ? 0.7 : 1.0;
      for (let i = 0; i < count; i++) {
        const { sound } = await Audio.Sound.createAsync(
          { uri: beepBase64 },
          { shouldPlay: true, volume: volNum }
        );
        setTimeout(() => {
          sound.unloadAsync();
        }, 600);
      }
    } catch {
      Speech.speak('嗶、嗶', { language: 'zh-TW', rate: 1.5 });
    }
  };

  const cancelScheduledNotifications = async () => {
    await Notifications.cancelAllScheduledNotificationsAsync();
  };

  const scheduleBackgroundNotifications = async (totalSec: number) => {
    await cancelScheduledNotifications();

    for (let sec = 300; sec < totalSec; sec += 300) {
      const triggerSec = totalSec - sec;
      const remMin = Math.round(sec / 60);
      await Notifications.scheduleNotificationAsync({
        content: {
          title: 'VoiceTimer 倒數提醒',
          body: `還剩 ${remMin} 分鐘！`,
          sound: true,
          channelId: 'timer-channel',
        },
        trigger: {
          type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL,
          seconds: triggerSec,
        },
      });
    }

    await Notifications.scheduleNotificationAsync({
      content: {
        title: 'VoiceTimer 時間到了！',
        body: '倒數計時已結束，請點擊停止！',
        sound: true,
        channelId: 'timer-channel',
      },
      trigger: {
        type: Notifications.SchedulableTriggerInputTypes.TIME_INTERVAL,
        seconds: totalSec,
      },
    });
  };

  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results?.[0]?.transcript ?? '';
    if (!text) return;
    setIsListening(false);

    const match = text.match(
      /(\d+(?:\.\d+)?)\s*(分鐘|分|min|mins|minute|minutes)/i
    );
    if (!match) {
      Speech.speak('請說例如，倒數 10 分鐘，或倒數 30 分鐘。', {
        language: 'zh-TW',
        rate: 0.9,
      });
      return;
    }

    const value = Math.floor(Number(match[1]));
    if (value < 1) {
      Speech.speak('倒數時間至少要一分鐘。', { language: 'zh-TW', rate: 0.9 });
      return;
    }
    if (value > 180) {
      Speech.speak('時間不能超過一百八十分鐘。', { language: 'zh-TW', rate: 0.9 });
      return;
    }

    setMinutes(value);
    setSecondsLeft(value * 60);
    setIsRunning(false);
    setIsFinished(false);
    endTimeRef.current = null;
    lastReminderRef.current = value;

    Speech.speak(`好的，已設定倒數 ${value} 分鐘。`, {
      language: 'zh-TW',
      rate: 0.9,
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
    cancelScheduledNotifications();
  };

  useEffect(() => {
    if (!isRunning) return;

    if (endTimeRef.current === null) {
      endTimeRef.current = Date.now() + secondsLeft * 1000;
    }

    const timer = setInterval(() => {
      const endTime = endTimeRef.current;
      if (!endTime) return;

      const remaining = Math.max(0, Math.ceil((endTime - Date.now()) / 1000));
      setSecondsLeft(remaining);

      if (remaining <= 0) {
        clearInterval(timer);
        setIsRunning(false);
        setIsFinished(true);
        endTimeRef.current = null;

        playBeep(3);

        finishIntervalRef.current = setInterval(() => {
          playBeep(2);
        }, 2000);

        finishTimeoutRef.current = setTimeout(() => {
          stopFinishedSound();
        }, 60000);
      }
    }, 500);

    return () => clearInterval(timer);
  }, [isRunning]);

  useEffect(() => {
    if (!isRunning || secondsLeft <= 0) return;

    const remainingMinutes = Math.ceil(secondsLeft / 60);
    if (
      remainingMinutes > 0 &&
      remainingMinutes % 5 === 0 &&
      lastReminderRef.current !== remainingMinutes
    ) {
      lastReminderRef.current = remainingMinutes;
      playBeep(2);
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
        Alert.alert('需要麥克風權限', '請允許 VoiceTimer 使用麥克風。');
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
      Speech.speak('請先設定倒數時間。', { language: 'zh-TW', rate: 0.9 });
      return;
    }

    if (isRunning) {
      setIsRunning(false);
      endTimeRef.current = null;
      cancelScheduledNotifications();
      return;
    }

    endTimeRef.current = Date.now() + secondsLeft * 1000;
    setIsRunning(true);
    scheduleBackgroundNotifications(secondsLeft);
  };

  const announceRemainingTime = () => {
    if (!isRunning || secondsLeft <= 0) return;
    const m = Math.floor(secondsLeft / 60);
    const s = secondsLeft % 60;
    if (m > 0 && s > 0) {
      Speech.speak(`目前剩餘 ${m} 分 ${s} 秒。`, { language: 'zh-TW', rate: 0.9 });
    } else if (m > 0) {
      Speech.speak(`目前剩餘 ${m} 分鐘。`, { language: 'zh-TW', rate: 0.9 });
    } else {
      Speech.speak(`目前剩餘 ${s} 秒。`, { language: 'zh-TW', rate: 0.9 });
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
