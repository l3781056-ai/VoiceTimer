import React, { useEffect, useRef, useState } from 'react';
import {
  Alert,
  NativeModules,
  Platform,
  SafeAreaView,
  StyleSheet,
  Text,
  TouchableOpacity,
  Vibration,
  View,
} from 'react-native';
import * as Speech from 'expo-speech';

const { VoiceTimerModule } = NativeModules;
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
  const lastSetSecondsRef = useRef(0);
  const pendingVoiceMinutesRef = useRef<number | null>(null);
  const voiceReleasedRef = useRef(false);
  const finishIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const finishTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

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

  const speakNormal = (text: string) => {
    try {
      const volNum = volume === '低' ? 0.4 : volume === '中' ? 0.75 : 1.0;
      Speech.stop();
      Speech.speak(text, {
        language: 'zh-TW',
        pitch: 1.0,
        rate: 1.0,
        volume: volNum,
      });
    } catch (e) {
      console.warn('Speech error:', e);
    }
  };

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
  };

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

      if (remaining <= 0) {
        clearInterval(timer);
        setIsRunning(false);
        setIsFinished(true);
        endTimeRef.current = null;

        // 保留本次設定的時間，不讓畫面歸零。
        setSecondsLeft(lastSetSecondsRef.current);

        speakNormal('時間到了，浩川祝你健康');
        Vibration.vibrate([0, 800, 400, 800]);

        finishIntervalRef.current = setInterval(() => {
          speakNormal('時間到了，浩川祝你健康');
          Vibration.vibrate([0, 600, 300, 600]);
        }, 4000);

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
      speakNormal(`還剩 ${remainingMinutes} 分鐘`);
      Vibration.vibrate([0, 300, 150, 300]);
    }
  }, [secondsLeft, isRunning]);

  const startTimerFromVoice = (value: number) => {
    const durationSeconds = value * 60;

    lastSetSecondsRef.current = durationSeconds;
    setMinutes(value);
    setSecondsLeft(durationSeconds);
    setIsFinished(false);
    lastReminderRef.current = value;

    endTimeRef.current = Date.now() + durationSeconds * 1000;
    setIsRunning(true);

    try {
      VoiceTimerModule?.startTimer(durationSeconds * 1000);
    } catch (e) {
      console.log('VoiceTimerModule start error:', e);
    }
  };

  const parseVoiceMinutes = (text: string): number | null => {
    const match = text.match(
      /(\d+(?:\.\d+)?)\s*(分鐘|分|min|mins|minute|minutes)/i
    );

    if (!match) return null;

    const value = Math.floor(Number(match[1]));
    if (!Number.isFinite(value)) return null;

    return value;
  };

  useSpeechRecognitionEvent('result', (event) => {
    const text = event.results?.[0]?.transcript ?? '';
    if (!text) return;

    setIsListening(false);

    const value = parseVoiceMinutes(text);

    if (value === null) {
      pendingVoiceMinutesRef.current = null;
      speakNormal('請說例如，倒數十分鐘，或倒數三十分鐘。');
      return;
    }

    if (value < 1) {
      pendingVoiceMinutesRef.current = null;
      speakNormal('倒數時間至少要一分鐘。');
      return;
    }

    if (value > 180) {
      pendingVoiceMinutesRef.current = null;
      speakNormal('時間不能超過一百八十分鐘。');
      return;
    }

    pendingVoiceMinutesRef.current = value;
    setMinutes(value);
    setSecondsLeft(value * 60);
    lastSetSecondsRef.current = value * 60;
    lastReminderRef.current = value;
    setIsFinished(false);

    // 先確認語音輸入的時間；倒數必須等手指放開才啟動。
    speakNormal(`好的，已設定倒數 ${value} 分鐘。`);

    if (voiceReleasedRef.current) {
      const confirmedValue = pendingVoiceMinutesRef.current;
      pendingVoiceMinutesRef.current = null;
      if (confirmedValue !== null) {
        startTimerFromVoice(confirmedValue);
      }
    }
  });

  useSpeechRecognitionEvent('start', () => {
    setIsListening(true);
  });

  useSpeechRecognitionEvent('end', () => {
    setIsListening(false);
  });

  useSpeechRecognitionEvent('error', (event) => {
    setIsListening(false);
    pendingVoiceMinutesRef.current = null;
    console.log('Speech recognition error:', event.error);
  });

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

      pendingVoiceMinutesRef.current = null;

      ExpoSpeechRecognitionModule.start({
        lang: 'zh-TW',
        interimResults: false,
        continuous: false,
      });
    } catch {
      setIsListening(false);
    }
  };

  const releaseVoiceButton = () => {
    voiceReleasedRef.current = true;

    const confirmedValue = pendingVoiceMinutesRef.current;
    if (confirmedValue !== null) {
      pendingVoiceMinutesRef.current = null;
      startTimerFromVoice(confirmedValue);
    } else if (isListening) {
      // 尚未辨識成功就放開，不啟動倒數。
      try {
        ExpoSpeechRecognitionModule.stop();
      } catch {
        // ignore
      }
    }
  };

  const pressVoiceButton = () => {
    voiceReleasedRef.current = false;
    startListening();
  };

  const toggleTimer = async () => {
    if (isFinished) {
      stopFinishedSound();
      setIsFinished(false);
      setSecondsLeft(0);
      setMinutes(null);
      lastSetSecondsRef.current = 0;
      lastReminderRef.current = null;

      try {
        VoiceTimerModule?.stopTimer();
      } catch (e) {
        console.log('VoiceTimerModule stop error:', e);
      }

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

      try {
        VoiceTimerModule?.stopTimer();
      } catch (e) {
        console.log('VoiceTimerModule stop error:', e);
      }

      return;
    }

    endTimeRef.current = Date.now() + secondsLeft * 1000;
    setIsRunning(true);

    try {
      VoiceTimerModule?.startTimer(secondsLeft * 1000);
    } catch (e) {
      console.log('VoiceTimerModule start error:', e);
    }
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
    return { minutes: String(m).padStart(2, '0'), seconds: String(s).padStart(2, '0') };
  };

  const volumeOptions: VolumeLevel[] = ['低', '中', '高'];
  const displayTime = formatTime();

  return (
    <SafeAreaView style={styles.container}>
      <View style={styles.content}>
        <TouchableOpacity
          style={styles.timerArea}
          activeOpacity={0.8}
          onPress={announceRemainingTime}
        >
          <View style={styles.timerRow}>
            <Text style={styles.timerMinutes}>{displayTime.minutes}</Text>
            <Text style={styles.timerColon}>:</Text>
            <Text style={styles.timerSeconds}>{displayTime.seconds}</Text>
          </View>
        </TouchableOpacity>

        {!isRunning && !isFinished && (
          <>
            <TouchableOpacity
              style={[styles.mainButton, styles.voiceButton]}
              activeOpacity={0.75}
              onPressIn={pressVoiceButton}
              onPressOut={releaseVoiceButton}
            >
              <Text style={styles.voiceButtonText}>
                {isListening ? '🎙️ 按住並說出時間...' : '🎙️ 按住語音設定時間'}
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
  timerRow: {
    flexDirection: 'row',
    alignItems: 'baseline',
    justifyContent: 'center',
  },
  timerMinutes: {
    fontSize: 144,
    fontWeight: 'bold',
    color: '#ffffff',
    letterSpacing: 3,
    lineHeight: 156,
  },
  timerColon: {
    fontSize: 96,
    fontWeight: 'bold',
    color: '#ffffff',
    lineHeight: 110,
  },
  timerSeconds: {
    fontSize: 48,
    fontWeight: 'bold',
    color: '#ffffff',
    letterSpacing: 1,
    lineHeight: 56,
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
